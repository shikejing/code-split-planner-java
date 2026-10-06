package com.github.shikejing.codesplitplannerjava.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable, committed code-split and load plan. Instances are only ever
 * published atomically; readers therefore always observe a complete plan.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class LoadPlan {

    private final long version;
    /** Bundles sorted by bundle id. */
    private final List<Bundle> bundles;
    /** module id -> bundle id, for modules that are packed. */
    private final Map<String, String> moduleToBundle;
    /** module id -> load timing, covering packed and skipped modules. */
    private final Map<String, LoadTiming> moduleTiming;
    /** module id -> skip reason, only for SKIPPED modules. */
    private final Map<String, SkipReason> skipReasons;
    private final List<Diagnostic> diagnostics;
    private final List<Demotion> demotions;

    public LoadPlan(long version, List<Bundle> bundles, Map<String, String> moduleToBundle,
                    Map<String, LoadTiming> moduleTiming, Map<String, SkipReason> skipReasons,
                    List<Diagnostic> diagnostics, List<Demotion> demotions) {
        this.version = version;
        this.bundles = List.copyOf(bundles);
        this.moduleToBundle = Map.copyOf(moduleToBundle);
        this.moduleTiming = Map.copyOf(moduleTiming);
        this.skipReasons = Map.copyOf(skipReasons);
        this.diagnostics = List.copyOf(diagnostics);
        this.demotions = List.copyOf(demotions);
    }

    /** An empty plan used before the first generation. */
    public static LoadPlan empty() {
        return new LoadPlan(0, List.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    public long version() {
        return version;
    }

    public List<Bundle> bundles() {
        return bundles;
    }

    public Map<String, String> moduleToBundle() {
        return moduleToBundle;
    }

    public Map<String, LoadTiming> moduleTiming() {
        return moduleTiming;
    }

    public Map<String, SkipReason> skipReasons() {
        return skipReasons;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public List<Demotion> demotions() {
        return demotions;
    }

    /**
     * Content equality ignoring the version number, used to verify that
     * incremental recomputation matches a full recompute.
     */
    public boolean sameContent(LoadPlan other) {
        return bundles.equals(other.bundles)
                && new TreeMap<>(moduleToBundle).equals(new TreeMap<>(other.moduleToBundle))
                && new TreeMap<>(moduleTiming).equals(new TreeMap<>(other.moduleTiming))
                && new TreeMap<>(skipReasons).equals(new TreeMap<>(other.skipReasons))
                && diagnostics.equals(other.diagnostics)
                && demotions.equals(other.demotions);
    }
}
