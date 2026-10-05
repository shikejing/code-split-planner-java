package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;

/**
 * 按入口查询得到的实际加载集合：每个模块及其加载时机，按模块 id 排序。
 */
public record EntryLoadSet(String entry, List<EntryModule> modules) {

    public record EntryModule(String moduleId, String chunkId, LoadTiming timing) {
    }
}
