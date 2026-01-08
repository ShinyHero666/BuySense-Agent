package com.buysense.evaluation;

import com.buysense.evaluation.EvaluationCase.JudgeRubric;
import com.buysense.evaluation.EvaluationSuite.Partition;
import com.buysense.evaluation.EvaluationSuite.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;

/**
 * Loads public benchmark tasks and private grading contracts from separate resources.
 * Target models receive only task prompts; gold constraints remain inside the grader.
 */
public final class BenchmarkPartitionLoader {
    private static final String SCHEMA_VERSION = "1.0";

    private final ObjectMapper mapper;
    private final EvaluationSuiteLoader suiteValidator;

    public BenchmarkPartitionLoader(ObjectMapper mapper) {
        this.mapper = mapper;
        this.suiteValidator = new EvaluationSuiteLoader(mapper);
    }

    public EvaluationSuite loadClasspath(String taskResource, String goldResource) {
        byte[] taskBytes = read(taskResource);
        byte[] goldBytes = read(goldResource);
        try {
            TaskSet taskSet = mapper.readValue(taskBytes, TaskSet.class);
            GoldSet goldSet = mapper.readValue(goldBytes, GoldSet.class);
            validate(taskSet, goldSet);

            Map<String, GoldCase> goldById = new LinkedHashMap<>();
            goldSet.cases().forEach(value -> goldById.put(value.caseId(), value));
            List<EvaluationCase> cases = taskSet.tasks().stream()
                    .map(task -> combine(task, goldById.get(task.caseId())))
                    .toList();
            Protocol protocol = new Protocol(
                    taskSet.partition(),
                    taskSet.promptTuningAllowed(),
                    true,
                    taskSet.taskSetId(),
                    goldSet.goldSetId(),
                    sha256(taskBytes),
                    sha256(goldBytes),
                    taskSet.dataSnapshotIds());
            EvaluationSuite suite = new EvaluationSuite(
                    "3.0",
                    taskSet.suiteId(),
                    taskSet.description(),
                    taskSet.systemVersion(),
                    cases,
                    protocol);
            suiteValidator.validate(suite);
            return suite;
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot parse benchmark partition", error);
        }
    }

    private void validate(TaskSet tasks, GoldSet gold) {
        if (tasks == null || gold == null
                || !SCHEMA_VERSION.equals(tasks.schemaVersion())
                || !SCHEMA_VERSION.equals(gold.schemaVersion())) {
            throw new IllegalArgumentException("benchmark task and gold schemas must be 1.0");
        }
        if (blank(tasks.taskSetId()) || blank(tasks.suiteId()) || blank(tasks.systemVersion())
                || tasks.partition() == null || tasks.tasks().isEmpty()) {
            throw new IllegalArgumentException("benchmark task metadata is incomplete");
        }
        if (tasks.partition() == Partition.CAPABILITY_HOLDOUT && tasks.promptTuningAllowed()) {
            throw new IllegalArgumentException("capability holdout cannot be used for prompt tuning");
        }
        if (blank(gold.goldSetId()) || !tasks.taskSetId().equals(gold.taskSetId())) {
            throw new IllegalArgumentException("gold set does not belong to the task set");
        }

        Set<String> taskIds = uniqueIds(
                tasks.tasks().stream().map(Task::caseId).toList(), "task");
        Set<String> goldIds = uniqueIds(
                gold.cases().stream().map(GoldCase::caseId).toList(), "gold");
        if (!taskIds.equals(goldIds)) {
            throw new IllegalArgumentException("task and gold case ids must match exactly");
        }

        Map<String, GoldCase> goldById = new LinkedHashMap<>();
        gold.cases().forEach(value -> goldById.put(value.caseId(), value));
        boolean hasJudgeRubric = false;
        for (Task task : tasks.tasks()) {
            if (blank(task.domainPackId()) || blank(task.prompt()) || task.trials() < 1 || task.trials() > 5) {
                throw new IllegalArgumentException("invalid benchmark task: " + task.caseId());
            }
            if (tasks.partition() == Partition.CAPABILITY_HOLDOUT && task.trials() < 3) {
                throw new IllegalArgumentException("capability holdout requires at least three trials");
            }
            GoldCase contract = goldById.get(task.caseId());
            if (contract.expected() == null) {
                throw new IllegalArgumentException("missing expected behavior: " + task.caseId());
            }
            Set<String> rubricIds = new HashSet<>();
            for (JudgeRubric rubric : contract.judgeRubrics()) {
                hasJudgeRubric = true;
                if (blank(rubric.rubricId()) || blank(rubric.criterion())
                        || rubric.minimumScore() < 1 || rubric.minimumScore() > 5
                        || !rubricIds.add(rubric.rubricId())) {
                    throw new IllegalArgumentException("invalid judge rubric: " + task.caseId());
                }
            }
        }
        if (tasks.partition() == Partition.CAPABILITY_HOLDOUT && !hasJudgeRubric) {
            throw new IllegalArgumentException("capability holdout requires model-judge rubrics");
        }
    }

    private EvaluationCase combine(Task task, GoldCase gold) {
        return new EvaluationCase(
                task.caseId(),
                task.domainPackId(),
                task.prompt(),
                task.tags(),
                task.trials(),
                gold.expected(),
                task.discovery(),
                gold.manualReviewRubrics(),
                gold.judgeRubrics());
    }

    private byte[] read(String resourcePath) {
        String normalized = resourcePath.startsWith("/")
                ? resourcePath.substring(1)
                : resourcePath;
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(normalized)) {
            if (stream == null) {
                throw new IllegalArgumentException("benchmark resource not found: " + resourcePath);
            }
            return stream.readAllBytes();
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read benchmark resource: " + resourcePath, error);
        }
    }

    private static Set<String> uniqueIds(List<String> ids, String kind) {
        Set<String> unique = new HashSet<>();
        for (String id : ids) {
            if (blank(id) || !unique.add(id)) {
                throw new IllegalArgumentException(kind + " case id is blank or duplicated: " + id);
            }
        }
        return Set.copyOf(unique);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record TaskSet(
            String schemaVersion,
            String taskSetId,
            String suiteId,
            String description,
            String systemVersion,
            Partition partition,
            boolean promptTuningAllowed,
            List<String> dataSnapshotIds,
            List<Task> tasks
    ) {
        public TaskSet {
            dataSnapshotIds = dataSnapshotIds == null ? List.of() : List.copyOf(dataSnapshotIds);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record Task(
            String caseId,
            String domainPackId,
            String prompt,
            List<String> tags,
            int trials,
            EvaluationCase.Discovery discovery
    ) {
        public Task {
            tags = tags == null ? List.of() : List.copyOf(tags);
            discovery = discovery == null
                    ? new EvaluationCase.Discovery(
                            "benchmark-user-" + caseId,
                            "benchmark-session-" + caseId,
                            false,
                            List.of(),
                            List.of(),
                            List.of())
                    : discovery;
        }
    }

    public record GoldSet(
            String schemaVersion,
            String goldSetId,
            String taskSetId,
            List<GoldCase> cases
    ) {
        public GoldSet {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public record GoldCase(
            String caseId,
            EvaluationCase.ExpectedBehavior expected,
            List<String> manualReviewRubrics,
            List<JudgeRubric> judgeRubrics
    ) {
        public GoldCase {
            manualReviewRubrics = manualReviewRubrics == null
                    ? List.of()
                    : List.copyOf(manualReviewRubrics);
            judgeRubrics = judgeRubrics == null ? List.of() : List.copyOf(judgeRubrics);
        }
    }
}
