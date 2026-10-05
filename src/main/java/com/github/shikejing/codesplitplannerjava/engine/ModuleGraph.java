package com.github.shikejing.codesplitplannerjava.engine;

import com.github.shikejing.codesplitplannerjava.model.ModuleSpec;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 可变模块依赖图（仅由服务层在写锁内修改）。
 * 内部使用插入序容器，但所有对外快照均排序，保证确定性。
 */
public class ModuleGraph {

    private final Map<String, ModuleSpec> modules = new LinkedHashMap<>();
    private final Map<String, Set<String>> imports = new LinkedHashMap<>();
    private final Set<String> entries = new LinkedHashSet<>();

    public void upsertModule(ModuleSpec spec) {
        modules.put(spec.id(), spec);
        imports.computeIfAbsent(spec.id(), k -> new LinkedHashSet<>());
    }

    public void removeModule(String id) {
        modules.remove(id);
        imports.remove(id);
        entries.remove(id);
        for (Set<String> targets : imports.values()) {
            targets.remove(id);
        }
    }

    public void addEntry(String id) {
        entries.add(id);
    }

    public void removeEntry(String id) {
        entries.remove(id);
    }

    public void addImport(String importer, String imported) {
        imports.computeIfAbsent(importer, k -> new LinkedHashSet<>()).add(imported);
    }

    public void removeImport(String importer, String imported) {
        Set<String> targets = imports.get(importer);
        if (targets != null) {
            targets.remove(imported);
        }
    }

    public ModuleGraph copy() {
        ModuleGraph g = new ModuleGraph();
        g.modules.putAll(modules);
        imports.forEach((k, v) -> g.imports.put(k, new LinkedHashSet<>(v)));
        g.entries.addAll(entries);
        return g;
    }

    public Map<String, ModuleSpec> modules() {
        return modules;
    }

    public Map<String, Set<String>> imports() {
        return imports;
    }

    public Set<String> entries() {
        return entries;
    }
}
