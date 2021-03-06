/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.remoting.Channel;
import hudson.remoting.RequestAbortedException;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.OfflineCause.ByCLI;
import hudson.slaves.RetentionStrategy;
import hudson.util.RemotingDiagnostics;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.util.Timer;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DeleteTagsRequest;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.RebootInstancesRequest;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
/**
 * Slave running on EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2AbstractSlave extends Slave {
    /**
     * Initial number of seconds to wait before trying to reconnect to a rebooting slave
     */
    private static final int REBOOT_TIMEOUT = 30;
    /**
     * Reconnect still pending recheck period in seconds
     */
    private static final int REBOOT_RECONNECT_PENDING = 5;

    private static final OfflineCause REBOOT_OFFLINE_CAUSE = new ByCLI("Rebooting after build");
    
    protected String instanceId;

    /**
     * Comes from {@link SlaveTemplate#initScript}.
     */
    public final String initScript;
    public final String tmpDir;
    public final String remoteAdmin; // e.g. 'ubuntu'
    
    
    public final String jvmopts; //e.g. -Xmx1g
    public final boolean stopOnTerminate;
    public final boolean rebootAfterBuild;
    public final boolean useJnlp;
    public final String idleTerminationMinutes;
    public final boolean usePrivateDnsName;
    public final boolean useDedicatedTenancy;
    public List<EC2Tag> tags;
    public final String cloudName;
    public AMITypeData amiType;

    // Temporary stuff that is obtained live from EC2
    public transient String publicDNS;
    public transient String privateDNS;

    /* The last instance data to be fetched for the slave */
    protected transient Instance lastFetchInstance = null;

    /* The time at which we fetched the last instance data */
    protected transient long lastFetchTime;

    /* The time (in milliseconds) after which we will always re-fetch externally changeable EC2 data when we are asked for it */
    protected static final long MIN_FETCH_TIME = 20 * 1000;


    protected final int launchTimeout;
    
    protected transient volatile Future<?> ongoingRebootReconnect;

    // Deprecated by the AMITypeData data structure
    @Deprecated
    protected transient int sshPort;
    @Deprecated
    public transient String rootCommandPrefix; // e.g. 'sudo'

    private transient long createdTime;

    public static final String TEST_ZONE = "testZone";


    public EC2AbstractSlave(String name, String instanceId, String description, String remoteFS, int numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy<EC2Computer> retentionStrategy, String initScript, List<? extends NodeProperty<?>> nodeProperties, String remoteAdmin, String jvmopts, boolean stopOnTerminate, String idleTerminationMinutes, List<EC2Tag> tags, String cloudName, boolean usePrivateDnsName, boolean useDedicatedTenancy, int launchTimeout, AMITypeData amiType, boolean rebootAfterBuild, boolean useJnlp) throws FormException, IOException {

        super(name, "", remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);

        this.instanceId = instanceId;
        this.initScript  = initScript;
        this.tmpDir  = tmpDir;
        this.remoteAdmin = remoteAdmin;
        this.jvmopts = jvmopts;
        this.stopOnTerminate = stopOnTerminate;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.tags = tags;
        this.usePrivateDnsName = usePrivateDnsName;
        this.useDedicatedTenancy = useDedicatedTenancy;
        this.cloudName = cloudName;
        this.launchTimeout = launchTimeout;
        this.amiType = amiType;
        this.rebootAfterBuild = rebootAfterBuild;
        this.useJnlp = useJnlp;
        readResolve();
    }

    protected Object readResolve() {
    	/*
    	 * If instanceId is null, this object was deserialized from an old
    	 * version of the plugin, where this field did not exist (prior to
    	 * version 1.18). In those versions, the node name *was* the instance
    	 * ID, so we can get it from there.
    	 */
    	if (instanceId == null) {
    		instanceId = getNodeName();
    	}
    	
    	if (amiType == null) {
    	    amiType = new UnixData(rootCommandPrefix, Integer.toString(sshPort));
    	}

    	return this;
    }
    
    public EC2Cloud getCloud() {
    	return (EC2Cloud) Hudson.getInstance().getCloud(cloudName);
    }

    /**
     * See http://aws.amazon.com/ec2/instance-types/
     */
    /*package*/ static int toNumExecutors(InstanceType it) {
        switch (it) {
        case T1Micro:       return 1;
        case M1Small:       return 1;
        case M1Medium:      return 2;
        case M1Large:       return 4;
        case C1Medium:      return 5;
        case M2Xlarge:      return 6;
        case C3Large:       return 7;
        case M1Xlarge:      return 8;
        case M22xlarge:     return 13;
        case M3Xlarge:      return 13;
        case C3Xlarge:      return 14;
        case C1Xlarge:      return 20;
        case M24xlarge:     return 26;
        case M32xlarge:     return 26;
        case G22xlarge:     return 26;
        case C32xlarge:     return 28;
        case Cc14xlarge:    return 33;
        case Cg14xlarge:    return 33;
        case Hi14xlarge:    return 35;
        case Hs18xlarge:    return 35;
        case C34xlarge:     return 55;
        case Cc28xlarge:    return 88;
        case Cr18xlarge:    return 88;
        case C38xlarge:     return 108;
        //We don't have a suggestion, but we don't want to fail completely surely?
        default:            return 1;
        }
    }

    /**
     * EC2 instance ID.
     */
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public Computer createComputer() {
        return new EC2Computer(this);
    }

    public static Instance getInstance(String instanceId, EC2Cloud cloud) {
        Instance i = null;
        try {
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            request.setInstanceIds(Collections.<String>singletonList(instanceId));
            if (cloud == null)
                return null;
            AmazonEC2 ec2 = cloud.connect();
            List<Reservation> reservations = ec2.describeInstances(request).getReservations();
            if (reservations.size() > 0) {
                List<Instance> instances = reservations.get(0).getInstances();
                if (instances.size() > 0)
                    i = instances.get(0);
            }
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to fetch EC2 instance: "+instanceId,e);
        }
    	return i;
    }

    /**
     * Terminates the instance in EC2.
     */
    public abstract void terminate();

    void reboot()
    {
        final EC2Computer computer = (EC2Computer) toComputer();
        LOGGER.info("Preparing to reboot EC2 instance " + getInstanceId() + ", computer " + computer.getName());
        // Firstly, take the computer offline
        takeOffline(REBOOT_OFFLINE_CAUSE);
        // Now disconnect from the instance and wait for disconnect to complete
        disconnect(REBOOT_OFFLINE_CAUSE);
        try {
            AmazonEC2 ec2 = getCloud().connect();
            RebootInstancesRequest request = new RebootInstancesRequest(Collections.singletonList(getInstanceId()));
            LOGGER.fine("Sending reboot request for " + getInstanceId());
            ec2.rebootInstances(request);
            LOGGER.info("EC2 instance reboot request sent for " + getInstanceId());
        }
        catch (AmazonClientException e) {
            Instance i = getInstance(getInstanceId(), getCloud());
            LOGGER.log(Level.WARNING, "Failed to reboot EC2 instance: " + getInstanceId() + " info: " + ((i != null) ? i : ""), e);
        }
        Timer.get().schedule(new RebootMonitor(), REBOOT_TIMEOUT, TimeUnit.SECONDS);
    }

    void stop() {
        disconnect(null);
        try {
            AmazonEC2 ec2 = getCloud().connect();
            StopInstancesRequest request = new StopInstancesRequest(
                    Collections.singletonList(getInstanceId()));
	        LOGGER.fine("Sending stop request for " + getInstanceId());
            ec2.stopInstances(request);
            LOGGER.info("EC2 instance stop request sent for " + getInstanceId());
            toComputer().disconnect(null);
        } catch (AmazonClientException e) {
            Instance i = getInstance(getInstanceId(), getCloud());
            LOGGER.log(Level.WARNING, "Failed to stop EC2 instance: "+getInstanceId() + " info: "+((i != null)?i:"") , e);
        }
    }

    boolean terminateInstance() {
        disconnect(null);
        try {
            AmazonEC2 ec2 = getCloud().connect();
            TerminateInstancesRequest request = new TerminateInstancesRequest(Collections.singletonList(getInstanceId()));
	        LOGGER.fine("Sending terminate request for " + getInstanceId());
            ec2.terminateInstances(request);
            LOGGER.info("EC2 instance terminate request sent for "+getInstanceId());
            return true;
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to terminate EC2 instance: "+getInstanceId(),e);
            return false;
        }
    }

    void takeOffline(OfflineCause cause)
    {
        final EC2Computer computer = (EC2Computer) toComputer();
        if (computer != null) {
            computer.setTemporarilyOffline(true, REBOOT_OFFLINE_CAUSE);
            LOGGER.fine("EC2 instance " + getInstanceId() + ": set computer " + computer.getName() + " offline");
        }
    }

    void disconnect(OfflineCause cause)
    {
        // Stop slave process if necessary
        stopSlaveProcess();
        final EC2Computer computer = (EC2Computer) toComputer();
        if (computer != null) {
            try {
                LOGGER.info("EC2 instance " + getInstanceId() + ": disconnecting " + computer.getName());
                computer.disconnect(REBOOT_OFFLINE_CAUSE).get();
                LOGGER.info("EC2 instance " + getInstanceId() + ": disconnected " + computer.getName());
            }
            catch (Exception e) {
                Instance i = getInstance(getInstanceId(), getCloud());
                LOGGER.log(useJnlp ? Level.FINER : Level.WARNING, "Error while disconnecting from EC2 instance: "
                        + getInstanceId() + " info: " + ((i != null) ? i : ""), e);
            }
        }
    }

    void stopSlaveProcess()
    {
        if (useJnlp) {
            final EC2Computer computer = (EC2Computer) toComputer();
            if (computer != null) {
                final Channel channel = computer.getChannel();
                if (channel != null) {
                    try {
                        LOGGER.info("EC2 instance " + getInstanceId() + ": shutting down JNLP agent " + computer.getName());
                        RemotingDiagnostics.executeGroovy("System.exit(0);", channel);
                    }
                    catch (RequestAbortedException e) {
                        LOGGER.log(Level.FINER, "EC2 instance " + getInstanceId()
                                + ": received an expected request abort error shutting down JNLP slave process", e);
                    }
                    catch (IOException e) {
                        LOGGER.log(Level.WARNING, "EC2 instance " + getInstanceId()
                                + ": received an unexpected I/O error shutting down JNLP slave process", e);
                    }
                    catch (InterruptedException e) {
                        LOGGER.log(Level.WARNING, "EC2 instance " + getInstanceId()
                                + ": interrupted while shutting down JNLP slave process", e);
                    }
                    LOGGER.info("EC2 instance " + getInstanceId() + ": shut down JNLP agent " + computer.getName());
                }
            }
        }
    }

    @Override
	public Node reconfigure(final StaplerRequest req, JSONObject form) throws FormException {
        if (form == null) {
            return null;
        }

        EC2AbstractSlave result = (EC2AbstractSlave) super.reconfigure(req, form);

        /* Get rid of the old tags, as represented by ourselves. */
        clearLiveInstancedata();

        /* Set the new tags, as represented by our successor */
        result.pushLiveInstancedata();
        return result;
    }

    void idleTimeout() {
    	LOGGER.info("EC2 instance idle time expired: "+getInstanceId());
    	if (!stopOnTerminate) {
    		terminate();
    	} else {
    		stop();
    	}
    }

    public long getLaunchTimeoutInMillis() {
        // this should be fine as long as launchTimeout remains an int type
        return launchTimeout * 1000L;
    }

    String getRemoteAdmin() {
        if (remoteAdmin == null || remoteAdmin.length() == 0)
            return amiType.isWindows() ? "Administrator" : "root";
        return remoteAdmin;
    }

    String getRootCommandPrefix() {
        String commandPrefix = amiType.isUnix() ? ((UnixData)amiType).getRootCommandPrefix() : "";
        if (commandPrefix == null || commandPrefix.length() == 0)
            return "";
        return commandPrefix + " ";
    }

    String getJvmopts() {
        return Util.fixNull(jvmopts);
    }

    public int getSshPort() {
        String sshPort = amiType.isUnix() ? ((UnixData)amiType).getSshPort() : "22";
        if (sshPort == null || sshPort.length() == 0)
            return 22;
        
        int port = 0;
        try {
            port = Integer.parseInt(sshPort);
        } catch (Exception e) {
        }
        return port!=0 ? port : 22;
    }

    public boolean getStopOnTerminate() {
        return stopOnTerminate;
    }

	/**
	 * Called when the slave is connected to Jenkins
	 */
	public void onConnected() {
		// Do nothing by default.
	}

    protected boolean isAlive(boolean force) {
        fetchLiveInstanceData(force);
        if (lastFetchInstance == null)
        	return false;
        if (lastFetchInstance.getState().getName().equals(InstanceStateName.Terminated.toString()))
        	return false;
        return true;
    }

    /* Much of the EC2 data is beyond our direct control, therefore we need to refresh it from time to
       time to ensure we reflect the reality of the instances. */
    protected void fetchLiveInstanceData( boolean force ) throws AmazonClientException {
		/* If we've grabbed the data recently, don't bother getting it again unless we are forced */
        long now = System.currentTimeMillis();
        if ((lastFetchTime > 0) && (now - lastFetchTime < MIN_FETCH_TIME) && !force) {
            return;
        }

        if (getInstanceId() == null || getInstanceId() == ""){
          /* The getInstanceId() implementation on EC2SpotSlave can return null if the spot request doesn't
           * yet know the instance id that it is starting. What happens is that null is passed to getInstanceId()
           * which searches AWS but without an instanceID the search returns some random box. We then fetch
           * its metadata, including tags, and then later, when the spot request eventually gets the
           * instanceID correctly we push the saved tags from that random box up to the new spot resulting in
           * confusion and delay.
           */
          return;
        }

        Instance i = getInstance(getInstanceId(), getCloud());

        lastFetchTime = now;
        lastFetchInstance = i;
        if (i == null)
            return;

        publicDNS = i.getPublicDnsName();
        privateDNS = i.getPrivateIpAddress();
        createdTime = i.getLaunchTime().getTime();
        tags = new LinkedList<EC2Tag>();

        for (Tag t : i.getTags()) {
            tags.add(new EC2Tag(t.getKey(), t.getValue()));
        }
    }


	/* Clears all existing tag data so that we can force the instance into a known state */
    protected void clearLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getInstanceId(), getCloud());

        /* Now that we have our instance, we can clear the tags on it */
        if (!tags.isEmpty()) {
            HashSet<Tag> inst_tags = new HashSet<Tag>();

            for(EC2Tag t : tags) {
                inst_tags.add(new Tag(t.getName(), t.getValue()));
            }

            DeleteTagsRequest tag_request = new DeleteTagsRequest();
            tag_request.withResources(inst.getInstanceId()).setTags(inst_tags);
            getCloud().connect().deleteTags(tag_request);
        }
    }


    /* Sets tags on an instance.  This will not clear existing tag data, so call clearLiveInstancedata if needed */
    protected void pushLiveInstancedata() throws AmazonClientException {
        Instance inst = getInstance(getInstanceId(), getCloud());

        /* Now that we have our instance, we can set tags on it */
        if (tags != null && !tags.isEmpty()) {
            HashSet<Tag> inst_tags = new HashSet<Tag>();

            for(EC2Tag t : tags) {
                inst_tags.add(new Tag(t.getName(), t.getValue()));
            }

            CreateTagsRequest tag_request = new CreateTagsRequest();
            tag_request.withResources(inst.getInstanceId()).setTags(inst_tags);
            getCloud().connect().createTags(tag_request);
        }
    }

    public String getPublicDNS() {
        fetchLiveInstanceData(false);
        return publicDNS;
    }

    public String getPrivateDNS() {
        fetchLiveInstanceData(false);
        return privateDNS;
    }

    public List<EC2Tag> getTags() {
        fetchLiveInstanceData(false);
        return Collections.unmodifiableList(tags);
    }

    public long getCreatedTime() {
        fetchLiveInstanceData(false);
        return createdTime;
    }

    public boolean getUsePrivateDnsName() {
        return usePrivateDnsName;
    }
    
    public String getAdminPassword() {
        return amiType.isWindows() ? ((WindowsData)amiType).getPassword() : "";
    }

    public boolean isUseHTTPS() {
        return amiType.isWindows() ? ((WindowsData)amiType).isUseHTTPS() : false;
    }

    public int getBootDelay() {
        return amiType.isWindows() ? ((WindowsData)amiType).getBootDelayInMillis() : 0;
    }

    public static ListBoxModel fillZoneItems(AWSCredentialsProvider credentialsProvider, String region) {
		ListBoxModel model = new ListBoxModel();
		if (AmazonEC2Cloud.testMode) {
			model.add(TEST_ZONE);
			return model;
		}

		if (!StringUtils.isEmpty(region)) {
			AmazonEC2 client = EC2Cloud.connect(credentialsProvider, AmazonEC2Cloud.getEc2EndpointUrl(region));
			DescribeAvailabilityZonesResult zones = client.describeAvailabilityZones();
			List<AvailabilityZone> zoneList = zones.getAvailabilityZones();
			model.add("<not specified>", "");
			for (AvailabilityZone z : zoneList) {
				model.add(z.getZoneName(), z.getZoneName());
			}
		}
		return model;
	}

    /*
     * Used to determine if the slave is On Demand or Spot
     */
    abstract public String getEc2Type();

    public static abstract class DescriptorImpl extends SlaveDescriptor {

    	@Override
		public abstract String getDisplayName();

		@Override
		public boolean isInstantiable() {
			return false;
		}

		public ListBoxModel doFillZoneItems(@QueryParameter boolean useInstanceProfileForCredentials,
				@QueryParameter String accessId, @QueryParameter String secretKey, @QueryParameter String region) {
			AWSCredentialsProvider credentialsProvider = EC2Cloud.createCredentialsProvider(useInstanceProfileForCredentials, accessId, secretKey);
			return fillZoneItems(credentialsProvider, region);
		}
		
		public List<Descriptor<AMITypeData>> getAMITypeDescriptors()
		{
		    return Hudson.getInstance().<AMITypeData,Descriptor<AMITypeData>>getDescriptorList(AMITypeData.class);
		}
	}

    private class RebootMonitor implements Runnable
    {

        /* (non-Javadoc)
         * @see java.util.TimerTask#run()
         */
        @Override
        public void run()
        {
            LOGGER.fine("Reboot monitor: woke up for EC2 instance " + getInstanceId());
            EC2Computer computer = (EC2Computer) toComputer();
            if (ongoingRebootReconnect != null) {
                LOGGER.finer("Reboot monitor: EC2 instance " + getInstanceId() + " found ongoing reconnect pending");
                if (ongoingRebootReconnect.isDone()) {
                    LOGGER.info("Reboot monitor: EC2 instance " + getInstanceId() + " ongoing reconnect is done");
                    if (computer.getChannel() == null) {
                        LOGGER.info("Reboot monitor: EC2 instance " + getInstanceId() + " is not connected");
                        if (computer.getOfflineCause() instanceof OfflineCause.LaunchFailed) {
                            Throwable problem = null;
                            try {
                                ongoingRebootReconnect.get();
                            }
                            catch (ExecutionException e) {
                                problem = e.getCause();
                            }
                            catch (InterruptedException e) {
                                // Should never happen
                                problem = e;
                            }
                            LOGGER.log(Level.WARNING, "Reboot monitor: EC2 instance " + getInstanceId()
                                    + " reconnect launch attempt failed, will try later", problem);
                            ongoingRebootReconnect = null; // want happens-before with next line
                            Timer.get().schedule(new RebootMonitor(), REBOOT_TIMEOUT, TimeUnit.SECONDS);
                        }
                        else {
                            // If offline cause is not because launch failed, we will not try to reconnect
                            LOGGER.log(Level.WARNING, "Reboot monitor: EC2 instance " + getInstanceId()
                                    + " experienced failure '" + computer.getOfflineCauseReason()
                                    + "' while we tried reconnecting. Reboot handling ABORTED");
                        }
                    }
                    else {
                        computer.setTemporarilyOffline(false, null);
                        LOGGER.info("Reboot monitor: EC2 instance " + getInstanceId() + " is now online");
                    }
                    // If computer is online or we're not retrying to reconnect we're done
                    ongoingRebootReconnect = null;
                }
                else {
                    LOGGER.finer("Reboot monitor: EC2 instance " + getInstanceId()
                            + " rescheduling to check on pending reconnect");
                    // Reconnect is not done, recheck status soon
                    Timer.get().schedule(new RebootMonitor(), REBOOT_RECONNECT_PENDING, TimeUnit.SECONDS);
                }
            }
            else {
                if (isAlive(true)) {
                    if (computer.getOfflineCause() == REBOOT_OFFLINE_CAUSE) {
                        LOGGER.info("Reboot monitor: EC2 instance " + getInstanceId() + " starting reboot reconnect");
                        // If we're rebooting and intend to continue rebooting
                        // Connect and schedule checks
                        ongoingRebootReconnect = computer.connect(false);
                        Timer.get().schedule(new RebootMonitor(), REBOOT_RECONNECT_PENDING, TimeUnit.SECONDS);
                    }
                }
                else {
                    // Give up trying to reconnect, instance is gone
                    ongoingRebootReconnect = null;
                    LOGGER.warning("EC2 instance " + getInstanceId() + " has been terminated or we lost access");
                }
            }
        }
    }
    
    private static final Logger LOGGER = Logger.getLogger(EC2AbstractSlave.class.getName());
}
