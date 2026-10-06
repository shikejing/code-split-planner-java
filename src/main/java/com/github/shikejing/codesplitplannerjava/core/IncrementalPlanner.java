package com.github.shikejing.codesplitplannerjava.core;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.model.Bundle;
import com.github.shikejing.codesplitplannerjava.model.Demotion;
import com.github.shikejing.codesplitplannerjava.model.Diagnostic;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import com.github.shikejing.codesplitplannerjava.model.SkipReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Recomputes a plan after a graph change.
 *
 * <p>The affected range is computed exactly: a module's grouping depends only
 * on its entry set (the entries that can reach it) and on cycle membership,
 * and both can only change for modules reachable from an entry whose
 * reachable closure intersects the changed region. Modules outside the
 * affected range therefore keep their previous bundles untouched; only
 * affected modules are regrouped. Budget enforcement is a deterministic
 * function of the merged bundle set and is re-run globally, so the merged
 * result is identical to a full recompute of the new graph.
 */
public class IncrementalPlanner {

    private final PlannerProperties properties;

    public IncrementalPlanner(PlannerProperties properties) {
        this.properties = properties;
    }

    /**
     * @param oldGraph   graph before the change
     * @param newGraph   graph after the change
     * @param oldPlan    plan committed for {@code oldGraph}
     * @param newVersion version for the new plan
     */
    public IncrementalResult recompute(GraphSnapshot oldGraph, GraphSnapshot newGraph,
                                       LoadPlan oldPlan, long newVersion) {
        // 1. What changed: added/removed/modified modules and added/removed edges.
        Set<String> changed = changedModules(oldGraph, newGraph);

        // 2. Affected entries: entries that can reach any changed module, in
        //    either graph version (a removed edge only exists in the old one).
        Set<String> dependents = new TreeSet<>();
        dependents.addAll(GraphAlgorithms.dependentsOf(oldGraph, changed));
        dependents.addAll(GraphAlgorithms.dependentsOf(newGraph, changed));
        Set<String> affectedEntries = new TreeSet<>();
        for (String entry : union(oldGraph.entries(), newGraph.entries())) {
            if (dependents.contains(entry)) {
                affectedEntries.add(entry);
            }
        }

        // 3. Affected modules: everything reachable from an affected entry in
        //    either graph, plus the changed modules themselves.
        Set<String> affectedModules = new TreeSet<>(changed);
        affectedModules.addAll(GraphAlgorithms.reachableFrom(oldGraph, affectedEntries));
        affectedModules.addAll(GraphAlgorithms.reachableFrom(newGraph, affectedEntries));

        // 4. Global linear passes on the new graph (reachability, cycles,
        //    entry sets, distances). These are cheap and shared with the full
        //    generator so results cannot diverge.
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Demotion> demotions = new ArrayList<>();
        PlanGenerator.detectMissingTargets(newGraph, diagnostics);

        Set<String> reachable = GraphAlgorithms.reachableFromEntries(newGraph);
        Map<String, LoadTiming> moduleTiming = new TreeMap<>();
        Map<String, SkipReason> skipReasons = new TreeMap<>();
        PlanGenerator.markUnreachable(newGraph, reachable, moduleTiming, skipReasons);

        List<List<String>> cycles = PlanGenerator.findCycles(newGraph, reachable);
        PlanGenerator.handleCycles(properties, cycles, diagnostics);
        Set<String> cycleMembers = new TreeSet<>();
        for (List<String> cycle : cycles) {
            cycleMembers.addAll(cycle);
        }

        Map<String, Set<String>> entrySets = GraphAlgorithms.entrySets(newGraph);
        Map<String, Integer> distance = GraphAlgorithms.distancesFromEntries(newGraph);

        // 5. Split old group bundles (non-cycle bundles) into kept and
        //    to-be-regrouped. Cycle bundles are rebuilt from the fresh cycle
        //    list, so only group bundles are candidates for reuse.
        Map<String, Bundle> oldGroupsById = new TreeMap<>();
        Set<String> oldCycleMembers = new TreeSet<>();
        for (Diagnostic d : oldPlan.diagnostics()) {
            if (d.code().equals("CYCLE_DETECTED")) {
                oldCycleMembers.addAll(d.moduleIds());
            }
        }
        for (Bundle b : oldPlan.bundles()) {
            boolean isCycleBundle = !oldCycleMembers.isEmpty()
                    && oldCycleMembers.containsAll(b.members());
            if (!isCycleBundle) {
                oldGroupsById.put(b.id(), b);
            }
        }

        List<Bundle> merged = new ArrayList<>();
        Set<String> regroup = new TreeSet<>();
        for (String module : reachable) {
            if (cycleMembers.contains(module)) {
                continue;
            }
            if (affectedModules.contains(module)) {
                regroup.add(module);
            }
        }
        // Kept bundles: old group bundles whose members are all unaffected,
        // still present and still reachable. Their entry sets are provably
        // unchanged, so the grouping is still valid.
        for (Bundle b : oldGroupsById.values()) {
            boolean reusable = true;
            for (String member : b.members()) {
                if (affectedModules.contains(member) || !reachable.contains(member)
                        || cycleMembers.contains(member)) {
                    reusable = false;
                    break;
                }
            }
            if (reusable) {
                merged.add(Bundle.of(b.members(), LoadTiming.EAGER, b.shared()));
            } else {
                // Members that are unaffected but whose old bundle dissolved
                // must be regrouped to keep the result equal to a full run.
                for (String member : b.members()) {
                    if (!affectedModules.contains(member) && reachable.contains(member)
                            && !cycleMembers.contains(member)) {
                        regroup.add(member);
                        affectedModules.add(member);
                    }
                }
            }
        }
        merged.addAll(PlanGenerator.groupByEntrySet(entrySets, distance, regroup));

        // 6. Budget enforcement over the merged candidate set (global,
        //    deterministic) and final assembly.
        Map<String, LoadTiming> finalTiming = PlanGenerator.enforceBudget(properties, newGraph,
                merged, distance, demotions, diagnostics);

        List<Bundle> bundles = new ArrayList<>();
        Map<String, String> moduleToBundle = new TreeMap<>();
        for (List<String> cycle : cycles) {
            Bundle bundle = Bundle.of(cycle, LoadTiming.LAZY, false);
            bundles.add(bundle);
            for (String member : cycle) {
                moduleToBundle.put(member, bundle.id());
                moduleTiming.put(member, LoadTiming.LAZY);
            }
        }
        for (Bundle candidate : merged) {
            LoadTiming timing = finalTiming.getOrDefault(candidate.id(), LoadTiming.EAGER);
            Bundle bundle = Bundle.of(candidate.members(), timing, candidate.shared());
            bundles.add(bundle);
            for (String member : bundle.members()) {
                moduleToBundle.put(member, bundle.id());
                moduleTiming.put(member, timing);
            }
        }
        bundles.sort((a, b) -> a.id().compareTo(b.id()));

        LoadPlan plan = new LoadPlan(newVersion, bundles, moduleToBundle, moduleTiming,
                skipReasons, List.copyOf(diagnostics), List.copyOf(demotions));
        return new IncrementalResult(plan, Set.copyOf(affectedEntries),
                Set.copyOf(affectedModules));
    }

