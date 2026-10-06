package com.github.shikejing.codesplitplannerjava.model;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable snapshot of the module dependency graph. All mutating operations
 * return a new snapshot; internal maps are sorted so iteration order never
 * depends on insertion order.
 */
public final class GraphSnapshot {

    private final TreeMap<String, ModuleNode> modules;
    /** module id -> ids of modules it imports (only known module ids). */
    private final TreeMap<String, TreeSet<String>> imports;

    public GraphSnapshot() {
        this(new TreeMap<>(), new TreeMap<>());
    }

    private GraphSnapshot(TreeMap<String, ModuleNode> modules,
                          TreeMap<String, TreeSet<String>> imports) {
        this.modules = modules;
        this.imports = imports;
    }

    public Map<String, ModuleNode> modules() {
        return Collections.unmodifiableMap(modules);
    }

    public Map<String, Set<String>> imports() {
        return Collections.unmodifiableMap(imports);
    }

    public Set<String> entries() {
        TreeSet<String> result = new TreeSet<>();
        for (ModuleNode node : modules.values()) {
            if (node.entry()) {
                result.add(node.id());
            }
        }
        return result;
    }

    public GraphSnapshot withModule(ModuleNode node) {
        TreeMap<String, ModuleNode> newModules = new TreeMap<>(modules);
        newModules.put(node.id(), node);
        TreeMap<String, TreeSet<String>> newImports = copyImports();
        newImports.putIfAbsent(node.id(), new TreeSet<>());
        return new GraphSnapshot(newModules, newImports);
    }

    public GraphSnapshot withoutModule(String id) {
        TreeMap<String, ModuleNode> newModules = new TreeMap<>(modules);
        newModules.remove(id);
        TreeMap<String, TreeSet<String>> newImports = copyImports();
        newImports.remove(id);
        for (TreeSet<String> targets : newImports.values()) {
            targets.remove(id);
        }
        return new GraphSnapshot(newModules, newImports);
    }

    public GraphSnapshot withImport(String from, String to) {
        TreeMap<String, TreeSet<String>> newImports = copyImports();
        newImports.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
        return new GraphSnapshot(new TreeMap<>(modules), newImports);
    }

    public GraphSnapshot withoutImport(String from, String to) {
        TreeMap<String, TreeSet<String>> newImports = copyImports();
        TreeSet<String> targets = newImports.get(from);
        if (targets != null) {
            targets.remove(to);
        }
        return new GraphSnapshot(new TreeMap<>(modules), newImports);
    }

    private TreeMap<String, TreeSet<String>> copyImports() {
        TreeMap<String, TreeSet<String>> copy = new TreeMap<>();
        for (Map.Entry<String, TreeSet<String>> e : imports.entrySet()) {
            copy.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return copy;
    }
}
