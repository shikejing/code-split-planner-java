package com.github.shikejing.codesplitplannerjava;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.core.IncrementalPlanner;
import com.github.shikejing.codesplitplannerjava.core.PlanGenerator;
import com.github.shikejing.codesplitplannerjava.model.EntryLoadSet;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;
import com.github.shikejing.codesplitplannerjava.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerServiceTest {

    @TempDir
    Path storeDir;

    private PlannerService newService() {
        PlannerProperties props = new PlannerProperties();
        props.setStoreDir(storeDir.toString());
        return new PlannerService(props, new PlanGenerator(props), new IncrementalPlanner(props));
    }

    private static ModuleNode mod(String id, long size, boolean entry) {
        return new ModuleNode(id, size, entry);
    }

    private static GraphSnapshot sampleGraph() {
        GraphSnapshot g = new GraphSnapshot();
        for (ModuleNode m : List.of(mod("entryA", 10, true), mod("entryB", 10, true),
                mod("shared", 5, false), mod("onlyA", 3, false))) {
            g = g.withModule(m);
        }
        return g.withImport("entryA", "shared").withImport("entryB", "shared")
                .withImport("entryA", "onlyA");
    }

    @Test
    void committedPlansAreVersionedPersistedAndComparable() {
        PlannerService service = newService();
        LoadPlan v1 = service.replaceGraph(sampleGraph());
        assertEquals(1, v1.version());

        LoadPlan v2 = service.upsertModule(mod("extra", 2, false)).plan();
        assertEquals(2, v2.version());
        service.addImport("shared", "extra");
        assertEquals(3, service.currentPlan().version());

        // history readable
        assertEquals(List.of(1L, 2L, 3L), service.versions());
        assertTrue(service.planAt(1).sameContent(v1));

        // diff between versions: the only change is the new module "extra"
        PlanDiff diff = service.diff(1, 3);
        assertEquals(1, diff.fromVersion());
        assertEquals(3, diff.toVersion());
        assertEquals(java.util.Set.of("extra"), diff.timingChanges().keySet());
        assertEquals(LoadTiming.EAGER, diff.timingChanges().get("extra").to());
        assertTrue(diff.addedBundles().stream()
                .anyMatch(b -> b.members().contains("extra")));

        // survives "restart": a new service over the same store dir resumes
        PlannerService restarted = newService();
        assertEquals(3, restarted.currentPlan().version());
        assertTrue(restarted.currentPlan().sameContent(service.currentPlan()));
        assertTrue(restarted.planAt(1).sameContent(v1));
    }

    @Test
    void loadSetForEntryReflectsClosureAndTimings() {
        PlannerProperties props = new PlannerProperties();
        props.setStoreDir(storeDir.toString());
        props.setMaxFirstScreenModules(4);
        PlannerService service = new PlannerService(props, new PlanGenerator(props),
                new IncrementalPlanner(props));
        service.replaceGraph(sampleGraph());

        EntryLoadSet forA = service.loadSetFor("entryA");
        // entryA's closure: entryA, shared, onlyA; budget 3 fits all
        assertEquals(List.of("entryA", "onlyA", "shared"), forA.eager());
        assertTrue(forA.lazy().isEmpty());
        // every listed module maps to a bundle
        assertEquals(3, forA.moduleToBundle().size());

        EntryLoadSet forB = service.loadSetFor("entryB");
        assertEquals(List.of("entryB", "shared"), forB.eager());

        // duplicate/transitive references collapse: shared appears once
        assertEquals(1, forA.eager().stream().filter("shared"::equals).count());

        assertThrows(NoSuchElementException.class, () -> service.loadSetFor("onlyA"));
    }

    @Test
    void loadSetReflectsBudgetDemotions() {
        PlannerProperties props = new PlannerProperties();
        props.setStoreDir(storeDir.toString());
        props.setMaxFirstScreenModules(1);
        PlannerService service = new PlannerService(props, new PlanGenerator(props),
                new IncrementalPlanner(props));
        GraphSnapshot g = new GraphSnapshot()
                .withModule(mod("entry", 10, true))
                .withModule(mod("dep", 1, false))
                .withImport("entry", "dep");
        service.replaceGraph(g);

        EntryLoadSet loadSet = service.loadSetFor("entry");
        assertEquals(List.of("entry"), loadSet.eager());
        assertEquals(List.of("dep"), loadSet.prefetch()); // direct dep of eager entry
    }

    @Test
    void concurrentReadersOnlySeeCommittedPlans() throws Exception {
        PlannerService service = newService();
        service.replaceGraph(sampleGraph());

        int writers = 4;
        int readers = 8;
        int writesPerWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            int id = w;
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < writesPerWriter; i++) {
                    service.upsertModule(mod("w" + id + "-m" + i, 1, false));
                }
            }));
        }
        List<String> violations = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                await(start);
                long end = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < end) {
                    LoadPlan plan = service.currentPlan();
                    // invariant of a committed plan: every packed module's
                    // bundle exists and contains the module
                    for (var e : plan.moduleToBundle().entrySet()) {
                        boolean ok = plan.bundles().stream()
                                .anyMatch(b -> b.id().equals(e.getValue())
                                        && b.members().contains(e.getKey()));
                        if (!ok) {
                            synchronized (violations) {
                                violations.add("inconsistent plan at version "
                                        + plan.version());
                            }
                            return;
                        }
                    }
                    // version monotonicity within a reader is not required,
                    // but a read plan must always be complete (checked above)
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(violations.isEmpty(), () -> "readers saw torn plans: " + violations);
        // all 100 module additions committed, each bumping the version
        assertEquals(1 + writers * writesPerWriter, service.currentPlan().version());
    }

    @Test
    void concurrentWritesAreSerializedAndConsistent() throws Exception {
        PlannerService service = newService();
        service.replaceGraph(sampleGraph());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            futures.add(pool.submit(() -> {
                await(start);
                service.upsertModule(mod("concurrent-" + id, 1, false));
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        LoadPlan plan = service.currentPlan();
        assertEquals(1 + threads, plan.version());
        for (int t = 0; t < threads; t++) {
            assertNotNull(plan.moduleTiming().get("concurrent-" + t));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
