package com.github.shikejing.codesplitplannerjava.core;

import com.github.shikejing.codesplitplannerjava.model.Diagnostic;

import java.util.List;

/**
 * Thrown when plan generation is rejected by policy (e.g. cycle strategy
 * REJECT). Carries structured diagnostics so the rejection is locatable.
 */
public class PlanRejectedException extends RuntimeException {

    private final transient List<Diagnostic> diagnostics;

    public PlanRejectedException(String message, List<Diagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
