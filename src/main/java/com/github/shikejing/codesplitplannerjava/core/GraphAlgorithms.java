package com.github.shikejing.codesplitplannerjava.core;

import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic graph algorithms. Every traversal iterates nodes and edges in
 * lexicographic order so results never depend on map iteration order.
 */
public final class GraphAlgorithms {

    private GraphAlgorithms() {
    }

    /** Modules reachable from any entry (entries included). */
    public static Set<String> reachableFromEntries(GraphSnapshot graph) {
        return reachableFrom(graph, graph.entries());
    }

    /** All modules reachable from the given starts (starts included). */
    public static Set<String> reachableFrom(GraphSnapshot graph, Set<String> starts) {
        Map<String, Set<String>> imports = graph.imports();
        Set<String> visited = new TreeSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String start : new TreeSet<>(starts)) {
            if (graph.modules().containsKey(start) && visited.add(start)) {
                stack.push(start);
            }
        }
        while (!stack.isEmpty()) {
            String current = stack.pop();
            Set<String> targets = imports.getOrDefault(current, Set.of());
            // push in reverse sorted order so smaller ids are expanded first
            for (String target : new TreeSet<>(targets).descendingSet()) {
                if (graph.modules().containsKey(target) && visited.add(target)) {
                    stack.push(target);
                }
            }
        }
        return visited;
    }

    /**
     * Strongly connected components of the subgraph induced by {@code nodes},
     * Tarjan style, computed with sorted adjacency and sorted node iteration.
     * Each component is a sorted list; the component list itself is sorted by
     * its first member, so the output is fully deterministic.
     */
    public static List<List<String>> stronglyConnectedComponents(GraphSnapshot graph,
                                                                 Set<String> nodes) {
        Map<String, Set<String>> imports = graph.imports();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new TreeSet<>();
        List<List<String>> components = new ArrayList<>();
        int[] counter = {0};

        // Iterative Tarjan to avoid stack overflow on large graphs.
        for (String root : new TreeSet<>(nodes)) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[]{root, null});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String v = (String) frame[0];
                if (frame[1] == null) {
                    index.put(v, counter[0]);
                    lowLink.put(v, counter[0]);
                    counter[0]++;
                    stack.push(v);
                    onStack.add(v);
                    frame[1] = new TreeSet<>(imports.getOrDefault(v, Set.of()))
                            .descendingSet().iterator();
                }
                @SuppressWarnings("unchecked")
                java.util.Iterator<String> it = (java.util.Iterator<String>) frame[1];
                boolean descended = false;
                while (it.hasNext()) {
                    String w = it.next();
                    if (!nodes.contains(w) || !graph.modules().containsKey(w)) {
                        continue;
                    }
                    if (!index.containsKey(w)) {
                        work.push(new Object[]{w, null});
                        descended = true;
                        break;
                    } else if (onStack.contains(w)) {
                        lowLink.put(v, Math.min(lowLink.get(v), index.get(w)));
                    }
                }
                if (descended) {
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = (String) work.peek()[0];
                    lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(v)));
                }
                if (lowLink.get(v).equals(index.get(v))) {
                    List<String> component = new ArrayList<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        component.add(w);
                    } while (!w.equals(v));
                    component.sort(String::compareTo);
                    components.add(component);
                }
            }
        }
        components.sort((a, b) -> a.get(0).compareTo(b.get(0)));
        return components;
    }

    /**
     * For every reachable module, the sorted set of entries that can reach it.
     * Computed by one traversal per entry; the per-module sets are sorted.
     */
    public static Map<String, Set<String>> entrySets(GraphSnapshot graph) {
        Map<String, Set<String>> result = new TreeMap<>();
        for (String entry : graph.entries()) {
            for (String reachable : reachableFrom(graph, Set.of(entry))) {
                result.computeIfAbsent(reachable, k -> new TreeSet<>()).add(entry);
            }
        }
        return result;
    }

    /**
     * BFS distance (in edges) from the nearest entry for every reachable
     * module; entries have distance 0. Frontier is processed in sorted order.
     */
    public static Map<String, Integer> distancesFromEntries(GraphSnapshot graph) {
        Map<String, Set<String>> imports = graph.imports();
        Map<String, Integer> distance = new TreeMap<>();
        List<String> frontier = new ArrayList<>();
        for (String entry : graph.entries()) {
            if (graph.modules().containsKey(entry) && !distance.containsKey(entry)) {
                distance.put(entry, 0);
                frontier.add(entry);
            }
        }
        frontier.sort(String::compareTo);
        int current = 0;
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String node : frontier) {
                for (String target : new TreeSet<>(imports.getOrDefault(node, Set.of()))) {
                    if (graph.modules().containsKey(target) && !distance.containsKey(target)) {
                        distance.put(target, current + 1);
                        next.add(target);
                    }
                }
            }
            next.sort(String::compareTo);
            frontier = next;
            current++;
        }
        return distance;
    }

    /**
     * Modules that can reach any of {@code targets} by following import edges
     * backwards (reverse transitive closure, targets included).
     */
    public static Set<String> dependentsOf(GraphSnapshot graph, Set<String> targets) {
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : graph.imports().entrySet()) {
            for (String to : e.getValue()) {
                reverse.computeIfAbsent(to, k -> new TreeSet<>()).add(e.getKey());
            }
        }
        Set<String> visited = new TreeSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String t : new TreeSet<>(targets)) {
            if (visited.add(t)) {
                stack.push(t);
            }
        }
        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String parent : new TreeSet<>(reverse.getOrDefault(current, Set.of()))
                    .descendingSet()) {
                if (visited.add(parent)) {
                    stack.push(parent);
                }
            }
        }
        return visited;
    }
}
