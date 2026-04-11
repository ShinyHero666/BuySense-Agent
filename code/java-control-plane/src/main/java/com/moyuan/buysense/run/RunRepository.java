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
        insertRows(run, idempotencyKey);
    }

    @Transactional
    public void insertAdmitted(
            AgentRun run,
            String idempotencyKey,
            RunExecutionProperties properties,
            Instant now
    ) {
        jdbc.queryForObject(
                "select lock_id from run_admission_lock where lock_id = 'global' for update",
                String.class);
        if (countActiveBySession(run.getSessionId()) >= properties.maxActivePerSession()) {
            throw new AdmissionRejectedException("session_concurrency_limit", false);
        }
        if (countCreatedSince(run.getSessionId(), now.minusSeconds(60))
                >= properties.maxCreatedPerMinute()) {
            throw new AdmissionRejectedException("session_rate_limit", false);
        }
        long globalCapacity = (long) properties.maxConcurrent() + properties.queueCapacity();
        if (countActiveGlobal() >= globalCapacity) {
            throw new AdmissionRejectedException("run_queue_full", true);
        }
        insertRows(run, idempotencyKey);
    }

    private void insertRows(AgentRun run, String idempotencyKey) {
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
        if (run.isConfirmationRequested()) {
            jdbc.update("""
                            insert into proposal_confirmation_claims (
                                proposal_run_id, confirmation_run_id, created_at
                            ) values (?, ?, ?)
                            """,
                    run.getProposalRunId(), run.getRunId(), Timestamp.from(Instant.now()));
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

    public long countActiveBySession(String sessionId) {
        Long count = jdbc.queryForObject("""
                select count(*) from agent_runs
                where session_id = ? and status in ('queued', 'running')
                """, Long.class, sessionId);
        return count == null ? 0 : count;
    }

    public long countCreatedSince(String sessionId, Instant since) {
        Long count = jdbc.queryForObject("""
                select count(*) from agent_runs
                where session_id = ? and created_at >= ?
                """, Long.class, sessionId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    public long countActiveGlobal() {
        Long count = jdbc.queryForObject("""
                select count(*) from agent_runs
                where status in ('queued', 'running')
                """, Long.class);
        return count == null ? 0 : count;
    }

    public Optional<String> findConfirmationByProposal(String proposalRunId) {
        if (proposalRunId == null || proposalRunId.isBlank()) return Optional.empty();
        List<String> matches = jdbc.query("""
                select confirmation_run_id
                from proposal_confirmation_claims
                where proposal_run_id = ?
                """, (rs, rowNum) -> rs.getString("confirmation_run_id"), proposalRunId);
        return matches.stream().findFirst();
    }
    @Transactional
    public Optional<Lease> acquireLease(
            String runId,
            String owner,
            Instant now,
            Instant expiresAt
    ) {
        int updated = jdbc.update("""
                update agent_runs
                set lease_owner = ?, lease_token = lease_token + 1,
                    lease_expires_at = ?, status = 'running', updated_at = ?
                where run_id = ?
                  and status in ('queued', 'running')
                  and (lease_expires_at is null or lease_expires_at < ?)
                """,
                owner,
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                runId,
                Timestamp.from(now));
        if (updated != 1) return Optional.empty();
        List<Lease> leases = jdbc.query("""
                select lease_owner, lease_token, lease_expires_at
                from agent_runs where run_id = ? and lease_owner = ?
                """,
                (rs, rowNum) -> new Lease(
                        rs.getString("lease_owner"),
                        rs.getLong("lease_token"),
                        instant(rs, "lease_expires_at")),
                runId,
                owner);
        return leases.stream().findFirst();
    }
    public boolean renewLease(
            String runId,
            Lease lease,
            Instant now,
            Instant expiresAt
    ) {
        return jdbc.update("""
                update agent_runs
                set lease_expires_at = ?, updated_at = ?
                where run_id = ?
                  and lease_owner = ? and lease_token = ?
                  and status in ('queued', 'running')
                  and lease_expires_at >= ?
                """,
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                runId,
                lease.owner(),
                lease.token(),
                Timestamp.from(now)) == 1;
    }

    public List<String> findRecoverableRunIds(Instant now, int limit) {
        return jdbc.query("""
                select run_id from agent_runs
                where status in ('queued', 'running')
                  and (lease_expires_at is null or lease_expires_at < ?)
                order by created_at
                limit ?
                """,
                (rs, rowNum) -> rs.getString("run_id"),
                Timestamp.from(now),
                limit);
    }
    @Transactional
    public void update(AgentRun run) {
        updateColumns(run);
        releaseRetryableConfirmationClaim(run);
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
        releaseRetryableConfirmationClaim(run);
    }
    @Transactional
    public void appendEventFenced(
            String runId,
            RunEvent event,
            Instant runUpdatedAt,
            Lease lease
    ) {
        requireLease(runId, runUpdatedAt, lease);
        insertEvent(runId, event);
    }

    @Transactional
    public void updateWithEventFenced(AgentRun run, RunEvent event, Lease lease) {
        insertEvent(run.getRunId(), event);
        int updated = jdbc.update("""
                        update agent_runs
                        set status = ?, result_json = ?, error_message = ?,
                            result_phase = ?, cart_draft_json = ?,
                            cancellation_requested = ?, updated_at = ?
                        where run_id = ?
                          and lease_owner = ? and lease_token = ?
                          and status in ('queued', 'running')
                          and lease_expires_at >= ?
                        """,
                run.getStatus(),
                writeNullable(run.getResult()),
                run.getError(),
                run.getPhase(),
                writeNullable(run.getCartDraft()),
                run.isCancellationRequested(),
                Timestamp.from(run.getUpdatedAt()),
                run.getRunId(),
                lease.owner(),
                lease.token(),
                Timestamp.from(Instant.now()));
        if (updated != 1) throw new LeaseLostException(run.getRunId());
        releaseRetryableConfirmationClaim(run);
    }

    private void requireLease(String runId, Instant updatedAt, Lease lease) {
        int updated = jdbc.update("""
                update agent_runs set updated_at = ?
                where run_id = ?
                  and lease_owner = ? and lease_token = ?
                  and status in ('queued', 'running')
                  and lease_expires_at >= ?
                """,
                Timestamp.from(updatedAt),
                runId,
                lease.owner(),
                lease.token(),
                Timestamp.from(Instant.now()));
        if (updated != 1) throw new LeaseLostException(runId);
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

    private void releaseRetryableConfirmationClaim(AgentRun run) {
        if (run.isConfirmationRequested()
                && ("failed".equals(run.getStatus()) || "cancelled".equals(run.getStatus()))) {
            jdbc.update("""
                    delete from proposal_confirmation_claims
                    where confirmation_run_id = ?
                    """, run.getRunId());
        }
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

    @Transactional
    public List<String> deleteTerminalBefore(Instant cutoff) {
        List<String> runIds = jdbc.query("""
                select run_id from agent_runs
                where status in ('completed', 'failed', 'cancelled')
                  and updated_at < ?
                """, (rs, rowNum) -> rs.getString("run_id"), Timestamp.from(cutoff));
        if (!runIds.isEmpty()) {
            jdbc.update("""
                    delete from agent_runs
                    where status in ('completed', 'failed', 'cancelled')
                      and updated_at < ?
                    """, Timestamp.from(cutoff));
        }
        return List.copyOf(runIds);
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

    public static final class AdmissionRejectedException extends RuntimeException {
        private final String code;
        private final boolean overloaded;

        public AdmissionRejectedException(String code, boolean overloaded) {
            super(code);
            this.code = code;
            this.overloaded = overloaded;
        }

        public String code() {
            return code;
        }

        public boolean overloaded() {
            return overloaded;
        }
    }
    public record Lease(String owner, long token, Instant expiresAt) {
    }

    public static final class LeaseLostException extends RuntimeException {
        public LeaseLostException(String runId) {
            super("run lease was lost: " + runId);
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