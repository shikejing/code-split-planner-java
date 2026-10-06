package com.github.shikejing.codesplitplannerjava.model;

import java.util.List;
import java.util.Map;

/**
 * Answer to "what does entry X actually load". Modules are grouped by timing;
 * each module appears exactly once regardless of how many times it is
 * (transitively) imported.
 *
 * @param entry        the queried entry module id
 * @param planVersion  version of the plan this answer was derived from
 * @param eager        module ids loaded with the first screen, sorted
 * @param prefetch     module ids prefetched, sorted
 * @param lazy         module ids loaded on demand, sorted
 * @param moduleToBundle module id -> bundle id for every listed module
 */
public record EntryLoadSet(String entry, long planVersion,
                           List<String> eager, List<String> prefetch, List<String> lazy,
                           Map<String, String> moduleToBundle) {
}
