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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministically turns a graph snapshot into a full load plan.
 *
 * <p>Pipeline (all steps iterate in lexicographic order):
 * <ol>
 *   <li>Validate import targets; dangling references are reported with
 *       {@code MISSING_IMPORT_TARGET} and ignored.</li>
 *   <li>Compute the reachable set from all entries; everything else is
 *       {@link LoadTiming#SKIPPED} with {@link SkipReason#UNREACHABLE}.</li>
 *   <li>Find strongly connected components. Cycles are either rejected
 *       (strategy REJECT) with locatable diagnostics, or collapsed into a
 *       single LAZY bundle per cycle (strategy DEGRADE) and excluded from the
 *       first screen.</li>
 *   <li>Group the remaining reachable modules by their exact entry set (the
 *       set of entries that can reach them). Modules with an identical entry
 *       set share one bundle, so a module is packed exactly once and shared
 *       dependencies are never duplicated.</li>
 *   <li>Enforce the first-screen budget: demote whole bundles, farthest from
 *       the entries first, until both constraints hold. Demoted bundles become
 *       PREFETCH when they are a direct dependency of an eager bundle,
 *       otherwise LAZY. Every demotion is recorded with its trigger.</li>
 * </ol>
 *
 * <p>The individual steps are exposed as package-private static methods so
 * that {@link IncrementalPlanner} can reuse the exact same logic on the
 * affected range and merge it with untouched bundles.
 */
public class PlanGenerator {

    private final PlannerProperties properties;

    public PlanGenerator(PlannerProperties properties) {
        this.properties = properties;
    }

    /**
     * Generates a complete plan from scratch. The result is a pure function of
     * the snapshot, the version and the configuration.
     */
    public LoadPlan generate(GraphSnapshot graph, long version) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Demotion> demotions = new ArrayList<>();

        detectMissingTargets(graph, diagnostics);

        Set<String> reachable = GraphAlgorithms.reachableFromEntries(graph);
        Map<String, LoadTiming> moduleTiming = new TreeMap<>();
        Map<String, SkipReason> skipReasons = new TreeMap<>();
        markUnreachable(graph, reachable, moduleTiming, skipReasons);

        List<List<String>> cycles = findCycles(graph, reachable);
        handleCycles(properties, cycles, diagnostics);

        Set<String> cycleMembers = new TreeSet<>();
        List<Bundle> bundles = new ArrayList<>();
        Map<String, String> moduleToBundle = new TreeMap<>();
        for (List<String> cycle : cycles) {
            cycleMembers.addAll(cycle);
            Bundle bundle = Bundle.of(cycle, LoadTiming.LAZY, false);
            bundles.add(bundle);
            for (String member : cycle) {
                moduleToBundle.put(member, bundle.id());
                moduleTiming.put(member, LoadTiming.LAZY);
            }
        }

        Map<String, Set<String>> entrySets = GraphAlgorithms.entrySets(graph);
        Map<String, Integer> distance = GraphAlgorithms.distancesFromEntries(graph);
        Set<String> groupable = new TreeSet<>(reachable);
        groupable.removeAll(cycleMembers);
        List<Bundle> groupBundles = groupByEntrySet(entrySets, distance, groupable);

        Map<String, LoadTiming> finalTiming = enforceBudget(properties, graph, groupBundles,
                distance, demotions, diagnostics);

        for (Bundle candidate : groupBundles) {
            LoadTiming timing = finalTiming.getOrDefault(candidate.id(), LoadTiming.EAGER);
            Bundle bundle = Bundle.of(candidate.members(), timing, candidate.shared());
            bundles.add(bundle);
            for (String member : bundle.members()) {
                moduleToBundle.put(member, bundle.id());
                moduleTiming.put(member, timing);
            }
        }

        List<Bundle> sortedBundles = new ArrayList<>(bundles);
        sortedBundles.sort((a, b) -> a.id().compareTo(b.id()));
        return new LoadPlan(version, sortedBundles, moduleToBundle, moduleTiming,
                skipReasons, List.copyOf(diagnostics), List.copyOf(demotions));
    }

    /** Step 1: report dangling import targets (they are ignored afterwards). */
    static void detectMissingTargets(GraphSnapshot graph, List<Diagnostic> diagnostics) {
        Set<String> missingTargets = new TreeSet<>();
        for (Map.Entry<String, Set<String>> edge : graph.imports().entrySet()) {
            if (!graph.modules().containsKey(edge.getKey())) {
                continue; // edge from unknown module, nothing to pack anyway
            }
            for (String to : edge.getValue()) {
                if (!graph.modules().containsKey(to)) {
                    missingTargets.add(to);
                }
            }
        }
        if (!missingTargets.isEmpty()) {
            diagnostics.add(new Diagnostic("MISSING_IMPORT_TARGET",
                    "Import targets not present in the module set; the edges are ignored.",
                    List.copyOf(missingTargets)));
        }
    }

    /** Step 2: mark modules that no entry can reach. */
    static void markUnreachable(GraphSnapshot graph, Set<String> reachable,
                                Map<String, LoadTiming> moduleTiming,
                                Map<String, SkipReason> skipReasons) {
        for (String id : graph.modules().keySet()) {
            if (!reachable.contains(id)) {
                moduleTiming.put(id, LoadTiming.SKIPPED);
                skipReasons.put(id, SkipReason.UNREACHABLE);
            }
        }
    }

    /** Step 3a: find cyclic strongly connected components, deterministically. */
    static List<List<String>> findCycles(GraphSnapshot graph, Set<String> reachable) {
        List<List<String>> sccs = GraphAlgorithms.stronglyConnectedComponents(graph, reachable);
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> scc : sccs) {
            boolean selfLoop = scc.size() == 1
                    && graph.imports().getOrDefault(scc.get(0), Set.of()).contains(scc.get(0));
            if (scc.size() > 1 || selfLoop) {
                cycles.add(scc);
            }
        }
        return cycles;
    }

    /** Step 3b: apply the configured cycle strategy. */
    static void handleCycles(PlannerProperties properties, List<List<String>> cycles,
                             List<Diagnostic> diagnostics) {
        if (cycles.isEmpty()) {
            return;
        }
        List<String> allCycleMembers = new ArrayList<>();
        for (List<String> cycle : cycles) {
            allCycleMembers.addAll(cycle);
        }
        if (properties.getCycleStrategy() == PlannerProperties.CycleStrategy.REJECT) {
            throw new PlanRejectedException(
                    "Dependency cycle(s) detected and cycle strategy is REJECT",
                    List.of(new Diagnostic("CYCLE_DETECTED",
                            "Cyclic strongly connected components; members listed in order.",
                            List.copyOf(allCycleMembers))));
        }
        diagnostics.add(new Diagnostic("CYCLE_DETECTED",
                "Cyclic strongly connected components collapsed into lazy bundles "
                        + "and excluded from the first screen.",
                List.copyOf(allCycleMembers)));
    }

    /**
     * Step 4: group modules by their exact entry set <b>and</b> their BFS
     * distance from the nearest entry. Modules with an identical entry set
     * and identical distance share one bundle, so shared dependencies are
     * packed exactly once while bundles stay layered by depth, which is what
     * makes distance-ordered budget demotion meaningful. The returned bundles
     * are all tentatively EAGER.
     */
    static List<Bundle> groupByEntrySet(Map<String, Set<String>> entrySets,
                                        Map<String, Integer> distance,
                                        Set<String> modules) {
        Map<String, List<String>> groups = new TreeMap<>();
        for (String module : new TreeSet<>(modules)) {
            Set<String> entrySet = entrySets.getOrDefault(module, Set.of());
            String key = distance.getOrDefault(module, 0) + "|"
                    + String.join("\n", new TreeSet<>(entrySet));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(module);
        }
        List<Bundle> bundles = new ArrayList<>();
        for (List<String> members : groups.values()) {
            members.sort(String::compareTo);
            Set<String> entrySet = entrySets.get(members.get(0));
            bundles.add(Bundle.of(members, LoadTiming.EAGER, entrySet.size() > 1));
        }
        return bundles;
    }

    /**
     * Step 5: decide the final timing of every group bundle under the
     * configured budget, returning bundle id -&gt; timing for the demoted
     * ones and appending one {@link Demotion} per demoted bundle.
     *
     * <p>Demotion order is deterministic: bundles are demoted whole, in order
     * of decreasing max distance from the entries, ties broken by bundle id.
     * Bundles containing entry modules are never demoted; if entries alone
     * exceed the budget a {@code BUDGET_EXCEEDED_BY_ENTRIES} diagnostic is
     * emitted instead of silently exceeding the budget.
     */
    static Map<String, LoadTiming> enforceBudget(PlannerProperties properties, GraphSnapshot graph,
                                                 List<Bundle> groupBundles,
                                                 Map<String, Integer> distance,
                                                 List<Demotion> demotions,
                                                 List<Diagnostic> diagnostics) {
        int maxModules = properties.getMaxFirstScreenModules();
        long maxBytes = properties.getMaxFirstScreenBytes();

        List<Bundle> eager = new ArrayList<>(groupBundles);
        List<Bundle> demoted = new ArrayList<>();

        List<Bundle> order = new ArrayList<>(groupBundles);
        order.sort((a, b) -> {
            int cmp = Integer.compare(maxDistance(b, distance), maxDistance(a, distance));
            return cmp != 0 ? cmp : a.id().compareTo(b.id());
        });

        for (Bundle candidate : order) {
            if (withinBudget(graph, eager, maxModules, maxBytes)) {
                break;
            }
            if (containsEntryModule(graph, candidate)) {
                continue; // entries always stay on the first screen
            }
            eager.remove(candidate);
            demoted.add(candidate);
            Demotion.Trigger trigger = !withinModuleCount(eager, maxModules)
                    ? Demotion.Trigger.MODULE_COUNT : Demotion.Trigger.BYTES;
            demotions.add(new Demotion(candidate.id(), candidate.members(), trigger,
                    LoadTiming.LAZY)); // timing refined below
        }

        // A demoted bundle becomes PREFETCH when it is a direct dependency of
        // an eager bundle, otherwise LAZY.
        Set<String> eagerModules = new TreeSet<>();
        for (Bundle b : eager) {
            eagerModules.addAll(b.members());
        }
        Map<String, LoadTiming> finalTiming = new HashMap<>();
        List<Demotion> refined = new ArrayList<>();
        for (int i = 0; i < demoted.size(); i++) {
            Bundle b = demoted.get(i);
            boolean directOfEager = false;
            for (String member : b.members()) {
                for (String eagerModule : eagerModules) {
                    if (graph.imports().getOrDefault(eagerModule, Set.of()).contains(member)) {
                        directOfEager = true;
                        break;
                    }
                }
                if (directOfEager) {
                    break;
                }
            }
            LoadTiming timing = directOfEager ? LoadTiming.PREFETCH : LoadTiming.LAZY;
            finalTiming.put(b.id(), timing);
            Demotion d = demotions.get(i);
            refined.add(new Demotion(d.bundleId(), d.moduleIds(), d.trigger(), timing));
        }
        demotions.clear();
        demotions.addAll(refined);

        if (!withinBudget(graph, eager, maxModules, maxBytes)) {
            List<String> entryModules = new ArrayList<>();
            for (Bundle b : eager) {
                for (String m : b.members()) {
                    ModuleNode node = graph.modules().get(m);
                    if (node != null && node.entry()) {
                        entryModules.add(m);
                    }
                }
            }
            diagnostics.add(new Diagnostic("BUDGET_EXCEEDED_BY_ENTRIES",
                    "Entry modules alone exceed the first-screen budget; entries are "
                            + "kept eager and the overflow is reported instead of hidden.",
                    List.copyOf(entryModules)));
        }
        return finalTiming;
    }

    private static boolean containsEntryModule(GraphSnapshot graph, Bundle bundle) {
        for (String m : bundle.members()) {
            ModuleNode node = graph.modules().get(m);
            if (node != null && node.entry()) {
                return true;
            }
        }
        return false;
    }

    private static int maxDistance(Bundle bundle, Map<String, Integer> distance) {
        int max = 0;
        for (String m : bundle.members()) {
            max = Math.max(max, distance.getOrDefault(m, 0));
        }
        return max;
    }

    private static boolean withinModuleCount(List<Bundle> eager, int maxModules) {
        if (maxModules < 0) {
            return true;
        }
        int count = 0;
        for (Bundle b : eager) {
            count += b.members().size();
        }
        return count <= maxModules;
    }

    private static boolean withinBudget(GraphSnapshot graph, List<Bundle> eager,
                                        int maxModules, long maxBytes) {
        if (!withinModuleCount(eager, maxModules)) {
            return false;
        }
        if (maxBytes >= 0) {
            long bytes = 0;
            for (Bundle b : eager) {
                for (String m : b.members()) {
                    ModuleNode node = graph.modules().get(m);
                    if (node != null) {
                        bytes += node.sizeBytes();
                    }
                }
            }
            return bytes <= maxBytes;
        }
        return true;
    }
}
