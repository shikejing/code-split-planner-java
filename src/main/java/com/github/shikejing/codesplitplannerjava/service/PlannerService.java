package com.github.shikejing.codesplitplannerjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.codesplitplannerjava.engine.DeterministicPlanner;
import com.github.shikejing.codesplitplannerjava.engine.ModuleGraph;
import com.github.shikejing.codesplitplannerjava.model.EntryLoadSet;
import com.github.shikejing.codesplitplannerjava.model.GraphUpdate;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModulePlacement;
import com.github.shikejing.codesplitplannerjava.model.ModuleSpec;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 分包与加载策略服务。
 *
 * <p>并发语义：读写锁保护图与计划历史；写线程在锁内完成
 * “应用变更 → 增量重算 → 持久化 → 提交”全过程，提交动作只是把
 * 当前计划引用替换为一份完全构建好的不可变计划，因此读线程
 * 永远只能看到某个已提交的完整计划，不会读到半更新状态。
 *
 * <p>增量重算：服务缓存每个入口的可达模块集合。一批变更提交时，
 * 先算出受影响模块（变更点及其在变更前/后图上的反向可达闭包），
 * 只有可达集合与受影响范围相交的入口才重新遍历，其余入口复用缓存，
 * 最终组装出的计划与全量重算逐项一致（有测试保证）。
 */
public class PlannerService {

    private final DeterministicPlanner planner;
    private final PlanStore planStore;
    private final Path graphFile;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ModuleGraph graph = new ModuleGraph();
    private final Map<String, Set<String>> reachabilityCache = new TreeMap<>();
    private final Map<Long, LoadPlan> history = new TreeMap<>();
    private volatile LoadPlan current;
    private long nextVersion = 1;

    public PlannerService(DeterministicPlanner planner, PlanStore planStore, String dataDir) {
        this.planner = planner;
        this.planStore = planStore;
        this.graphFile = Path.of(dataDir).resolve("graph.json");
        restore();
    }

