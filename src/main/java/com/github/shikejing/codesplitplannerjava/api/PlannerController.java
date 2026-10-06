package com.github.shikejing.codesplitplannerjava.api;

import com.github.shikejing.codesplitplannerjava.core.IncrementalPlanner;
import com.github.shikejing.codesplitplannerjava.core.PlanRejectedException;
import com.github.shikejing.codesplitplannerjava.model.EntryLoadSet;
import com.github.shikejing.codesplitplannerjava.model.GraphSnapshot;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.ModuleNode;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;
import com.github.shikejing.codesplitplannerjava.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST API for graph updates, plan queries, history and diffs.
 */
@RestController
@RequestMapping("/api/planner")
public class PlannerController {

    private final PlannerService service;

    public PlannerController(PlannerService service) {
        this.service = service;
    }

    // ---- graph mutation ----

    /** Replaces the whole graph in one shot and runs a full recompute. */
    @PutMapping("/graph")
    public LoadPlan replaceGraph(@RequestBody GraphRequest request) {
        GraphSnapshot graph = new GraphSnapshot();
        for (ModuleNode node : request.modules()) {
            graph = graph.withModule(node);
        }
        for (ImportEdge edge : request.imports()) {
            graph = graph.withImport(edge.from(), edge.to());
        }
        return service.replaceGraph(graph);
    }

    @PostMapping("/graph/modules")
    public IncrementalPlanner.IncrementalResult upsertModule(@RequestBody ModuleNode node) {
        return service.upsertModule(node);
    }

    @DeleteMapping("/graph/modules/{id}")
    public IncrementalPlanner.IncrementalResult removeModule(@PathVariable String id) {
        return service.removeModule(id);
    }

    @PostMapping("/graph/imports")
    public IncrementalPlanner.IncrementalResult addImport(@RequestBody ImportEdge edge) {
        return service.addImport(edge.from(), edge.to());
    }

    @DeleteMapping("/graph/imports")
    public IncrementalPlanner.IncrementalResult removeImport(@RequestBody ImportEdge edge) {
        return service.removeImport(edge.from(), edge.to());
    }

    // ---- plan queries ----

    @GetMapping("/plans/current")
    public LoadPlan currentPlan() {
        return service.currentPlan();
    }

    @GetMapping("/plans")
    public List<Long> versions() {
        return service.versions();
    }

    @GetMapping("/plans/{version}")
    public LoadPlan planAt(@PathVariable long version) {
        return service.planAt(version);
    }

    @GetMapping("/plans/diff")
    public PlanDiff diff(@RequestParam long from, @RequestParam long to) {
        return service.diff(from, to);
    }

    @GetMapping("/entries/{entryId}/load-set")
    public EntryLoadSet loadSet(@PathVariable String entryId) {
        return service.loadSetFor(entryId);
    }

    // ---- error mapping ----

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PlanRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(PlanRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", e.getMessage(), "diagnostics", e.diagnostics()));
    }

    // ---- request DTOs ----

    public record GraphRequest(List<ModuleNode> modules, List<ImportEdge> imports) {
    }

    public record ImportEdge(String from, String to) {
    }
}