    /** Modules added, removed, or with changed attributes, plus edge endpoints. */
    private Set<String> changedModules(GraphSnapshot oldGraph, GraphSnapshot newGraph) {
        Set<String> changed = new TreeSet<>();
        for (String id : union(oldGraph.modules().keySet(), newGraph.modules().keySet())) {
            ModuleNode oldNode = oldGraph.modules().get(id);
            ModuleNode newNode = newGraph.modules().get(id);
            if (oldNode == null || newNode == null || !oldNode.equals(newNode)) {
                changed.add(id);
            }
        }
        for (String from : union(oldGraph.imports().keySet(), newGraph.imports().keySet())) {
            Set<String> oldTargets = oldGraph.imports().getOrDefault(from, Set.of());
            Set<String> newTargets = newGraph.imports().getOrDefault(from, Set.of());
            if (!oldTargets.equals(newTargets)) {
                changed.add(from);
                for (String to : union(oldTargets, newTargets)) {
                    changed.add(to);
                }
            }
        }
        return changed;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.addAll(b);
        return result;
    }

    /**
     * @param affectedEntries entries whose reachable closure may have changed
     * @param affectedModules modules whose grouping was recomputed
     */
    public record IncrementalResult(LoadPlan plan, Set<String> affectedEntries,
                                    Set<String> affectedModules) {
    }
}
