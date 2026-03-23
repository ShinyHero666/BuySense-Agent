package com.moyuan.buysense.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.DecisionResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RunRepository {
    private static final TypeReference<Map<String, Object>> EVENT_PAYLOAD = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void insert(AgentRun run, String idempotencyKey) {
        jdbc.update("""
                        insert into agent_runs (
                            run_id, session_id, message, status, result_json, error_message,
                            confirmation_requested, domain_pack_id, workflow_id, proposal_run_id,
                            result_phase, cart_draft_json, cancellation_requested, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                run.getRunId(),
                run.getSessionId(),
                run.getMessage(),
                run.getStatus(),
                writeNullable(run.getResult()),
                run.getError(),
                run.isConfirmationRequested(),
                run.getDomainPackId(),
                run.getWorkflowId(),
                run.getProposalRunId(),
                run.getPhase(),
                writeNullable(run.getCartDraft()),
                run.isCancellationRequested(),
                Timestamp.from(run.getCreatedAt()),
                Timestamp.from(run.getUpdatedAt()));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            jdbc.update("""
                            insert into idempotency_keys (session_id, idempotency_key, run_id, created_at)
                            values (?, ?, ?, ?)
                            """,
                    run.getSessionId(), idempotencyKey, run.getRunId(), Timestamp.from(Instant.now()));
        }
    }

    public Optional<String> findIdempotentRun(String sessionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        List<String> matches = jdbc.query("""
                        select run_id from idempotency_keys
                        where session_id = ? and idempotency_key = ?
                        """,
                (rs, rowNum) -> rs.getString("run_id"),
                sessionId, idempotencyKey);
        return matches.stream().findFirst();
    }

    @Transactional
    public void update(AgentRun run) {
        updateColumns(run);
    }

    @Transactional
    public void appendEvent(String runId, RunEvent event, Instant runUpdatedAt) {
        insertEvent(runId, event);
        jdbc.update("update agent_runs set updated_at = ? where run_id = ?",
                Timestamp.from(runUpdatedAt), runId);
    }

    @Transactional
    public void updateWithEvent(AgentRun run, RunEvent event) {
        insertEvent(run.getRunId(), event);
        updateColumns(run);
    }

    private void insertEvent(String runId, RunEvent event) {
        jdbc.update("""
                        insert into run_events (
                            event_id, run_id, sequence_no, event_type, event_time, payload_json
                        ) values (?, ?, ?, ?, ?, ?)
                        """,
                event.eventId(),
                runId,
                event.sequence(),
                event.eventType(),
                Timestamp.from(event.timestamp()),
                write(event.payload()));
    }

    private void updateColumns(AgentRun run) {
        jdbc.update("""
                        update agent_runs
                        set status = ?, result_json = ?, error_message = ?,
                            result_phase = ?, cart_draft_json = ?,
                            cancellation_requested = ?, updated_at = ?
                        where run_id = ?
                        """,
                run.getStatus(),
                writeNullable(run.getResult()),
                run.getError(),
                run.getPhase(),
                writeNullable(run.getCartDraft()),
                run.isCancellationRequested(),
                Timestamp.from(run.getUpdatedAt()),
                run.getRunId());
    }

    public List<AgentRun> findAll() {
        return jdbc.query(selectRuns(""), (rs, rowNum) -> row(rs)).stream()
                .map(this::restore)
                .toList();
    }

    public Optional<AgentRun> findById(String runId) {
        List<RunRow> matches = jdbc.query(
                selectRuns("where run_id = ?"),
                (rs, rowNum) -> row(rs),
                runId);
        return matches.stream().findFirst().map(this::restore);
    }

    private static String selectRuns(String suffix) {
        return """
                select run_id, session_id, message, confirmation_requested,
                       domain_pack_id, workflow_id, proposal_run_id,
                       status, result_json, error_message, result_phase, cart_draft_json,
                       cancellation_requested, created_at, updated_at
                from agent_runs
                """ + suffix + " order by created_at";
    }

    private AgentRun restore(RunRow row) {
        List<RunEvent> events = jdbc.query("""
                        select event_id, sequence_no, event_type, event_time, payload_json
                        from run_events
                        where run_id = ?
                        order by sequence_no
                        """,
                (rs, rowNum) -> new RunEvent(
                        rs.getString("event_id"),
                        rs.getLong("sequence_no"),
                        rs.getString("event_type"),
                        instant(rs, "event_time"),
                        read(rs.getString("payload_json"), EVENT_PAYLOAD)),
                row.runId());
        DecisionResult result = row.resultJson() == null
                ? null
                : read(row.resultJson(), DecisionResult.class);
        AgentRun.CartDraftState cartDraft = row.cartDraftJson() == null
                ? null
                : read(row.cartDraftJson(), AgentRun.CartDraftState.class);
        return AgentRun.restore(
                row.runId(),
                row.sessionId(),
                row.message(),
                row.confirmationRequested(),
                row.domainPackId(),
                row.workflowId(),
                row.proposalRunId(),
                row.status(),
                row.createdAt(),
                row.updatedAt(),
                result,
                row.error(),
                row.phase(),
                cartDraft,
                row.cancellationRequested(),
                events);
    }

    private RunRow row(ResultSet rs) throws SQLException {
        return new RunRow(
                rs.getString("run_id"),
                rs.getString("session_id"),
                rs.getString("message"),
                rs.getBoolean("confirmation_requested"),
                rs.getString("domain_pack_id"),
                rs.getString("workflow_id"),
                rs.getString("proposal_run_id"),
                rs.getString("status"),
                rs.getString("result_json"),
                rs.getString("error_message"),
                rs.getString("result_phase"),
                rs.getString("cart_draft_json"),
                rs.getBoolean("cancellation_requested"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private String writeNullable(Object value) {
        return value == null ? null : write(value);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("unable to serialize persistent run state", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("unable to deserialize persistent run state", error);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("unable to deserialize persistent run event", error);
        }
    }

    private record RunRow(
            String runId,
            String sessionId,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId,
            String status,
            String resultJson,
            String error,
            String phase,
            String cartDraftJson,
            boolean cancellationRequested,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}