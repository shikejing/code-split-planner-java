package com.github.shikejing.codesplitplannerjava.model;

/**
 * 模块未进入首屏加载集合的原因：
 * UNREACHABLE —— 从任何入口都不可达；
 * IN_CYCLE    —— 处于依赖环中，按降级策略处理。
 */
public enum SkipReason {
    UNREACHABLE,
    IN_CYCLE
}
