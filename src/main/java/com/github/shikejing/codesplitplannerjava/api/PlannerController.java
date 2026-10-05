package com.github.shikejing.codesplitplannerjava.api;

import com.github.shikejing.codesplitplannerjava.model.EntryLoadSet;
import com.github.shikejing.codesplitplannerjava.model.GraphUpdate;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;
import com.github.shikejing.codesplitplannerjava.model.PlanDiff;
import com.github.shikejing.codesplitplannerjava.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 分包与加载策略 REST 接口。
 */
@RestController
@RequestMapping("/api/planner")
public class PlannerController {

    private final PlannerService service;

    public PlannerController(PlannerService service) {
        this.service = service;
    }

    /** 提交一批图变更，返回新版本号与受影响模块范围。 */
    @PostMapping("/updates")
    public UpdateResult applyUpdate(@RequestBody GraphUpdate update) {
        Set<String> affected = service.applyUpdate(update);
        long version = service.currentPlan().map(LoadPlan::version).orElse(0L);
        return new UpdateResult(version, List.copyOf(affected));
    }

    /** 当前已提交计划。 */
    @GetMapping("/plans/current")
    public ResponseEntity<LoadPlan> current() {
        return service.currentPlan().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 历史版本计划。 */
    @GetMapping("/plans/{version}")
    public LoadPlan planAt(@PathVariable long version) {
        return service.planAt(version)
                .orElseThrow(() -> new NoSuchElementException("no plan version " + version));
    }

    /** 全部版本号。 */
    @GetMapping("/plans")
    public List<Long> versions() {
        return service.versions();
    }

    /** 两版本计划差异。 */
    @GetMapping("/diff")
    public PlanDiff diff(@RequestParam long from, @RequestParam long to) {
        return service.diff(from, to);
    }

    /** 按入口查询实际加载集合。 */
    @GetMapping("/entries/{entry}/load-set")
    public EntryLoadSet loadSet(@PathVariable String entry) {
        return service.loadSetFor(entry);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    public record UpdateResult(long version, List<String> affectedModules) {
    }
}
