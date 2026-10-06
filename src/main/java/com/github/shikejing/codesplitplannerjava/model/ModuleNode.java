package com.github.shikejing.codesplitplannerjava.model;

/**
 * A module in the dependency graph.
 *
 * @param id        unique module identifier, compared lexicographically for all
 *                  deterministic ordering decisions
 * @param sizeBytes module size in bytes, used by the first-screen byte budget
 * @param entry     whether this module is an entry (first-screen root)
 */
public record ModuleNode(String id, long sizeBytes, boolean entry) {
}
