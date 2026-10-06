package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;

/**
 * Records one budget-driven downgrade of a bundle from EAGER to a later
 * timing, so every demotion can be explained to concrete modules and the
 * exact constraint that triggered it.
 *
 * @param bundleId  the demoted bundle
 * @param moduleIds members of the demoted bundle, sorted
 * @param trigger   which budget constraint triggered the demotion
 * @param resultingTiming timing after demotion (PREFETCH or LAZY)
 */
public record Demotion(String bundleId, List<String> moduleIds, Trigger trigger,
                       LoadTiming resultingTiming) {

    public enum Trigger {
        /** First-screen module count exceeded the configured maximum. */
        MODULE_COUNT,
        /** First-screen total bytes exceeded the configured maximum. */
        BYTES
    }
}
