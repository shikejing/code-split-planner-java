package com.github.shikejing.codesplitplannerjava;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.engine.DeterministicPlanner;
import com.github.shikejing.codesplitplannerjava.model.EntryLoadSet;
import com.github.shikejing.codesplitplannerjava.model.GraphUpdate;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleSpec;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;
import com.github.shikejing.codesplitplannerjava.service.PlanStore;
import com.github.shikejing.codesplitplannerjava.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerServiceTest {

    @TempDir
    Path tempDir;

    private PlannerService newService(PlannerProperties props) {
        String dir = tempDir.resolve("plans").toString();
        return new PlannerService(new DeterministicPlanner(props), new PlanStore(dir), dir);
    }

    private static GraphUpdate baseGraph() {
        List<GraphUpdate.Op> ops = new ArrayList<>();
        for (String id : List.of("entryA", "entryB", "common", "onlyA", "onlyB")) {
            ops.add(new GraphUpdate.UpsertModule(new ModuleSpec(id, 10)));
        }
        ops.add(new GraphUpdate.AddImport("entryA", "common"));
        ops.add(new GraphUpdate.AddImport("entryB", "common"));
        ops.add(new GraphUpdate.AddImport("entryA", "onlyA"));
        ops.add(new GraphUpdate.AddImport("entryB", "onlyB"));
        ops.add(new GraphUpdate.AddEntry("entryA"));
        ops.add(new GraphUpdate.AddEntry("entryB"));
        return new GraphUpdate(ops);
    }

    @Test
    void versionsIncreaseAndHistoryIsReadable() {
        PlannerService service = newService(new PlannerProperties());
        service.applyUpdate(baseGraph());
        service.applyUpdate(new GraphUpdate(List.of(
                new GraphUpdate.UpsertModule(new ModuleSpec("extra", 5)),
                new GraphUpdate.AddImport("entryA", "extra"))));

        assertEquals(List.of(1L, 2L), service.versions());
        LoadPlan v1 = service.planAt(1).orElseThrow();
        LoadPlan v2 = service.planAt(2).orElseThrow();
        assertFalse(v1.placements().containsKey("extra"));
        assertTrue(v2.placements().containsKey("extra"));
        assertEquals(v2, service.currentPlan().orElseThrow());
    }

    @Test
    void incrementalResultEqualsFullRecompute() {
        PlannerProperties props = new PlannerProperties();
        PlannerService incremental = newService(props);
        incremental.applyUpdate(baseGraph());
        // 第二批变更：新增模块、删边、删模块
        Set<String> affected = incremental.applyUpdate(new GraphUpdate(List.of(
                new GraphUpdate.UpsertModule(new ModuleSpec("extra", 5)),
                new GraphUpdate.AddImport("entryA", "extra"),
                new GraphUpdate.RemoveImport("entryB", "onlyB"),
                new GraphUpdate.RemoveModule("onlyB"))));
        assertTrue(affected.contains("extra"));
        assertTrue(affected.contains("entryB"), "entryB depends on removed module");

        // 全量重算：新服务一次性提交最终图
        PlannerService full = new PlannerService(new DeterministicPlanner(props),
                new PlanStore(tempDir.resolve("full").toString()),
                tempDir.resolve("full").toString());
        List<GraphUpdate.Op> all = new ArrayList<>(baseGraph().ops());
        all.add(new GraphUpdate.UpsertModule(new ModuleSpec("extra", 5)));
        all.add(new GraphUpdate.AddImport("entryA", "extra"));
        all.add(new GraphUpdate.RemoveImport("entryB", "onlyB"));
        all.add(new GraphUpdate.RemoveModule("onlyB"));
        full.applyUpdate(new GraphUpdate(all));

        LoadPlan inc = incremental.currentPlan().orElseThrow();
        LoadPlan ref = full.currentPlan().orElseThrow();
        assertEquals(ref.chunks(), inc.chunks(), "incremental must equal full recompute");
        assertEquals(ref.placements(), inc.placements());
        assertEquals(ref.skipped(), inc.skipped());
    }

    @Test
    void plansSurviveRestart() {
        PlannerService service = newService(new PlannerProperties());
        service.applyUpdate(baseGraph());
        LoadPlan committed = service.currentPlan().orElseThrow();

        // 模拟重启：同一目录新建服务
        PlannerService restarted = newService(new PlannerProperties());
        assertEquals(committed, restarted.currentPlan().orElseThrow(),
                "committed plan must survive restart");
        assertEquals(List.of(1L), restarted.versions());
        // 重启后继续提交，版本号接续
        restarted.applyUpdate(new GraphUpdate(List.of(
                new GraphUpdate.UpsertModule(new ModuleSpec("extra", 5)))));
        assertEquals(List.of(1L, 2L), restarted.versions());
        // 重启后按入口查询仍可复现
        EntryLoadSet loadSet = restarted.loadSetFor("entryA");
        assertTrue(loadSet.modules().stream().anyMatch(m -> m.moduleId().equals("common")));
    }

    @Test
    void diffReportsModuleAndTimingChanges() {
        PlannerProperties props = new PlannerProperties();
        PlannerService service = newService(props);
        service.applyUpdate(baseGraph());
        service.applyUpdate(new GraphUpdate(List.of(
                new GraphUpdate.UpsertModule(new ModuleSpec("extra", 5)),
                new GraphUpdate.AddImport("entryA", "extra"),
                new GraphUpdate.RemoveModule("onlyB"))));

        PlanDiff diff = service.diff(1, 2);
        assertEquals(1, diff.fromVersion());
        assertEquals(2, diff.toVersion());
        assertEquals(List.of("extra"), diff.addedModules());
        assertEquals(List.of("onlyB"), diff.removedModules());
        assertTrue(diff.timingChanges().isEmpty());
        assertFalse(diff.addedChunks().isEmpty(), "new module must create a new chunk");
    }

    @Test
    void loadSetForEntryResolvesTransitiveClosure() {
        PlannerService service = newService(new PlannerProperties());
        service.applyUpdate(baseGraph());
        EntryLoadSet set = service.loadSetFor("entryA");
        List<String> ids = set.modules().stream().map(EntryLoadSet.EntryModule::moduleId).toList();
        // 传递闭包：entryA 自身 + 直接依赖 + 共享依赖；不含 entryB 私有模块
        assertEquals(List.of("common", "entryA", "onlyA"), ids);
        // 重复引用只出现一次（天然由集合语义保证），且每项都有加载时机
        for (EntryLoadSet.EntryModule m : set.modules()) {
            assertEquals(LoadTiming.EAGER, m.timing());
            assertFalse(m.chunkId().isBlank());
        }
        assertThrows(NoSuchElementException.class, () -> service.loadSetFor("notAnEntry"));
    }

    @Test
    void concurrentReadersOnlySeeCommittedPlans() throws Exception {
        PlannerService service = newService(new PlannerProperties());
        service.applyUpdate(baseGraph());
        LoadPlan v1 = service.currentPlan().orElseThrow();

        int readers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        List<String> violations = new CopyOnWriteArrayList<>();
        for (int i = 0; i < readers; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 200; j++) {
                        LoadPlan p = service.currentPlan().orElseThrow();
                        // 读到的每个计划都必须是某个完整提交的版本
                        if (!p.equals(v1) && !p.equals(service.planAt(p.version()).orElseThrow())) {
                            violations.add("uncommitted plan observed: " + p.version());
                        }
                        // 计划内部一致性：placements 引用的 chunk 必须存在
                        for (var placement : p.placements().values()) {
                            boolean chunkExists = p.chunks().stream()
                                    .anyMatch(c -> c.id().equals(placement.chunkId()));
                            if (!chunkExists) {
                                violations.add("half-updated plan: dangling chunk " + placement.chunkId());
                            }
                        }
                        service.loadSetFor("entryA");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        // 写线程并发提交多个版本
        new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                service.applyUpdate(new GraphUpdate(List.of(
                        new GraphUpdate.UpsertModule(new ModuleSpec("m" + i, i + 1)),
                        new GraphUpdate.AddImport("entryA", "m" + i))));
            }
        }).start();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
        assertEquals(21L, service.currentPlan().orElseThrow().version());
    }
}
