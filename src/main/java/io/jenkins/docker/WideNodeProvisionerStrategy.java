package io.jenkins.docker;

import static hudson.slaves.NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
import static hudson.slaves.NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import com.nirima.jenkins.plugins.docker.DockerCloud;
import com.nirima.jenkins.plugins.docker.DockerTemplate;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.Strategy;
import hudson.slaves.NodeProvisioner.StrategyDecision;
import java.util.Collection;
import java.util.LinkedList;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

public class WideNodeProvisionerStrategy extends Strategy {

    private static final Logger LOGGER = Logger.getLogger(WideNodeProvisionerStrategy.class.getName());

    @NonNull
    @Override
    public StrategyDecision apply(@NonNull NodeProvisioner.StrategyState state) {
        if (Jenkins.get().isQuietingDown()) {
            return StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        final Label label = state.getLabel();
        final CloudQueue queue = new CloudQueue(label);

        // Taken from FastNodeProvisionerStrategy. Calculate the how much of jobs in the queue
        // needs to be provisioned.
        LoadStatistics.LoadStatisticsSnapshot snapshot = state.getSnapshot();
        LOGGER.log(FINEST, "Available executors={0}, connecting={1}, planned={2}", new Object[] {
            snapshot.getAvailableExecutors(), snapshot.getConnectingExecutors(), state.getPlannedCapacitySnapshot()
        });
        int availableCapacity = snapshot.getAvailableExecutors()
                + snapshot.getConnectingExecutors()
                + state.getPlannedCapacitySnapshot();

        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(FINE, "Available capacity={0}, currentDemand={1}", new Object[] {availableCapacity, currentDemand});

        while (availableCapacity < currentDemand) {

            DockerCloud cloud = queue.pop();
            if (cloud == null) {
                LOGGER.log(FINE, "Provisioning not complete, consulting remaining strategies");
                // for now, we couldn't provision all the demands
                return CONSULT_REMAINING_STRATEGIES;
            }

            // we only provision single job at a time
            Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(label, 1);
            if (plannedNodes.isEmpty()) {
                LOGGER.log(FINE, "No more nodes to provision from {0}", cloud.name);
                // we are not pushing back this cloud into the queue since it does not
                // take any more jobs for this label.
                continue;
            }
            LOGGER.log(FINE, "Planned {0} new nodes on {1}", new Object[] {plannedNodes.size(), cloud.name});
            state.recordPendingLaunches(plannedNodes);
            availableCapacity += 1;

            // push the current cloud back into the queue to have the usage
            // recalculated and reordered
            queue.push(cloud);
        }
        LOGGER.log(FINE, "Provisioning completed");
        return PROVISIONING_COMPLETED;
    }

    /**
     * A queue that maintains CloudEntry objects in sorted order based on their usage values.
     */
    private static class CloudQueue {
        private Label label;
        private LinkedList<CloudEntry> queue = new LinkedList<>();

        /**
         * Default constructor for CloudQueue.
         */
        public CloudQueue(Label label) {
            this.label = label;
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof DockerCloud) {
                    this.push((DockerCloud) cloud);
                }
            }
        }

        private float getUsage(Label label, DockerCloud cloud) {
            if (!cloud.canProvision(label)) {
                return 1.0f;
            }
            int running = 0;
            for (DockerTemplate t : cloud.getTemplates(label)) {
                running += cloud.countContainersInProgress(t);
            }
            // This is coarse calculation of usage.  It does not take into account of:
            // 1. The template cap
            // 2. Planned containers in the queue
            // Those are not available outside of the DockerCloud
            return (float) running / (float) cloud.getContainerCap();
        }

        /**
         * Represents an entry in the CloudQueue, containing a DockerCloud and its usage value.
         */
        private static class CloudEntry {
            private final DockerCloud cloud;
            private final float usage;

            public CloudEntry(DockerCloud cloud, float usage) {
                this.cloud = cloud;
                this.usage = usage;
            }

            public DockerCloud getCloud() {
                return cloud;
            }

            public float getUsage() {
                return usage;
            }
        }

        /**
         * Push a new DockerCloud into the queue, maintaining the sorted order.
         * Entries with the same usage value are inserted at the end of their group.
         *
         * @param cloud The DockerCloud to push.
         */
        public void push(DockerCloud cloud) {
            float usage = getUsage(label, cloud);
            CloudEntry entry = new CloudEntry(cloud, usage);
            int index = 0;
            for (CloudEntry e : queue) {
                if (e.getUsage() > entry.getUsage()) {
                    break;
                }
                index++;
            }
            queue.add(index, entry);
            LOGGER.log(FINE, "Placed cloud {0} with usage {1} at index {2}", new Object[] {cloud.name, usage, index});
        }

        /**
         * Pop the first DockerCloud from the queue.
         *
         * @return The first DockerCloud, or null if the queue is empty.
         */
        public DockerCloud pop() {
            return queue.isEmpty() ? null : queue.removeFirst().getCloud();
        }
    }
}
