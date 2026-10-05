package com.github.shikejing.codesplitplannerjava;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.engine.DeterministicPlanner;
import com.github.shikejing.codesplitplannerjava.engine.ModuleGraph;
import com.github.shikejing.codesplitplannerjava.model.Chunk;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleSpec;
import com.github.shikejing.codesplitplannerjava.model.SkipReason;
import com.github.shikejing.codesplitplannerjava.model.SkippedModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicPlannerTest {

    private DeterministicPlanner planner(PlannerProperties props) {
        return new DeterministicPlanner(props);
    }

    /** 两个入口共享 common；各自有私有模块；orphan 不可达。 */
    private ModuleGraph sampleGraph(boolean shuffled, long seed) {
        List<ModuleSpec> modules = new ArrayList<>(List.of(
                new ModuleSpec("entryA", 100),
                new ModuleSpec("entryB", 100),
                new ModuleSpec("common", 50),
                new ModuleSpec("onlyA", 10),
                new ModuleSpec("onlyB", 20),
                new ModuleSpec("orphan", 5)));
        List<String[]> edges = new ArrayList<>(List.of(
                new String[]{"entryA", "common"},
                new String[]{"entryB", "common"},
                new String[]{"entryA", "onlyA"},
                new String[]{"entryB", "onlyB"}));
        List<String> entries = new ArrayList<>(List.of("entryA", "entryB"));
        if (shuffled) {
            Random r = new Random(seed);
            Collections.shuffle(modules, r);
            Collections.shuffle(edges, r);
            Collections.shuffle(entries, r);
        }
        ModuleGraph g = new ModuleGraph();
        for (ModuleSpec m : modules) {
            g.upsertModule(m);
        }
        for (String[] e : edges) {
            g.addImport(e[0], e[1]);
        }
        for (String e : entries) {
            g.addEntry(e);
        }
        return g;
    }

    @Test
    void shuffledInputProducesIdenticalPlan() {
        LoadPlan reference = planner(new PlannerProperties()).plan(sampleGraph(false, 0), 1);
        for (long seed = 1; seed <= 20; seed++) {
            LoadPlan shuffled = planner(new PlannerProperties()).plan(sampleGraph(true, seed), 1);
            assertEquals(reference, shuffled, "plan must not depend on input order, seed=" + seed);
        }
    }

    @Test
    void sharedModuleIsPackedExactlyOnce() {
        LoadPlan plan = planner(new PlannerProperties()).plan(sampleGraph(false, 0), 1);
        long occurrences = plan.chunks().stream()
                .filter(c -> c.members().contains("common"))
                .count();
        assertEquals(1, occurrences, "shared module must appear in exactly one chunk");
        Chunk shared = plan.chunks().stream()
                .filter(c -> c.members().contains("common"))
                .findFirst().orElseThrow();
        assertEquals(List.of("common"), shared.members());
        assertEquals(LoadTiming.EAGER, shared.timing());
        // 包内成员按 id 字典序
        for (Chunk c : plan.chunks()) {
            List<String> sorted = c.members().stream().sorted().toList();
            assertEquals(sorted, c.members());
        }
    }

    @Test
    void unreachableModuleIsSkippedNotEager() {
        LoadPlan plan = planner(new PlannerProperties()).plan(sampleGraph(false, 0), 1);
        assertFalse(plan.placements().containsKey("orphan"), "unreachable module must not be placed");
        assertTrue(plan.skipped().contains(new SkippedModule("orphan", SkipReason.UNREACHABLE)));
        long eagerCount = plan.placements().values().stream()
                .filter(p -> p.timing() == LoadTiming.EAGER).count();
        assertEquals(5, eagerCount, "orphan must not count towards first screen");
    }

    @Test
    void cycleIsDegradedToLazyAndLocated() {
        ModuleGraph g = new ModuleGraph();
        g.upsertModule(new ModuleSpec("entry", 10));
        g.upsertModule(new ModuleSpec("a", 10));
        g.upsertModule(new ModuleSpec("b", 10));
        g.upsertModule(new ModuleSpec("c", 10));
        g.addImport("entry", "a");
        g.addImport("a", "b");
        g.addImport("b", "c");
        g.addImport("c", "a"); // 环 a -> b -> c -> a
        g.addEntry("entry");

        LoadPlan plan = planner(new PlannerProperties()).plan(g, 1);
        // 生成不失败；环成员整体一个 LAZY 包
        Chunk cycleChunk = plan.chunks().stream()
                .filter(c -> c.members().contains("a"))
                .findFirst().orElseThrow();
        assertEquals(List.of("a", "b", "c"), cycleChunk.members());
        assertEquals(LoadTiming.LAZY, cycleChunk.timing());
        for (String m : List.of("a", "b", "c")) {
            assertTrue(plan.skipped().contains(new SkippedModule(m, SkipReason.IN_CYCLE)));
            assertTrue(plan.placements().get(m).reason().startsWith("cycle:"),
                    "cycle reason must be locatable");
        }
        // 环外模块不受影响
        assertEquals(LoadTiming.EAGER, plan.placements().get("entry").timing());
    }

    @Test
    void budgetDemotesModulesWithExplanation() {
        ModuleGraph g = new ModuleGraph();
        g.upsertModule(new ModuleSpec("entry", 10));
        g.upsertModule(new ModuleSpec("big1", 500));
        g.upsertModule(new ModuleSpec("big2", 400));
        g.upsertModule(new ModuleSpec("small", 30));
        g.addImport("entry", "big1");
        g.addImport("entry", "big2");
        g.addImport("entry", "small");
        g.addEntry("entry");

        PlannerProperties props = new PlannerProperties();
        props.setBudgetMaxBytes(100); // entry(10)+big1+big2+small 远超预算
        LoadPlan plan = planner(props).plan(g, 1);

        long eagerBytes = plan.placements().values().stream()
                .filter(p -> p.timing() == LoadTiming.EAGER)
                .mapToLong(p -> switch (p.moduleId()) {
                    case "entry" -> 10;
                    case "big1" -> 500;
                    case "big2" -> 400;
                    default -> 30;
                }).sum();
        assertTrue(eagerBytes <= 100 || eagerBytes == 10,
                "must not silently exceed budget, eagerBytes=" + eagerBytes);
        // 入口永不降级
        assertEquals(LoadTiming.EAGER, plan.placements().get("entry").timing());
        // 被降级的模块必须带可解释的触发条件
        for (var p : plan.placements().values()) {
            if (p.timing() == LoadTiming.LAZY) {
                assertTrue(p.reason().contains("budget-demoted:"),
                        "demotion must be explained: " + p);
                assertTrue(p.reason().contains("maxBytes=100"));
            }
        }
        // 大体积优先降级：big1 应先于 small 被降级
        assertEquals(LoadTiming.LAZY, plan.placements().get("big1").timing());
    }

    @Test
    void moduleCountBudgetIsRespected() {
        ModuleGraph g = new ModuleGraph();
        g.upsertModule(new ModuleSpec("entry", 1));
        for (int i = 0; i < 5; i++) {
            g.upsertModule(new ModuleSpec("m" + i, 1));
            g.addImport("entry", "m" + i);
        }
        g.addEntry("entry");
        PlannerProperties props = new PlannerProperties();
        props.setBudgetMaxModules(3);
        LoadPlan plan = planner(props).plan(g, 1);
        long eager = plan.placements().values().stream()
                .filter(p -> p.timing() == LoadTiming.EAGER).count();
        assertTrue(eager <= 3, "eager count must respect budget, got " + eager);
        assertTrue(plan.placements().values().stream()
                .anyMatch(p -> p.reason().contains("maxModules=3")));
    }

    @Test
    void sharedChunkPrefetchIsConfigurable() {
        PlannerProperties props = new PlannerProperties();
        props.setSharedChunkPrefetch(true);
        LoadPlan plan = planner(props).plan(sampleGraph(false, 0), 1);
        assertEquals(LoadTiming.PREFETCH, plan.placements().get("common").timing());
        assertEquals(LoadTiming.EAGER, plan.placements().get("onlyA").timing());
    }

    @Test
    void concurrentPlanningOfSameGraphIsIdentical() throws Exception {
        ModuleGraph graph = sampleGraph(false, 0);
        LoadPlan reference = planner(new PlannerProperties()).plan(graph, 7);
        List<LoadPlan> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> results.add(planner(new PlannerProperties()).plan(graph, 7)));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        for (LoadPlan p : results) {
            assertEquals(reference, p);
        }
    }
}
