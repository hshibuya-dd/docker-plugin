package io.jenkins.docker;

import com.nirima.jenkins.plugins.docker.DockerCloudGlobalConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.Strategy;
import hudson.slaves.NodeProvisioner.StrategyDecision;

@Extension
public class NodeProvisionerStrategy extends Strategy {

    private final FastNodeProvisionerStrategy fastStrategy = new FastNodeProvisionerStrategy();
    private final WideNodeProvisionerStrategy wideStrategy = new WideNodeProvisionerStrategy();

    @NonNull
    @Override
    public StrategyDecision apply(@NonNull NodeProvisioner.StrategyState state) {
        String strategyName = DockerCloudGlobalConfiguration.get().getStrategyName();
        if ("wide".equalsIgnoreCase(strategyName)) {
            return wideStrategy.apply(state);
        } else {
            return fastStrategy.apply(state);
        }
    }
}
