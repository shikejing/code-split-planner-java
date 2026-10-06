package com.github.shikejing.codesplitplannerjava;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.core.PlanGenerator;
import com.github.shikejing.codesplitplannerjava.core.PlanRejectedException;
import com.github.shikejing.codesplitplannerjava.model.Bundle;
import com.github.shikejing.codesplitplannerjava.model.Demotion;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import com.github.shikejing.codesplitplannerjava.model.SkipReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanGeneratorTest {

    private final PlannerProperties properties = new PlannerProperties();
    private final PlanGenerator generator = new PlanGenerator(properties);

    private static GraphSnapshot graph(List<ModuleNode> modules, String... edges) {
        GraphSnapshot g = new GraphSnapshot();
        for (ModuleNode m : modules) {
            g = g.withModule(m);
        }
        for (String edge : edges) {
            String[] parts = edge.split("->");
            g = g.withImport(parts[0], parts[1]);
        }
        return g;
    }

    private static ModuleNode mod(String id, long size, boolean entry) {
        return new ModuleNode(id, size, entry);
    }

    @Test
    void sameGraphProducesIdenticalPlanRegardlessOfInputOrder() {
        List<ModuleNode> modules = List.of(
                mod("entryA", 10, true), mod("entryB", 10, true),
                mod("shared", 5, false), mod("onlyA", 3, false),
                mod("onlyB", 4, false), mod("leaf", 1, false));
        List<String> edges = List.of("entryA->shared", "entryB->shared",
                "entryA->onlyA", "entryB->onlyB", "shared->leaf", "onlyA->leaf");

        LoadPlan reference = generator.generate(graph(modules, edges.toArray(new String[0])), 1);

        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            List<ModuleNode> shuffledModules = new ArrayList<>(modules);
            List<String> shuffledEdges = new ArrayList<>(edges);
            Collections.shuffle(shuffledModules, random);
            Collections.shuffle(shuffledEdges, random);
            LoadPlan plan = generator.generate(
                    graph(shuffledModules, shuffledEdges.toArray(new String[0])), 1);
            assertTrue(reference.sameContent(plan),
                    "plan must not depend on input order, iteration " + i);
        }
    }

    @Test
    void sameGraphProducesIdenticalPlanUnderConcurrency() throws Exception {
        List<ModuleNode> modules = List.of(
                mod("entryA", 10, true), mod("entryB", 10, true),
                mod("shared", 5, false), mod("onlyA", 3, false),
                mod("onlyB", 4, false), mod("leaf", 1, false));
        List<String> edges = List.of("entryA->shared", "entryB->shared",
                "entryA->onlyA", "entryB->onlyB", "shared->leaf", "onlyA->leaf");
        LoadPlan reference = generator.generate(graph(modules, edges.toArray(new String[0])), 1);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<LoadPlan>> futures = new ArrayList<>();
            Random random = new Random(7);
            for (int i = 0; i < 32; i++) {
                List<ModuleNode> sm = new ArrayList<>(modules);
                List<String> se = new ArrayList<>(edges);
                Collections.shuffle(sm, random);
                Collections.shuffle(se, random);
                futures.add(pool.submit(() -> generator.generate(
                        graph(sm, se.toArray(new String[0])), 1)));
            }
            for (var f : futures) {
                assertTrue(reference.sameContent(f.get()),
                        "plan must not depend on concurrency");
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void sharedDependenciesAreGroupedAndPackedOnce() {
        GraphSnapshot g = graph(List.of(
                mod("entryA", 10, true), mod("entryB", 10, true),
                mod("shared", 5, false), mod("onlyA", 3, false)),
                "entryA->shared", "entryB->shared", "entryA->onlyA");

        LoadPlan plan = generator.generate(g, 1);

        // shared packed exactly once, in a bundle marked shared
        long sharedBundles = plan.bundles().stream()
                .filter(b -> b.members().contains("shared")).count();
        assertEquals(1, sharedBundles);
        Bundle shared = plan.bundles().stream()
                .filter(b -> b.members().contains("shared")).findFirst().orElseThrow();
        assertTrue(shared.shared());
        // no module appears in two bundles
        long totalMembers = plan.bundles().stream().mapToLong(b -> b.members().size()).sum();
        assertEquals(plan.moduleToBundle().size(), totalMembers);
        // bundle id is derived from members only
        assertEquals(Bundle.idOf(List.of("shared")), shared.id());
    }

    @Test
    void cyclesAreCollapsedToLazyBundleAndUnreachableModulesSkipped() {
        GraphSnapshot g = graph(List.of(
                mod("entry", 10, true), mod("a", 1, false), mod("b", 1, false),
                mod("orphan", 1, false)),
                "entry->a", "a->b", "b->a");

        LoadPlan plan = generator.generate(g, 1);

        // cycle a<->b collapsed into one lazy bundle, plan still generated
        Bundle cycleBundle = plan.bundles().stream()
                .filter(b -> b.members().equals(List.of("a", "b"))).findFirst().orElseThrow();
        assertEquals(LoadTiming.LAZY, cycleBundle.timing());
        assertEquals(LoadTiming.LAZY, plan.moduleTiming().get("a"));
        // cycle reported with locatable members
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("CYCLE_DETECTED")
                && d.moduleIds().equals(List.of("a", "b"))));
        // unreachable module skipped with distinguishable reason, not eager
        assertEquals(LoadTiming.SKIPPED, plan.moduleTiming().get("orphan"));
        assertEquals(SkipReason.UNREACHABLE, plan.skipReasons().get("orphan"));
        // entry stays eager
        assertEquals(LoadTiming.EAGER, plan.moduleTiming().get("entry"));
    }

    @Test
    void cycleStrategyRejectFailsWithLocatableDiagnostics() {
        properties.setCycleStrategy(PlannerProperties.CycleStrategy.REJECT);
        GraphSnapshot g = graph(List.of(mod("entry", 10, true), mod("a", 1, false),
                mod("b", 1, false)), "entry->a", "a->b", "b->a");

        PlanRejectedException ex = assertThrows(PlanRejectedException.class,
                () -> generator.generate(g, 1));
        assertEquals("CYCLE_DETECTED", ex.diagnostics().get(0).code());
        assertEquals(List.of("a", "b"), ex.diagnostics().get(0).moduleIds());
    }

    @Test
    void missingImportTargetsAreReportedAndIgnored() {
        GraphSnapshot g = graph(List.of(mod("entry", 10, true)), "entry->ghost");
        LoadPlan plan = generator.generate(g, 1);
        assertTrue(plan.diagnostics().stream().anyMatch(
                d -> d.code().equals("MISSING_IMPORT_TARGET") && d.moduleIds().contains("ghost")));
        assertEquals(LoadTiming.EAGER, plan.moduleTiming().get("entry"));
    }

    @Test
    void moduleCountBudgetDemotesFarthestBundlesWithExplanation() {
        properties.setMaxFirstScreenModules(2);
        GraphSnapshot g = graph(List.of(
                mod("entry", 10, true), mod("mid", 1, false), mod("deep", 1, false),
                mod("leaf", 1, false)),
                "entry->mid", "mid->deep", "deep->leaf");

        LoadPlan plan = generator.generate(g, 1);

        assertEquals(LoadTiming.EAGER, plan.moduleTiming().get("entry"));
        assertEquals(LoadTiming.EAGER, plan.moduleTiming().get("mid"));
        // farthest modules demoted first
        assertEquals(LoadTiming.LAZY, plan.moduleTiming().get("leaf"));
        // direct dependency of an eager bundle -> prefetch instead of lazy
        assertEquals(LoadTiming.PREFETCH, plan.moduleTiming().get("deep"));
        assertEquals(2, plan.demotions().size());
        Demotion leafDemotion = plan.demotions().stream()
                .filter(d -> d.moduleIds().contains("leaf")).findFirst().orElseThrow();
        assertEquals(Demotion.Trigger.MODULE_COUNT, leafDemotion.trigger());
        assertEquals(LoadTiming.LAZY, leafDemotion.resultingTiming());
    }

    @Test
    void byteBudgetDemotesUntilWithinLimit() {
        properties.setMaxFirstScreenBytes(15);
        GraphSnapshot g = graph(List.of(
                mod("entry", 10, true), mod("big", 8, false), mod("small", 4, false)),
                "entry->big", "entry->small");

        LoadPlan plan = generator.generate(g, 1);

        long eagerBytes = plan.bundles().stream()
                .filter(b -> b.timing() == LoadTiming.EAGER)
                .flatMap(b -> b.members().stream())
                .mapToLong(m -> g.modules().get(m).sizeBytes()).sum();
        assertTrue(eagerBytes <= 15, "eager bytes must respect the budget");
        assertFalse(plan.demotions().isEmpty());
        // both demoted modules are direct deps of the eager entry -> prefetch
        assertEquals(LoadTiming.PREFETCH, plan.moduleTiming().get("big"));
    }

    @Test
    void entriesAloneOverBudgetAreReportedNotSilentlyExceeded() {
        properties.setMaxFirstScreenModules(1);
        GraphSnapshot g = graph(List.of(
                mod("entryA", 10, true), mod("entryB", 10, true)));

        LoadPlan plan = generator.generate(g, 1);

        assertEquals(LoadTiming.EAGER, plan.moduleTiming().get("entryA"));
        assertEquals(LoadTiming.EAGER, plan.moduleTiming().get("entryB"));
        assertTrue(plan.diagnostics().stream()
                .anyMatch(d -> d.code().equals("BUDGET_EXCEEDED_BY_ENTRIES")));
    }
}
