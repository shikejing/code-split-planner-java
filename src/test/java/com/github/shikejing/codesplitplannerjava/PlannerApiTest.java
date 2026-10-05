package com.github.shikejing.codesplitplannerjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlannerApiTest {

    @TempDir
    static java.nio.file.Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("planner.data-dir", () -> tempDir.resolve("plans").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void fullFlowOverRest() throws Exception {
        String update = """
                {"ops":[
                  {"type":"upsertModule","module":{"id":"entryA","sizeBytes":100}},
                  {"type":"upsertModule","module":{"id":"entryB","sizeBytes":100}},
                  {"type":"upsertModule","module":{"id":"common","sizeBytes":50}},
                  {"type":"addImport","importer":"entryA","imported":"common"},
                  {"type":"addImport","importer":"entryB","imported":"common"},
                  {"type":"addEntry","moduleId":"entryA"},
                  {"type":"addEntry","moduleId":"entryB"}
                ]}""";
        mvc.perform(post("/api/planner/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/planner/plans/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.placements.common.timing").value("EAGER"));

        mvc.perform(get("/api/planner/plans")).andExpect(jsonPath("$[0]").value(1));
        mvc.perform(get("/api/planner/plans/1")).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/planner/plans/99")).andExpect(status().isNotFound());

        mvc.perform(get("/api/planner/entries/entryA/load-set"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.length()").value(2));
        mvc.perform(get("/api/planner/entries/ghost/load-set"))
                .andExpect(status().isNotFound());

        // 第二次提交后做版本对比
        String update2 = """
                {"ops":[
                  {"type":"upsertModule","module":{"id":"extra","sizeBytes":5}},
                  {"type":"addImport","importer":"entryA","imported":"extra"}
                ]}""";
        mvc.perform(post("/api/planner/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(update2))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/planner/diff?from=1&to=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedModules[0]").value("extra"));
    }
}
