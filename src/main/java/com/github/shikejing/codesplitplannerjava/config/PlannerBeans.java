package com.github.shikejing.codesplitplannerjava.config;

import com.github.shikejing.codesplitplannerjava.engine.DeterministicPlanner;
import com.github.shikejing.codesplitplannerjava.service.PlanStore;
import com.github.shikejing.codesplitplannerjava.service.PlannerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlannerBeans {

    @Bean
    public DeterministicPlanner deterministicPlanner(PlannerProperties properties) {
        return new DeterministicPlanner(properties);
    }

    @Bean
    public PlanStore planStore(PlannerProperties properties) {
        return new PlanStore(properties.getDataDir());
    }

    @Bean
    public PlannerService plannerService(DeterministicPlanner planner, PlanStore planStore,
                                         PlannerProperties properties) {
        return new PlannerService(planner, planStore, properties.getDataDir());
    }
}
