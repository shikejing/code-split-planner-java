package com.github.shikejing.codesplitplannerjava.model;

/**
 * When a module is loaded, relative to the first screen.
 *
 * <ul>
 *   <li>{@link #EAGER} - shipped with the first screen.</li>
 *   <li>{@link #PREFETCH} - not on the critical path, but prefetched ahead of
 *       time because it is a direct dependency of an eager bundle.</li>
 *   <li>{@link #LAZY} - loaded on demand only.</li>
 *   <li>{@link #SKIPPED} - not loaded at all; see {@link SkipReason}.</li>
 * </ul>
 */
public enum LoadTiming {
    EAGER,
    PREFETCH,
    LAZY,
    SKIPPED
}
