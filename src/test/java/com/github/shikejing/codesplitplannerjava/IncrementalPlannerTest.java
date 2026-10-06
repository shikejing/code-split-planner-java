package com.github.shikejing.codesplitplannerjava;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.core.IncrementalPlanner;
import com.github.shikejing.codesplitplannerjava.core.PlanGenerator;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalPlannerTest {

    private final PlannerProperties properties = new PlannerProperties();
    private final PlanGenerator generator = new PlanGenerator(properties);
    private final IncrementalPlanner incremental = new IncrementalPlanner(properties);

    private static ModuleNode mod(String id, long size, boolean entry) {
        return new ModuleNode(id, size, entry);
    }

    private static GraphSnapshot baseGraph() {
        GraphSnapshot g = new GraphSnapshot();
        for (ModuleNode m : List.of(
                mod("entryA", 10, true), mod("entryB", 10, true),
                mod("shared", 5, false), mod("onlyA", 3, false), mod("onlyB", 4, false),
                mod("leaf", 1, false), mod("orphan", 1, false))) {
            g = g.withModule(m);
        }
        return g.withImport("entryA", "shared").withImport("entryB", "shared")
                .withImport("entryA", "onlyA").withImport("entryB", "onlyB")
                .withImport("shared", "leaf");
    }

    private void assertIncrementalMatchesFull(GraphSnapshot oldGraph, GraphSnapshot newGraph) {
        LoadPlan oldPlan = generator.generate(oldGraph, 1);
        IncrementalPlanner.IncrementalResult result =
                incremental.recompute(oldGraph, newGraph, oldPlan, 2);
        LoadPlan full = generator.generate(newGraph, 2);
        assertTrue(result.plan().sameContent(full),
                "incremental result must equal full recompute");
        assertFalse(result.affectedModules().isEmpty(), "affected range must be reported");
    }

    @Test
    void addingEdgeMatchesFullRecompute() {
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withImport("entryB", "leaf");
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void removingEdgeMatchesFullRecompute() {
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withoutImport("entryA", "shared");
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void addingModuleMatchesFullRecompute() {
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withModule(mod("extra", 2, false))
                .withImport("shared", "extra");
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void removingModuleMatchesFullRecompute() {
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withoutModule("shared");
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void changingModuleSizeMatchesFullRecompute() {
        properties.setMaxFirstScreenBytes(20);
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withModule(mod("leaf", 100, false));
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void addingEdgeCreatingCycleMatchesFullRecompute() {
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withImport("leaf", "shared");
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void edgeMakingOrphanReachableMatchesFullRecompute() {
        GraphSnapshot oldGraph = baseGraph();
        GraphSnapshot newGraph = oldGraph.withImport("leaf", "orphan");
        assertIncrementalMatchesFull(oldGraph, newGraph);
    }

    @Test
    void unaffectedModulesKeepTheirBundles() {
        GraphSnapshot oldGraph = baseGraph();
        // change only reachable-from-entryA structure; entryB-only modules unaffected
        GraphSnapshot newGraph = oldGraph.withModule(mod("extra", 2, false))
                .withImport("onlyA", "extra");
        LoadPlan oldPlan = generator.generate(oldGraph, 1);
        IncrementalPlanner.IncrementalResult result =
                incremental.recompute(oldGraph, newGraph, oldPlan, 2);

        assertTrue(result.affectedEntries().contains("entryA"));
        assertFalse(result.affectedEntries().contains("entryB"));
        assertFalse(result.affectedModules().contains("onlyB"),
                "onlyB is exclusive to the unaffected entryB");
        // the bundle containing onlyB is carried over untouched
        String oldBundleId = oldPlan.moduleToBundle().get("onlyB");
        assertEquals(oldBundleId, result.plan().moduleToBundle().get("onlyB"));
        assertTrue(result.plan().bundles().stream()
                .anyMatch(b -> b.id().equals(oldBundleId)));
    }
}
