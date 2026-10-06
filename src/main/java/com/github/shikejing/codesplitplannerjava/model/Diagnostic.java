package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;

/**
 * A structured, locatable finding produced while generating a plan.
 *
 * @param code      stable machine-readable code, e.g. {@code CYCLE_DETECTED}
 * @param message   human readable explanation
 * @param moduleIds modules the finding refers to, sorted lexicographically
 */
public record Diagnostic(String code, String message, List<String> moduleIds) {
}
