package com.nirima.jenkins.plugins.docker;

import hudson.Extension;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundSetter;

@Extension
public final class DockerCloudGlobalConfiguration extends GlobalConfiguration {

    private String strategyName = "fast"; // default

    public DockerCloudGlobalConfiguration() {
        load();
    }

    public String getStrategyName() {
        return strategyName;
    }

    @DataBoundSetter
    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
        save();
    }

    public static DockerCloudGlobalConfiguration get() {
        DockerCloudGlobalConfiguration config = GlobalConfiguration.all().get(DockerCloudGlobalConfiguration.class);
        if (config == null) {
            throw new IllegalStateException("DockerCloudGlobalConfiguration not found");
        }
        return config;
    }

    public ListBoxModel doFillStrategyNameItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("Fast (default Jenkins behavior)", "fast");
        items.add("Wide (spread jobs across clouds)", "wide");
        return items;
    }
}
