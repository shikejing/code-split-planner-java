package com.github.shikejing.codesplitplannerjava.model;

/**
 * 单个模块在计划中的落位：所属分包与加载时机。
 * reason 记录导致该时机的触发条件（如预算降级），正常为首屏时为空串。
 */
public record ModulePlacement(String moduleId, String chunkId, LoadTiming timing, String reason) {
}
