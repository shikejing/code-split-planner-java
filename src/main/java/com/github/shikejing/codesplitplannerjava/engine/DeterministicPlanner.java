package com.github.shikejing.codesplitplannerjava.engine;

import com.github.shikejing.codesplitplannerjava.config.PlannerProperties;
import com.github.shikejing.codesplitplannerjava.model.Chunk;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModulePlacement;
import com.github.shikejing.codesplitplannerjava.model.ModuleSpec;
import com.github.shikejing.codesplitplannerjava.model.SkipReason;
import com.github.shikejing.codesplitplannerjava.model.SkippedModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 确定性计划生成器。
 *
 * <p>确定性保证：所有遍历都先按模块 id / 入口 id 字典序排序，
 * 不依赖任何 Map/Set 的迭代顺序；分包 id 由成员集合的哈希推导，
 * 因此同一份依赖图在任意输入顺序与任意并发度下产出逐项一致的计划。
 *
 * <p>算法步骤（详见 PLANNING.md）：
 * <ol>
 *   <li>从各入口做可达性遍历，不可达模块记为 UNREACHABLE 跳过；</li>
 *   <li>在可达子图上用 Tarjan 找强连通分量，依赖环整体降级为 LAZY 包，
 *       成员记为 IN_CYCLE，不会让生成失败；</li>
 *   <li>非环可达模块按“能到达它的入口集合”归组，同组同包，
 *       被多个入口共享的模块只打包一次；</li>
 *   <li>按预算把超限部分整包降级为 LAZY，降级原因写到每个模块落位上。</li>
 * </ol>
 */
public class DeterministicPlanner {

    private final PlannerProperties properties;

