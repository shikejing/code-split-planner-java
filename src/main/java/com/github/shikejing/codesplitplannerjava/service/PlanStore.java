package com.github.shikejing.codesplitplannerjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.codesplitplannerjava.model.LoadPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划持久化：每个已提交计划以 JSON 文件落盘（先写临时文件再原子改名），
 * 重启后按版本号升序恢复，保证已提交计划不丢失且可复现。
 */
public class PlanStore {

    private static final Pattern FILE_NAME = Pattern.compile("plan-(\\d+)\\.json");

    private final Path dir;
    private final ObjectMapper mapper;

    public PlanStore(String dataDir) {
        this.dir = Path.of(dataDir);
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create plan store dir " + dir, e);
        }
    }

    public synchronized void save(LoadPlan plan) {
        Path tmp = dir.resolve("plan-" + plan.version() + ".json.tmp");
        Path target = fileFor(plan.version());
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), plan);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist plan version " + plan.version(), e);
        }
    }

    public synchronized Optional<LoadPlan> load(long version) {
        Path file = fileFor(version);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), LoadPlan.class));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load plan version " + version, e);
        }
    }

    /** 按版本号升序返回全部已持久化计划。 */
    public synchronized List<LoadPlan> loadAll() {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list plan store dir " + dir, e);
        }
        files.sort(Comparator.comparingLong(p -> versionOf(p.getFileName().toString())));
        List<LoadPlan> plans = new ArrayList<>();
        for (Path file : files) {
            try {
                plans.add(mapper.readValue(file.toFile(), LoadPlan.class));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot load plan file " + file, e);
            }
        }
        return plans;
    }

    private Path fileFor(long version) {
        return dir.resolve("plan-" + version + ".json");
    }

    private static long versionOf(String fileName) {
        Matcher m = FILE_NAME.matcher(fileName);
        if (!m.matches()) {
            throw new IllegalArgumentException(fileName);
        }
        return Long.parseLong(m.group(1));
    }
}
