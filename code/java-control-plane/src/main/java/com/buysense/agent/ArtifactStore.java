package com.buysense.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Per-run append-only Artifact ledger. */
final class ArtifactStore {
    private final String runId;
    private final List<AgentArtifact> items = new CopyOnWriteArrayList<>();

    ArtifactStore(String runId) {
        this.runId = runId;
    }

    AgentArtifact publish(
            String type,
            String producer,
            String parentTaskId,
            AgentArtifact.Status status,
            Map<String, Object> payload
    ) {
        AgentArtifact artifact = new AgentArtifact(
                "artifact-" + UUID.randomUUID(),
                runId,
                parentTaskId,
                type,
                producer,
                status,
                payload,
                Instant.now());
        items.add(artifact);
        return artifact;
    }

    synchronized AgentArtifact updateStatus(String artifactId, AgentArtifact.Status status) {
        for (int index = 0; index < items.size(); index++) {
            AgentArtifact artifact = items.get(index);
            if (!artifact.artifactId().equals(artifactId)) continue;
            AgentArtifact updated = artifact.withStatus(status);
            items.set(index, updated);
            return updated;
        }
        throw new IllegalArgumentException("unknown artifact: " + artifactId);
    }

    List<AgentArtifact> list() {
        return List.copyOf(items);
    }
}
