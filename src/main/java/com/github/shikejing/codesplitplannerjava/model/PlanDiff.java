package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;
import java.util.Map;

/**
 * Difference between two committed plans.
 *
 * @param fromVersion    older plan version
 * @param toVersion      newer plan version
 * @param addedBundles   bundles present only in the newer plan
 * @param removedBundles bundles present only in the older plan
 * @param timingChanges  module id -> (old timing, new timing) for modules whose
 *                       timing changed
 */
public record PlanDiff(long fromVersion, long toVersion,
                       List<Bundle> addedBundles, List<Bundle> removedBundles,
                       Map<String, TimingChange> timingChanges) {

    public record TimingChange(LoadTiming from, LoadTiming to) {
    }
}
