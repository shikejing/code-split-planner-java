package com.github.shikejing.codesplitplannerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分包与加载策略的全部配置项，前缀 planner。
 */
@ConfigurationProperties(prefix = "planner")
public class PlannerProperties {

    /** 首屏预算：最大模块数；<=0 表示不限制。 */
    private int budgetMaxModules = 0;

    /** 首屏预算：最大总体积（字节）；<=0 表示不限制。 */
    private long budgetMaxBytes = 0;

    /** 是否把共享依赖包标记为预取（否则随首屏）。 */
    private boolean sharedChunkPrefetch = false;

    /** 计划持久化目录，重启后从此恢复已提交计划。 */
    private String dataDir = "data/plans";

    public int getBudgetMaxModules() {
        return budgetMaxModules;
    }

    public void setBudgetMaxModules(int budgetMaxModules) {
        this.budgetMaxModules = budgetMaxModules;
    }

    public long getBudgetMaxBytes() {
        return budgetMaxBytes;
    }

    public void setBudgetMaxBytes(long budgetMaxBytes) {
        this.budgetMaxBytes = budgetMaxBytes;
    }

    public boolean isSharedChunkPrefetch() {
        return sharedChunkPrefetch;
    }

    public void setSharedChunkPrefetch(boolean sharedChunkPrefetch) {
        this.sharedChunkPrefetch = sharedChunkPrefetch;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
