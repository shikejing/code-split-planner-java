package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;

/**
 * 一个分包。id 由成员确定性推导；members 按模块 id 字典序排列。
 */
public record Chunk(String id, LoadTiming timing, List<String> members) {
    public Chunk {
        members = List.copyOf(members);
    }
}
