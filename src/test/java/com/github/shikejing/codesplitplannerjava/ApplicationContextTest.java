package com.github.shikejing.codesplitplannerjava;

import com.github.shikejing.codesplitplannerjava.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(properties = "planner.store-dir=target/test-store-context")
class ApplicationContextTest {

    @Autowired
    private PlannerService plannerService;

    @Test
    void contextWiresPlannerService() {
        assertNotNull(plannerService);
        assertNotNull(plannerService.currentPlan());
    }
}
