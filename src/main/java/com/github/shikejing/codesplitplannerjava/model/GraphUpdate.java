package com.github.shikejing.codesplitplannerjava.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 一批图变更操作，按顺序应用后整体提交为一个新版本。
 */
public record GraphUpdate(List<Op> ops) {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = GraphUpdate.UpsertModule.class, name = "upsertModule"),
            @JsonSubTypes.Type(value = GraphUpdate.RemoveModule.class, name = "removeModule"),
            @JsonSubTypes.Type(value = GraphUpdate.AddEntry.class, name = "addEntry"),
            @JsonSubTypes.Type(value = GraphUpdate.RemoveEntry.class, name = "removeEntry"),
            @JsonSubTypes.Type(value = GraphUpdate.AddImport.class, name = "addImport"),
            @JsonSubTypes.Type(value = GraphUpdate.RemoveImport.class, name = "removeImport")
    })
    public sealed interface Op {
    }

    /** 新增或覆盖一个模块。 */
    public record UpsertModule(ModuleSpec module) implements Op {
    }

    /** 删除一个模块及其全部导入关系。 */
    public record RemoveModule(String moduleId) implements Op {
    }

    /** 声明 from 为入口模块。 */
    public record AddEntry(String moduleId) implements Op {
    }

    /** 取消入口标记。 */
    public record RemoveEntry(String moduleId) implements Op {
    }

    /** 新增导入边：importer 导入 imported。 */
    public record AddImport(String importer, String imported) implements Op {
    }

    /** 删除导入边。 */
    public record RemoveImport(String importer, String imported) implements Op {
    }
}
