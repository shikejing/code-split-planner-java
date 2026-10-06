package com.github.shikejing.codesplitplannerjava.model;

/**
 * Why a module is excluded from any load set. The reasons are deliberately
 * distinct so callers can tell them apart.
 */
public enum SkipReason {
    /** Not reachable from any entry module. */
    UNREACHABLE,
    /** Referenced by an import edge but not present in the module set. */
    MISSING_MODULE
}
