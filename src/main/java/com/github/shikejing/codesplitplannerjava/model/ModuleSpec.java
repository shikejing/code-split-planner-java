package com.github.shikejing.codesplitplannerjava.model;

/**
 * 一个模块的定义。id 全局唯一，sizeBytes 用于体积预算。
 */
public record ModuleSpec(String id, long sizeBytes) {
    public ModuleSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("module id must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
