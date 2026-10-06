package com.github.shikejing.codesplitplannerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the code-split planner. All properties use the
 * {@code planner} prefix.
 */
@ConfigurationProperties(prefix = "planner")
public class PlannerProperties {

    /** How dependency cycles are handled. */
    public enum CycleStrategy {
        /** Collapse each cycle into a single LAZY bundle and keep going. */
        DEGRADE,
        /** Reject the whole plan generation with a locatable diagnostic. */
        REJECT
    }

    /** Maximum number of modules in the first screen; -1 means unlimited. */
    private int maxFirstScreenModules = -1;

    /** Maximum total bytes in the first screen; -1 means unlimited. */
    private long maxFirstScreenBytes = -1;

    /** Cycle handling strategy. */
    private CycleStrategy cycleStrategy = CycleStrategy.DEGRADE;

    /** Directory where committed plans and graph snapshots are persisted. */
    private String storeDir = "data/plans";

    public int getMaxFirstScreenModules() {
        return maxFirstScreenModules;
    }

    public void setMaxFirstScreenModules(int maxFirstScreenModules) {
        this.maxFirstScreenModules = maxFirstScreenModules;
    }

    public long getMaxFirstScreenBytes() {
        return maxFirstScreenBytes;
    }

    public void setMaxFirstScreenBytes(long maxFirstScreenBytes) {
        this.maxFirstScreenBytes = maxFirstScreenBytes;
    }

    public CycleStrategy getCycleStrategy() {
        return cycleStrategy;
    }

    public void setCycleStrategy(CycleStrategy cycleStrategy) {
        this.cycleStrategy = cycleStrategy;
    }

    public String getStoreDir() {
        return storeDir;
    }

    public void setStoreDir(String storeDir) {
        this.storeDir = storeDir;
    }
}
