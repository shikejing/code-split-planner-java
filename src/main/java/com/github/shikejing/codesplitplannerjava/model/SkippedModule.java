package com.github.shikejing.codesplitplannerjava.model;

/**
 * 被跳过（不计入首屏集合）的模块及原因。
 */
public record SkippedModule(String moduleId, SkipReason reason) {
}
