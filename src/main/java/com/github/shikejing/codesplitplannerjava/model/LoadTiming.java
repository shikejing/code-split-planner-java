package com.github.shikejing.codesplitplannerjava.model;

/**
 * 加载时机：
 * EAGER   —— 随首屏立即加载；
 * PREFETCH —— 首屏之后提前预取；
 * LAZY    —— 按需懒加载。
 */
public enum LoadTiming {
    EAGER,
    PREFETCH,
    LAZY
}
