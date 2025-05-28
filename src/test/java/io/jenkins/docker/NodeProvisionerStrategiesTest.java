package io.jenkins.docker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.nirima.jenkins.plugins.docker.DockerCloud;
import com.nirima.jenkins.plugins.docker.DockerTemplate;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.NodeProvisioner;
import io.vavr.collection.HashMap;
import io.vavr.collection.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class NodeProvisionerStrategiesTest {
    // This is a simple sequence number generator to simulate job numbers,
    // which will be used to track the jobs assigned to each cloud
    // so that we can verify the distribution of jobs across clouds.
    private static int sequenceNumber = 0;

    private static synchronized int genSeqNum() {
        return ++sequenceNumber;
    }

    private static void resetSeqNum() {
        sequenceNumber = 0;
    }

    // This class simulates the state of a DockerCloud, including its capacity
    private class MockedCloudState {
        // This list will hold the job numbers assigned to this cloud
        private final List<Integer> jobNums = new ArrayList<>();
        // This variable represents the remaining capacity of the cloud
        private int capacity;
        // number of running container for a specific template
        private int runningContainers = 0;

        public MockedCloudState(int capacity) {
            this.capacity = capacity;
        }

        // This method simulates the provisioning of nodes in the cloud
        // It will allocate a number of nodes up to the cloud's capacity
        // and generate job numbers for each allocated node
        public List<NodeProvisioner.PlannedNode> provision(int nodes) {
            List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();
            int allocatableNodes = Math.min(nodes, capacity);
            for (int i = 0; i < allocatableNodes; i++) {
                jobNums.add(genSeqNum());
                plannedNodes.add(mock(NodeProvisioner.PlannedNode.class));
            }
            runningContainers += allocatableNodes;
            return plannedNodes;
        }

        public List<Integer> getJobNums() {
            return Collections.unmodifiableList(jobNums);
        }
    }

    // This maps mock DockerCloud instances to their corresponding MockedCloudState
    private static Map<DockerCloud, MockedCloudState> cloudStateMap = HashMap.empty();

    private DockerCloud createMockedCloud(int capacity) {
        DockerCloud cloud = mock(DockerCloud.class);
        cloudStateMap = cloudStateMap.put(cloud, new MockedCloudState(capacity));
        assertTrue(cloudStateMap.containsKey(cloud));
        when(cloud.provision(any(Label.class), anyInt())).thenAnswer(invocation -> {
            int nodes = invocation.getArgument(1);
            return cloudStateMap.get(cloud).get().provision(nodes);
        });
        when(cloud.getTemplates(any(Label.class))).thenReturn(List.of(mock(DockerTemplate.class)));
        when(cloud.getContainerCap()).thenReturn(capacity);
        when(cloud.countContainersInProgress(any())).thenAnswer(invocation -> {
            return cloudStateMap.get(cloud).get().runningContainers;
        });
        when(cloud.canProvision(any(Label.class))).thenReturn(true);

        return cloud;
    }

    private LoadStatistics.LoadStatisticsSnapshot createMockedLoadStatisticsSnapshot(int queueLength) {
        LoadStatistics.LoadStatisticsSnapshot snapshot = mock(LoadStatistics.LoadStatisticsSnapshot.class);
        when(snapshot.getQueueLength()).thenReturn(queueLength);
        when(snapshot.getAvailableExecutors()).thenReturn(0);
        when(snapshot.getConnectingExecutors()).thenReturn(0);
        return snapshot;
    }

    private MockedCloudState getMockedCloudState(DockerCloud cloud) {
        return cloudStateMap.get(cloud).get();
    }

    @Test
    public void testFastNodeProvisionerStrategy(JenkinsRule jenkins) throws Exception {
        resetSeqNum();
        // Mock the two DockerClouds
        DockerCloud cloud1 = createMockedCloud(2);
        DockerCloud cloud2 = createMockedCloud(2);

        // Mock the label
        Label label = mock(Label.class);

        // Add the clouds to Jenkins
        jenkins.getInstance().clouds.add(cloud1);
        jenkins.getInstance().clouds.add(cloud2);

        // Create the strategy
        FastNodeProvisionerStrategy strategy = new FastNodeProvisionerStrategy();

        // Mock the StrategyState
        NodeProvisioner.StrategyState state = mock(NodeProvisioner.StrategyState.class);
        when(state.getLabel()).thenReturn(label);

        // Mock the LoadStatisticsSnapshot
        LoadStatistics.LoadStatisticsSnapshot snapshot = createMockedLoadStatisticsSnapshot(4);
        when(state.getSnapshot()).thenReturn(snapshot);

        // Apply the strategy
        strategy.apply(state);

        // Validate the job numbers for each cloud
        MockedCloudState cloud1State = getMockedCloudState(cloud1);
        MockedCloudState cloud2State = getMockedCloudState(cloud2);

        // it is expected that clouds are filled in the order they are added
        // up to their capacity
        assertEquals(List.of(1, 2), cloud1State.getJobNums());
        assertEquals(List.of(3, 4), cloud2State.getJobNums());
    }

    @Test
    public void testWideNodeProvisionerStrategy(JenkinsRule jenkins) throws Exception {
        resetSeqNum();

        // Mock the two DockerClouds
        DockerCloud cloud1 = createMockedCloud(2);
        DockerCloud cloud2 = createMockedCloud(2);
        DockerCloud cloud3 = createMockedCloud(2);

        // Mock the label
        Label label = mock(Label.class);

        // Add the clouds to Jenkins
        jenkins.getInstance().clouds.add(cloud1);
        jenkins.getInstance().clouds.add(cloud2);
        jenkins.getInstance().clouds.add(cloud3);

        // Create the strategy
        WideNodeProvisionerStrategy strategy = new WideNodeProvisionerStrategy();

        // Mock the StrategyState
        NodeProvisioner.StrategyState state = mock(NodeProvisioner.StrategyState.class);
        when(state.getLabel()).thenReturn(label);

        // Mock the LoadStatisticsSnapshot
        LoadStatistics.LoadStatisticsSnapshot snapshot = createMockedLoadStatisticsSnapshot(4);
        when(state.getSnapshot()).thenReturn(snapshot);

        // Apply the strategy
        strategy.apply(state);

        // Validate the job numbers for each cloud
        MockedCloudState cloud1State = getMockedCloudState(cloud1);
        MockedCloudState cloud2State = getMockedCloudState(cloud2);
        MockedCloudState cloud3State = getMockedCloudState(cloud3);

        // it is expected that jobs are distributed across clouds evenly
        assertEquals(List.of(1, 4), cloud1State.getJobNums());
        assertEquals(List.of(2), cloud2State.getJobNums());
        assertEquals(List.of(3), cloud3State.getJobNums());
    }
}
