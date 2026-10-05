package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;
import java.util.Map;

/**
 * 两个版本计划之间的差异。
 */
public record PlanDiff(
        long fromVersion,
        long toVersion,
        List<String> addedModules,
        List<String> removedModules,
        Map<String, TimingChange> timingChanges,
        List<String> addedChunks,
        List<String> removedChunks) {

    public record TimingChange(LoadTiming from, LoadTiming to) {
    }
}