    public DeterministicPlanner(PlannerProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建排序邻接表：模块与导入目标均按字典序，dangling import
     * （指向不存在模块的边）被忽略。
     */
    public static Map<String, List<String>> sortedAdjacency(ModuleGraph graph) {
        Map<String, List<String>> adjacency = new TreeMap<>();
        for (String id : graph.modules().keySet()) {
            Set<String> raw = graph.imports().getOrDefault(id, Set.of());
            adjacency.put(id, raw.stream().filter(graph.modules()::containsKey).sorted().toList());
        }
        return adjacency;
    }

    /** 图中实际生效的入口（存在对应模块），按字典序。 */
    public static List<String> sortedEntries(ModuleGraph graph) {
        return graph.entries().stream()
                .filter(graph.modules()::containsKey)
                .sorted()
                .toList();
    }

    /** 从 entry 出发的可达模块集合（含 entry 自身），结果按字典序。 */
    public Set<String> reachable(String entry, Map<String, List<String>> adjacency) {
        return reachableFrom(entry, adjacency);
    }

    /**
     * 对整张图做全量计算，生成指定版本号的计划。
     */
    public LoadPlan plan(ModuleGraph graph, long version) {
        Map<String, List<String>> adjacency = sortedAdjacency(graph);
        Map<String, Set<String>> perEntry = new TreeMap<>();
        for (String entry : sortedEntries(graph)) {
            perEntry.put(entry, reachableFrom(entry, adjacency));
        }
        return planWithReachability(graph, version, perEntry);
    }

    /**
     * 用调用方提供的逐入口可达集合组装计划（增量重算入口：
     * 未受影响的入口可复用缓存的可达集合，结果与全量重算一致）。
     * perEntry 必须覆盖当前全部生效入口。
     */
    public LoadPlan planWithReachability(ModuleGraph graph, long version,
                                         Map<String, Set<String>> perEntry) {
        // 1. 规范化输入：全部排序。
        Map<String, ModuleSpec> modules = new TreeMap<>(graph.modules());
        Map<String, List<String>> adjacency = sortedAdjacency(graph);
        List<String> entries = sortedEntries(graph);

        // 2. 可达性：ownerSets[m] = 能到达 m 的入口集合。
        Map<String, Set<String>> ownerSets = new TreeMap<>();
        for (String entry : entries) {
            Set<String> reached = perEntry.get(entry);
            if (reached == null) {
                throw new IllegalArgumentException("missing reachability for entry " + entry);
            }
            for (String m : reached) {
                ownerSets.computeIfAbsent(m, k -> new TreeSet<>()).add(entry);
            }
        }

        List<SkippedModule> skipped = new ArrayList<>();
        for (String id : modules.keySet()) {
            if (!ownerSets.containsKey(id)) {
                skipped.add(new SkippedModule(id, SkipReason.UNREACHABLE));
            }
        }

        // 3. 依赖环检测（仅在可达子图上）。
        List<Set<String>> cycles = findCycles(ownerSets.keySet(), adjacency);
        Set<String> cycleMembers = new TreeSet<>();
        for (Set<String> cycle : cycles) {
            cycleMembers.addAll(cycle);
        }

        // 4. 分包：环内成员每环一包（LAZY）；其余按入口集合归组。
        List<ChunkDraft> drafts = new ArrayList<>();
        for (Set<String> cycle : cycles) {
            List<String> members = new ArrayList<>(cycle);
            drafts.add(new ChunkDraft(members, LoadTiming.LAZY,
                    "cycle: dependency cycle among " + members));
        }
        Map<List<String>, List<String>> groups = new TreeMap<>( (a, b) -> {
            int c = Integer.compare(a.size(), b.size());
            if (c != 0) {
                return c;
            }
            for (int i = 0; i < a.size(); i++) {
                c = a.get(i).compareTo(b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        for (Map.Entry<String, Set<String>> e : ownerSets.entrySet()) {
            if (cycleMembers.contains(e.getKey())) {
                continue;
            }
            List<String> key = List.copyOf(e.getValue());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<List<String>, List<String>> g : groups.entrySet()) {
            List<String> members = g.getValue(); // ownerSets 有序，成员天然按 id 升序
            boolean shared = g.getKey().size() > 1;
            LoadTiming timing = shared && properties.isSharedChunkPrefetch()
                    ? LoadTiming.PREFETCH
                    : LoadTiming.EAGER;
            String reason = shared
                    ? "shared: reachable from entries " + g.getKey()
                    : "entry-private: reachable only from entry " + g.getKey().get(0);
            drafts.add(new ChunkDraft(members, timing, reason));
        }

        // 5. 预算约束：超限则整包降级为 LAZY（含入口模块的包不降级，保证入口可启动）。
        applyBudget(drafts, modules, entries);

        // 6. 组装不可变计划。
        List<Chunk> chunks = new ArrayList<>();
        Map<String, ModulePlacement> placements = new TreeMap<>();
        for (ChunkDraft draft : drafts) {
            String chunkId = chunkId(draft.members());
            chunks.add(new Chunk(chunkId, draft.timing(), draft.members()));
            for (String m : draft.members()) {
                placements.put(m, new ModulePlacement(m, chunkId, draft.timing(), draft.reason()));
            }
            if (draft.timing() == LoadTiming.LAZY && draft.reason().startsWith("cycle:")) {
                for (String m : draft.members()) {
                    skipped.add(new SkippedModule(m, SkipReason.IN_CYCLE));
                }
            }
        }
        chunks.sort((a, b) -> a.id().compareTo(b.id()));
        skipped.sort((a, b) -> {
            int c = a.moduleId().compareTo(b.moduleId());
            return c != 0 ? c : a.reason().compareTo(b.reason());
        });
        return new LoadPlan(version, chunks, placements, skipped, entries);
    }

    /** 从 entry 出发的可达模块集合（含 entry 自身），迭代式 DFS，邻接已排序。 */
    private Set<String> reachableFrom(String entry, Map<String, List<String>> adjacency) {
        Set<String> reached = new TreeSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(entry);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!reached.add(cur)) {
                continue;
            }
            List<String> targets = adjacency.getOrDefault(cur, List.of());
            for (int i = targets.size() - 1; i >= 0; i--) {
                stack.push(targets.get(i));
            }
        }
        return reached;
    }

    /** Tarjan 强连通分量，返回所有大小>1 或带自环的分量（成员各自排序）。 */
    private List<Set<String>> findCycles(Set<String> scope, Map<String, List<String>> adjacency) {
        Tarjan tarjan = new Tarjan(adjacency);
        for (String id : new TreeSet<>(scope)) {
            tarjan.visit(id);
        }
        List<Set<String>> cycles = new ArrayList<>();
        for (List<String> scc : tarjan.components()) {
            if (scc.size() > 1) {
                cycles.add(new TreeSet<>(scc));
            } else {
                String only = scc.get(0);
                if (adjacency.getOrDefault(only, List.of()).contains(only)) {
                    cycles.add(new TreeSet<>(scc));
                }
            }
        }
        return cycles;
    }

    /**
     * 预算降级策略：统计 EAGER 模块的数量与总体积，超限时按
     * “模块体积降序、模块 id 升序”依次把候选模块（非入口的 EAGER 模块）
     * 从原包中拆出、单独形成 LAZY 包，直到满足预算或无候选。
     * 入口模块永不降级；若仅剩入口仍超预算，保持现状（文档说明的下限行为）。
     * 每个被降级模块的 reason 记录触发条件（哪条预算、当前值与上限）。
     */
    private void applyBudget(List<ChunkDraft> drafts, Map<String, ModuleSpec> modules, List<String> entries) {
        Set<String> entrySet = new TreeSet<>(entries);
        while (true) {
            int eagerCount = 0;
            long eagerBytes = 0;
            for (ChunkDraft d : drafts) {
                if (d.timing() == LoadTiming.EAGER) {
                    eagerCount += d.members().size();
                    for (String m : d.members()) {
                        eagerBytes += modules.get(m).sizeBytes();
                    }
                }
            }
            boolean countExceeded = properties.getBudgetMaxModules() > 0
                    && eagerCount > properties.getBudgetMaxModules();
            boolean bytesExceeded = properties.getBudgetMaxBytes() > 0
                    && eagerBytes > properties.getBudgetMaxBytes();
            if (!countExceeded && !bytesExceeded) {
                return;
            }
            // 候选：非入口的 EAGER 模块，按体积降序、id 升序取第一个。
            String victim = null;
            long victimBytes = -1;
            for (ChunkDraft d : drafts) {
                if (d.timing() != LoadTiming.EAGER) {
                    continue;
                }
                for (String m : d.members()) {
                    if (entrySet.contains(m)) {
                        continue;
                    }
                    long size = modules.get(m).sizeBytes();
                    if (victim == null || size > victimBytes
                            || (size == victimBytes && m.compareTo(victim) < 0)) {
                        victim = m;
                        victimBytes = size;
                    }
                }
            }
            if (victim == null) {
                return;
            }
            StringBuilder trigger = new StringBuilder("budget-demoted:");
            if (countExceeded) {
                trigger.append(" eagerModules=").append(eagerCount)
                        .append(">maxModules=").append(properties.getBudgetMaxModules());
            }
            if (bytesExceeded) {
                trigger.append(" eagerBytes=").append(eagerBytes)
                        .append(">maxBytes=").append(properties.getBudgetMaxBytes());
            }
            // 从原包拆出 victim，单独成 LAZY 包；原包空了则移除。
            for (int i = 0; i < drafts.size(); i++) {
                ChunkDraft d = drafts.get(i);
                if (d.members().contains(victim)) {
                    List<String> remaining = new ArrayList<>(d.members());
                    remaining.remove(victim);
                    if (remaining.isEmpty()) {
                        drafts.remove(i);
                    } else {
                        drafts.set(i, new ChunkDraft(remaining, d.timing(), d.reason()));
                    }
                    break;
                }
            }
            drafts.add(new ChunkDraft(List.of(victim), LoadTiming.LAZY,
                    "entry-private/shared module; " + trigger));
        }
    }

    /** 分包 id：成员 id 排序后拼接的 SHA-256 前 16 位，与输入顺序无关。 */
    static String chunkId(List<String> sortedMembers) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join(",", sortedMembers).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("chunk-");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 分包草稿：组装前可被预算策略降级。 */
    private static final class ChunkDraft {
        private final List<String> members;
        private LoadTiming timing;
        private String reason;

        ChunkDraft(List<String> members, LoadTiming timing, String reason) {
            this.members = members;
            this.timing = timing;
            this.reason = reason;
        }

        List<String> members() {
            return members;
        }

        LoadTiming timing() {
            return timing;
        }

        String reason() {
            return reason;
        }
    }

    /** 迭代式 Tarjan，按字典序访问保证分量发现顺序确定。 */
    private static final class Tarjan {
        private final Map<String, List<String>> adjacency;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowlink = new HashMap<>();
        private final Set<String> onStack = new java.util.HashSet<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final List<List<String>> components = new ArrayList<>();
        private int next = 0;

        Tarjan(Map<String, List<String>> adjacency) {
            this.adjacency = adjacency;
        }

        List<List<String>> components() {
            return components;
        }

        void visit(String root) {
            if (index.containsKey(root)) {
                return;
            }
            // 显式栈模拟递归，避免深图栈溢出。
            Deque<Object[]> frames = new ArrayDeque<>();
            index.put(root, next);
            lowlink.put(root, next);
            next++;
            stack.push(root);
            onStack.add(root);
            frames.push(new Object[]{root, 0});
            while (!frames.isEmpty()) {
                Object[] frame = frames.peek();
                String v = (String) frame[0];
                int i = (Integer) frame[1];
                List<String> targets = adjacency.getOrDefault(v, List.of());
                if (i < targets.size()) {
                    frame[1] = i + 1;
                    String w = targets.get(i);
                    if (!index.containsKey(w)) {
                        index.put(w, next);
                        lowlink.put(w, next);
                        next++;
                        stack.push(w);
                        onStack.add(w);
                        frames.push(new Object[]{w, 0});
                    } else if (onStack.contains(w)) {
                        lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
                    }
                } else {
                    frames.pop();
                    if (lowlink.get(v).equals(index.get(v))) {
                        List<String> scc = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.remove(w);
                            scc.add(w);
                        } while (!w.equals(v));
                        scc.sort(String::compareTo);
                        components.add(scc);
                    }
                    if (!frames.isEmpty()) {
                        String parent = (String) frames.peek()[0];
                        lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(v)));
                    }
                }
            }
        }
    }
}
