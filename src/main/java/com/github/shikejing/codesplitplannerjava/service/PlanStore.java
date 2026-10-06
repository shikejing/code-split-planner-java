package com.github.shikejing.codesplitplannerjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.codesplitplannerjava.model.Bundle;
import com.github.shikejing.codesplitplannerjava.model.Demotion;
import com.github.shikejing.codesplitplannerjava.model.Diagnostic;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.LoadTiming;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import com.github.shikejing.codesplitplannerjava.model.SkipReason;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File-based persistence for committed plans and their graph snapshots, so
 * plans survive restarts and stay reproducible. Each committed version is
 * stored as a pair of JSON files ({@code plan-v<n>.json} and
 * {@code graph-v<n>.json}) written atomically via a temporary file plus move.
 */
public class PlanStore {

    private static final Pattern PLAN_FILE = Pattern.compile("plan-v(\\d+)\\.json");

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanStore(String storeDir) {
        this.dir = Path.of(storeDir);
    }

    /** Persists a committed plan together with the graph that produced it. */
    public void save(long version, GraphSnapshot graph, LoadPlan plan) {
        try {
            Files.createDirectories(dir);
            writeAtomic(dir.resolve("plan-v" + version + ".json"), StoredPlan.from(plan));
            writeAtomic(dir.resolve("graph-v" + version + ".json"), StoredGraph.from(graph));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist plan version " + version, e);
        }
    }

    /** All persisted versions, ascending. */
    public List<Long> versions() {
        List<Long> versions = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return versions;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Matcher m = PLAN_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    versions.add(Long.parseLong(m.group(1)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list plan store", e);
        }
        versions.sort(Long::compareTo);
        return versions;
    }

    public Optional<LoadPlan> loadPlan(long version) {
        Path file = dir.resolve("plan-v" + version + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), StoredPlan.class).toPlan());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load plan version " + version, e);
        }
    }

    public Optional<GraphSnapshot> loadGraph(long version) {
        Path file = dir.resolve("graph-v" + version + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), StoredGraph.class).toGraph());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load graph version " + version, e);
        }
    }

    /** The latest persisted version, or empty if the store is empty. */
    public Optional<Long> latestVersion() {
        List<Long> versions = versions();
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(versions.size() - 1));
    }

    private void writeAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** JSON representation of a {@link LoadPlan}. */
    public static class StoredPlan {
        public long version;
        public List<Bundle> bundles = List.of();
        public Map<String, String> moduleToBundle = Map.of();
        public Map<String, LoadTiming> moduleTiming = Map.of();
        public Map<String, SkipReason> skipReasons = Map.of();
        public List<Diagnostic> diagnostics = List.of();
        public List<Demotion> demotions = List.of();

        static StoredPlan from(LoadPlan plan) {
            StoredPlan sp = new StoredPlan();
            sp.version = plan.version();
            sp.bundles = plan.bundles();
            sp.moduleToBundle = new TreeMap<>(plan.moduleToBundle());
            sp.moduleTiming = new TreeMap<>(plan.moduleTiming());
            sp.skipReasons = new TreeMap<>(plan.skipReasons());
            sp.diagnostics = plan.diagnostics();
            sp.demotions = plan.demotions();
            return sp;
        }

        LoadPlan toPlan() {
            return new LoadPlan(version, bundles, moduleToBundle, moduleTiming, skipReasons,
                    diagnostics, demotions);
        }
    }

    /** JSON representation of a {@link GraphSnapshot}. */
    public static class StoredGraph {
        public List<ModuleNode> modules = List.of();
        public Map<String, List<String>> imports = Map.of();

        static StoredGraph from(GraphSnapshot graph) {
            StoredGraph sg = new StoredGraph();
            sg.modules = new ArrayList<>(graph.modules().values());
            Map<String, List<String>> imports = new TreeMap<>();
            for (Map.Entry<String, java.util.Set<String>> e : graph.imports().entrySet()) {
                imports.put(e.getKey(), List.copyOf(e.getValue()));
            }
            sg.imports = imports;
            return sg;
        }

        GraphSnapshot toGraph() {
            GraphSnapshot graph = new GraphSnapshot();
            for (ModuleNode node : modules) {
                graph = graph.withModule(node);
            }
            for (Map.Entry<String, List<String>> e : imports.entrySet()) {
                for (String to : e.getValue()) {
                    graph = graph.withImport(e.getKey(), to);
                }
            }
            return graph;
        }
    }
}
