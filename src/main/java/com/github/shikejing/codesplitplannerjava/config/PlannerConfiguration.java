package com.github.shikejing.codesplitplannerjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlannerProperties.class)
public class PlannerConfiguration {
}
