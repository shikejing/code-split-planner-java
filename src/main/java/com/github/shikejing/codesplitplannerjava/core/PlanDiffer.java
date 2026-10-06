package com.github.shikejing.codesplitplannerjava.core;

import com.github.shikejing.codesplitplannerjava.model.Bundle;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the difference between two committed plans. Bundle identity is the
 * deterministic bundle id; timing changes are reported per module.
 */
public final class PlanDiffer {

    private PlanDiffer() {
    }

    public static PlanDiff diff(LoadPlan from, LoadPlan to) {
        Map<String, Bundle> fromBundles = new TreeMap<>();
        for (Bundle b : from.bundles()) {
            fromBundles.put(b.id(), b);
        }
        Map<String, Bundle> toBundles = new TreeMap<>();
        for (Bundle b : to.bundles()) {
            toBundles.put(b.id(), b);
        }

        List<Bundle> added = new ArrayList<>();
        for (Map.Entry<String, Bundle> e : toBundles.entrySet()) {
            if (!fromBundles.containsKey(e.getKey())) {
                added.add(e.getValue());
            }
        }
        List<Bundle> removed = new ArrayList<>();
        for (Map.Entry<String, Bundle> e : fromBundles.entrySet()) {
            if (!toBundles.containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }

        Map<String, PlanDiff.TimingChange> timingChanges = new TreeMap<>();
        for (Map.Entry<String, LoadTiming> e : to.moduleTiming().entrySet()) {
            LoadTiming old = from.moduleTiming().get(e.getKey());
            if (old != e.getValue()) {
                timingChanges.put(e.getKey(), new PlanDiff.TimingChange(old, e.getValue()));
            }
        }
        for (Map.Entry<String, LoadTiming> e : from.moduleTiming().entrySet()) {
            if (!to.moduleTiming().containsKey(e.getKey())) {
                timingChanges.put(e.getKey(), new PlanDiff.TimingChange(e.getValue(), null));
            }
        }

        return new PlanDiff(from.version(), to.version(),
                List.copyOf(added), List.copyOf(removed), timingChanges);
    }
}
