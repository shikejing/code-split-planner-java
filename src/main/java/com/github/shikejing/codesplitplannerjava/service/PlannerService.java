package com.github.shikejing.codesplitplannerjava.service;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.core.IncrementalPlanner;
import com.github.shikejing.codesplitplannerjava.core.PlanDiffer;
import com.github.shikejing.codesplitplannerjava.core.PlanGenerator;
import com.github.shikejing.codesplitplannerjava.model.Bundle;
import com.github.shikejing.codesplitplannerjava.model.EntryLoadSet;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe facade over graph mutation and plan generation.
 *
 * <p>All updates are serialized by a single lock and published by swapping an
 * {@link AtomicReference} to a fully built, immutable {@link State}. Readers
 * never touch the lock and therefore only ever observe the last committed
 * plan as a whole — never a half-updated state. Every committed plan is
 * persisted before publication, so history survives restarts.
 */
@Service
public class PlannerService {

    /** Immutable committed state: the graph and the plan derived from it. */
    private record State(GraphSnapshot graph, LoadPlan plan) {
    }

    private final PlanGenerator generator;
    private final IncrementalPlanner incrementalPlanner;
    private final PlanStore store;
    private final ReentrantLock updateLock = new ReentrantLock();
    private final AtomicReference<State> committed;

    public PlannerService(PlannerProperties properties, PlanGenerator generator,
                          IncrementalPlanner incrementalPlanner) {
        this.generator = generator;
        this.incrementalPlanner = incrementalPlanner;
        this.store = new PlanStore(properties.getStoreDir());
        this.committed = new AtomicReference<>(restore());
    }

    /** Loads the latest persisted state, or starts empty on first boot. */
    private State restore() {
        return store.latestVersion()
                .flatMap(v -> store.loadPlan(v).flatMap(
                        plan -> store.loadGraph(v).map(graph -> new State(graph, plan))))
                .orElseGet(() -> new State(new GraphSnapshot(), LoadPlan.empty()));
    }

    /** Replaces the whole graph and generates a fresh plan (full recompute). */
    public LoadPlan replaceGraph(GraphSnapshot newGraph) {
        updateLock.lock();
        try {
            long version = committed.get().plan().version() + 1;
            LoadPlan plan = generator.generate(newGraph, version);
            return commit(new State(newGraph, plan));
        } finally {
            updateLock.unlock();
        }
    }

    /** Adds or replaces a module, then incrementally recomputes the plan. */
    public IncrementalPlanner.IncrementalResult upsertModule(ModuleNode node) {
        return applyIncremental(graph -> graph.withModule(node));
    }

    public IncrementalPlanner.IncrementalResult removeModule(String moduleId) {
        return applyIncremental(graph -> graph.withoutModule(moduleId));
    }

    public IncrementalPlanner.IncrementalResult addImport(String from, String to) {
        return applyIncremental(graph -> graph.withImport(from, to));
    }

    public IncrementalPlanner.IncrementalResult removeImport(String from, String to) {
        return applyIncremental(graph -> graph.withoutImport(from, to));
    }

    /**
     * Applies a mutation to the committed graph and incrementally recomputes.
     * The mutation is applied inside the lock so it always builds on the
     * latest committed state, never on a stale snapshot.
     */
    private IncrementalPlanner.IncrementalResult applyIncremental(
            java.util.function.UnaryOperator<GraphSnapshot> mutation) {
        updateLock.lock();
        try {
            State current = committed.get();
            GraphSnapshot newGraph = mutation.apply(current.graph());
            IncrementalPlanner.IncrementalResult result = incrementalPlanner.recompute(
                    current.graph(), newGraph, current.plan(), current.plan().version() + 1);
            commit(new State(newGraph, result.plan()));
            return result;
        } finally {
            updateLock.unlock();
        }
    }

    /** Persists first, then publishes atomically. */
    private LoadPlan commit(State state) {
        store.save(state.plan().version(), state.graph(), state.plan());
        committed.set(state);
        return state.plan();
    }

    /** The currently committed plan. */
    public LoadPlan currentPlan() {
        return committed.get().plan();
    }

    public GraphSnapshot currentGraph() {
        return committed.get().graph();
    }

    public List<Long> versions() {
        return store.versions();
    }

    public LoadPlan planAt(long version) {
        if (version == currentPlan().version()) {
            return currentPlan();
        }
        return store.loadPlan(version)
                .orElseThrow(() -> new NoSuchElementException("no plan with version " + version));
    }

    public PlanDiff diff(long fromVersion, long toVersion) {
        return PlanDiffer.diff(planAt(fromVersion), planAt(toVersion));
    }

    /**
     * The actual load set for one entry, derived from the committed plan.
     *
     * <p>A module is included when the entry can reach it, i.e. when it was
     * grouped into a bundle whose entry set contains this entry. Duplicate
     * imports and transitive dependencies collapse to a single entry per
     * module; each module appears exactly once, under its bundle's timing.
     */
    public EntryLoadSet loadSetFor(String entryId) {
        State state = committed.get();
        LoadPlan plan = state.plan();
        if (!state.graph().entries().contains(entryId)) {
            throw new NoSuchElementException("not an entry module: " + entryId);
        }
        // Recompute the entry's closure deterministically from the graph.
        var reachable = com.github.shikejing.codesplitplannerjava.core.GraphAlgorithms
                .reachableFrom(state.graph(), java.util.Set.of(entryId));
        List<String> eager = new ArrayList<>();
        List<String> prefetch = new ArrayList<>();
        List<String> lazy = new ArrayList<>();
        Map<String, String> moduleToBundle = new TreeMap<>();
        for (String module : reachable) {
            LoadTiming timing = plan.moduleTiming().get(module);
            String bundleId = plan.moduleToBundle().get(module);
            if (timing == null || bundleId == null) {
                continue; // not packed (unreachable from all entries cannot happen here)
            }
            moduleToBundle.put(module, bundleId);
            switch (timing) {
                case EAGER -> eager.add(module);
                case PREFETCH -> prefetch.add(module);
                case LAZY -> lazy.add(module);
                case SKIPPED -> { /* excluded from the load set */ }
            }
        }
        eager.sort(String::compareTo);
        prefetch.sort(String::compareTo);
        lazy.sort(String::compareTo);
        return new EntryLoadSet(entryId, plan.version(), eager, prefetch, lazy, moduleToBundle);
    }
}