    /** 启动时恢复：已提交计划全部读回，图状态重建，可达缓存全量预热。 */
    private void restore() {
        for (LoadPlan plan : planStore.loadAll()) {
            history.put(plan.version(), plan);
            nextVersion = Math.max(nextVersion, plan.version() + 1);
        }
        if (!history.isEmpty()) {
            current = history.get(((TreeMap<Long, LoadPlan>) history).lastKey());
        }
        if (Files.exists(graphFile)) {
            try {
                GraphState state = mapper.readValue(graphFile.toFile(), GraphState.class);
                for (ModuleSpec m : state.modules()) {
                    graph.upsertModule(m);
                }
                state.imports().forEach((from, targets) -> {
                    for (String to : targets) {
                        graph.addImport(from, to);
                    }
                });
                for (String e : state.entries()) {
                    graph.addEntry(e);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot restore graph state", e);
            }
        }
        rebuildReachability(Set.copyOf(DeterministicPlanner.sortedEntries(graph)));
    }

    /**
     * 应用一批图变更并提交新版本计划，返回受影响模块范围（排序）。
     */
    public Set<String> applyUpdate(GraphUpdate update) {
        lock.writeLock().lock();
        try {
            Set<String> changed = changedModules(update);
            Set<String> affected = new TreeSet<>(changed);
            affected.addAll(reverseClosure(changed, graph));

            for (GraphUpdate.Op op : update.ops()) {
                apply(op);
            }
            // 变更后的图上再取一次反向闭包，覆盖新增边引入的影响。
            affected.addAll(reverseClosure(changed, graph));

            // 只重算受影响入口：缓存可达集合与受影响范围相交的入口，
            // 以及没有任何缓存的新入口。
            Set<String> liveEntries = Set.copyOf(DeterministicPlanner.sortedEntries(graph));
            reachabilityCache.keySet().retainAll(new TreeSet<>(liveEntries));
            Set<String> toRecompute = new TreeSet<>();
            for (String entry : liveEntries) {
                Set<String> cached = reachabilityCache.get(entry);
                if (cached == null || !Set.copyOf(cached).stream().filter(affected::contains).toList().isEmpty()) {
                    toRecompute.add(entry);
                }
            }
            rebuildReachability(toRecompute);

            LoadPlan plan = planner.planWithReachability(graph, nextVersion++, reachabilityCache);
            planStore.save(plan);
            persistGraph();
            history.put(plan.version(), plan);
            current = plan; // 原子提交：读者只看到完整计划
            return affected;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void rebuildReachability(Set<String> entries) {
        Map<String, List<String>> adjacency = DeterministicPlanner.sortedAdjacency(graph);
        for (String entry : entries) {
            reachabilityCache.put(entry, planner.reachable(entry, adjacency));
        }
    }

    private Set<String> changedModules(GraphUpdate update) {
        Set<String> changed = new TreeSet<>();
        for (GraphUpdate.Op op : update.ops()) {
            if (op instanceof GraphUpdate.UpsertModule u) {
                changed.add(u.module().id());
            } else if (op instanceof GraphUpdate.RemoveModule r) {
                changed.add(r.moduleId());
            } else if (op instanceof GraphUpdate.AddEntry a) {
                changed.add(a.moduleId());
            } else if (op instanceof GraphUpdate.RemoveEntry r) {
                changed.add(r.moduleId());
            } else if (op instanceof GraphUpdate.AddImport a) {
                changed.add(a.importer());
                changed.add(a.imported());
            } else if (op instanceof GraphUpdate.RemoveImport r) {
                changed.add(r.importer());
                changed.add(r.imported());
            }
        }
        return changed;
    }

    /** 变更点的反向可达闭包：所有（传递）依赖于变更模块的模块。 */
    private Set<String> reverseClosure(Set<String> seeds, ModuleGraph g) {
        Map<String, Set<String>> reverse = new ConcurrentHashMap<>();
        g.imports().forEach((from, targets) -> {
            for (String to : targets) {
                reverse.computeIfAbsent(to, k -> ConcurrentHashMap.newKeySet()).add(from);
            }
        });
        Set<String> closure = new TreeSet<>();
        List<String> queue = new ArrayList<>(seeds);
        while (!queue.isEmpty()) {
            String cur = queue.remove(queue.size() - 1);
            for (String parent : reverse.getOrDefault(cur, Set.of())) {
                if (closure.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return closure;
    }

    private void apply(GraphUpdate.Op op) {
        if (op instanceof GraphUpdate.UpsertModule u) {
            graph.upsertModule(u.module());
        } else if (op instanceof GraphUpdate.RemoveModule r) {
            graph.removeModule(r.moduleId());
        } else if (op instanceof GraphUpdate.AddEntry a) {
            graph.addEntry(a.moduleId());
        } else if (op instanceof GraphUpdate.RemoveEntry r) {
            graph.removeEntry(r.moduleId());
        } else if (op instanceof GraphUpdate.AddImport a) {
            graph.addImport(a.importer(), a.imported());
        } else if (op instanceof GraphUpdate.RemoveImport r) {
            graph.removeImport(r.importer(), r.imported());
        }
    }

    private void persistGraph() {
        try {
            GraphState state = new GraphState(
                    List.copyOf(graph.modules().values()),
                    sortedImports(),
                    List.copyOf(graph.entries()));
            mapper.writerWithDefaultPrettyPrinter().writeValue(graphFile.toFile(), state);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist graph state", e);
        }
    }

    private Map<String, List<String>> sortedImports() {
        Map<String, List<String>> result = new TreeMap<>();
        graph.imports().forEach((from, targets) -> result.put(from, List.copyOf(new TreeSet<>(targets))));
        return result;
    }

    /** 当前最新已提交计划。 */
    public Optional<LoadPlan> currentPlan() {
        return Optional.ofNullable(current);
    }

    /** 读取历史版本计划。 */
    public Optional<LoadPlan> planAt(long version) {
        lock.readLock().lock();
        try {
            LoadPlan plan = history.get(version);
            if (plan != null) {
                return Optional.of(plan);
            }
        } finally {
            lock.readLock().unlock();
        }
        return planStore.load(version);
    }

    /** 全部已提交版本号，升序。 */
    public List<Long> versions() {
        lock.readLock().lock();
        try {
            return List.copyOf(history.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 对比两个版本的计划差异。 */
    public PlanDiff diff(long fromVersion, long toVersion) {
        LoadPlan from = planAt(fromVersion)
                .orElseThrow(() -> new NoSuchElementException("no plan version " + fromVersion));
        LoadPlan to = planAt(toVersion)
                .orElseThrow(() -> new NoSuchElementException("no plan version " + toVersion));

        Set<String> fromModules = from.placements().keySet();
        Set<String> toModules = to.placements().keySet();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Map<String, PlanDiff.TimingChange> timingChanges = new TreeMap<>();
        for (String m : toModules) {
            if (!fromModules.contains(m)) {
                added.add(m);
            }
        }
        for (String m : fromModules) {
            if (!toModules.contains(m)) {
                removed.add(m);
            }
        }
        for (String m : fromModules) {
            ModulePlacement a = from.placements().get(m);
            ModulePlacement b = to.placements().get(m);
            if (b != null && a.timing() != b.timing()) {
                timingChanges.put(m, new PlanDiff.TimingChange(a.timing(), b.timing()));
            }
        }
        Set<String> fromChunks = chunkIds(from);
        Set<String> toChunks = chunkIds(to);
        List<String> addedChunks = new ArrayList<>(toChunks);
        addedChunks.removeAll(fromChunks);
        addedChunks.sort(String::compareTo);
        List<String> removedChunks = new ArrayList<>(fromChunks);
        removedChunks.removeAll(toChunks);
        removedChunks.sort(String::compareTo);
        added.sort(String::compareTo);
        removed.sort(String::compareTo);
        return new PlanDiff(fromVersion, toVersion, added, removed, timingChanges, addedChunks, removedChunks);
    }

    private Set<String> chunkIds(LoadPlan plan) {
        Set<String> ids = new TreeSet<>();
        plan.chunks().forEach(c -> ids.add(c.id()));
        return ids;
    }

    /**
     * 按入口查询实际加载集合：入口（传递）可达的每个模块出现且仅出现一次，
     * 不可达模块与未声明模块不会出现；环内模块以 LAZY 时机列出。
     */
    public EntryLoadSet loadSetFor(String entry) {
        lock.readLock().lock();
        try {
            LoadPlan plan = current;
            if (plan == null) {
                throw new NoSuchElementException("no plan committed yet");
            }
            if (!plan.entries().contains(entry)) {
                throw new NoSuchElementException("unknown entry " + entry);
            }
            Set<String> reached = reachabilityCache.getOrDefault(entry, Set.of());
            List<EntryLoadSet.EntryModule> modules = new ArrayList<>();
            for (String m : new TreeSet<>(reached)) {
                ModulePlacement p = plan.placements().get(m);
                if (p != null) {
                    modules.add(new EntryLoadSet.EntryModule(m, p.chunkId(), p.timing()));
                }
            }
            return new EntryLoadSet(entry, modules);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 仅供测试：对当前图做一次全量重算（不走增量缓存）。 */
    LoadPlan recomputeFullyForVerification() {
        lock.readLock().lock();
        try {
            return planner.plan(graph, current != null ? current.version() : 0L);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 图状态持久化 DTO。 */
    record GraphState(List<ModuleSpec> modules, Map<String, List<String>> imports, List<String> entries) {
    }
}
