package com.github.shikejing.codesplitplannerjava.config;

import com.github.shikejing.codesplitplannerjava.core.IncrementalPlanner;
import com.github.shikejing.codesplitplannerjava.core.PlanGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlannerProperties.class)
public class PlannerConfig {

    @Bean
    public PlanGenerator planGenerator(PlannerProperties properties) {
        return new PlanGenerator(properties);
    }

    @Bean
    public IncrementalPlanner incrementalPlanner(PlannerProperties properties) {
        return new IncrementalPlanner(properties);
    }
}
