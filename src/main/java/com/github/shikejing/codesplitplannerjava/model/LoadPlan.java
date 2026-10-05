package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一次生成得到的完整加载计划（不可变）。
 * version 单调递增；chunks 按 id 排序；placements 以模块 id 为键。
 */
public record LoadPlan(
        long version,
        List<Chunk> chunks,
        Map<String, ModulePlacement> placements,
        List<SkippedModule> skipped,
        List<String> entries) {

    public LoadPlan {
        chunks = List.copyOf(chunks);
        placements = Map.copyOf(placements);
        skipped = List.copyOf(skipped);
        entries = List.copyOf(entries);
    }
}
