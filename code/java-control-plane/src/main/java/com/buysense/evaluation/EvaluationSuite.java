package com.buysense.evaluation;

import java.util.List;

public record EvaluationSuite(
        String schemaVersion,
        String suiteId,
        String description,
        String systemVersion,
        List<EvaluationCase> cases,
        Protocol protocol
) {
    public EvaluationSuite {
        cases = cases == null ? List.of() : List.copyOf(cases);
        protocol = protocol == null ? Protocol.legacy() : protocol;
    }

    public EvaluationSuite(
            String schemaVersion,
            String suiteId,
            String description,
            String systemVersion,
            List<EvaluationCase> cases
    ) {
        this(schemaVersion, suiteId, description, systemVersion, cases, Protocol.legacy());
    }

    public enum Partition {
        REGRESSION,
        CAPABILITY_HOLDOUT,
        SAFETY_CHALLENGE
    }

    public record Protocol(
            Partition partition,
            boolean promptTuningAllowed,
            boolean hiddenLabels,
            String taskSetId,
            String goldSetId,
            String taskSetSha256,
            String goldSetSha256,
            List<String> dataSnapshotIds
    ) {
        public Protocol {
            partition = partition == null ? Partition.REGRESSION : partition;
            taskSetId = taskSetId == null ? "" : taskSetId;
            goldSetId = goldSetId == null ? "" : goldSetId;
            taskSetSha256 = taskSetSha256 == null ? "" : taskSetSha256;
            goldSetSha256 = goldSetSha256 == null ? "" : goldSetSha256;
            dataSnapshotIds = dataSnapshotIds == null ? List.of() : List.copyOf(dataSnapshotIds);
        }

        static Protocol legacy() {
            return new Protocol(
                    Partition.REGRESSION,
                    true,
                    false,
                    "",
                    "",
                    "",
                    "",
                    List.of());
        }
    }
}
