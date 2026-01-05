package com.buysense.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:buysense-governance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class RunGovernanceTest {
    @Autowired
    private RunRepository repository;

    @Autowired
    private RunService runs;

    @Test
    void expiredWorkerCannotCommitAfterAnotherWorkerAcquiresTheLease() {
        AgentRun run = run("lease");
        repository.insert(run, null);
        Instant started = Instant.now();

        RunRepository.Lease first = repository.acquireLease(
                run.getRunId(), "worker-a", started, started.plusSeconds(30)).orElseThrow();
        assertThat(repository.acquireLease(
                run.getRunId(), "worker-b", started.plusSeconds(1), started.plusSeconds(31)))
                .isEmpty();

        RunRepository.Lease second = repository.acquireLease(
                run.getRunId(), "worker-b", started.plusSeconds(31), started.plusSeconds(61)).orElseThrow();
        RunEvent event = new RunEvent(
                UUID.randomUUID().toString(),
                1,
                "artifact",
                started.plusSeconds(32),
                Map.of("event", "fenced_write"));

        assertThat(second.token()).isGreaterThan(first.token());
        assertThatThrownBy(() -> repository.appendEventFenced(
                run.getRunId(), event, event.timestamp(), first))
                .isInstanceOf(RunRepository.LeaseLostException.class);

        repository.appendEventFenced(run.getRunId(), event, event.timestamp(), second);
        assertThat(repository.findById(run.getRunId()).orElseThrow().getEvents())
                .extracting(RunEvent::eventId)
                .containsExactly(event.eventId());
    }

    @Test
    void onlyOneConfirmationRunCanBindToAProposalAcrossIdempotencyKeys() {
        String proposalId = UUID.randomUUID().toString();
        repository.insert(new AgentRun(
                proposalId,
                "confirmation-session",
                "proposal",
                false,
                "normal-3c-v1",
                "commerce-decision-v1",
                null), null);

        repository.insert(new AgentRun(
                UUID.randomUUID().toString(),
                "confirmation-session",
                "confirm",
                true,
                "normal-3c-v1",
                "commerce-decision-v1",
                proposalId), "confirm-key-a");

        assertThatThrownBy(() -> repository.insert(new AgentRun(
                UUID.randomUUID().toString(),
                "confirmation-session",
                "confirm again",
                true,
                "normal-3c-v1",
                "commerce-decision-v1",
                proposalId), "confirm-key-b"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void renewsAnOwnedLeaseAndOnlyRecoversItAfterTheRenewedDeadline() {
        AgentRun run = run("renew");
        repository.insert(run, null);
        Instant started = Instant.now();
        RunRepository.Lease lease = repository.acquireLease(
                run.getRunId(), "worker-a", started, started.plusSeconds(30)).orElseThrow();

        assertThat(repository.renewLease(
                run.getRunId(), lease, started.plusSeconds(20), started.plusSeconds(50)))
                .isTrue();
        assertThat(repository.acquireLease(
                run.getRunId(), "worker-b", started.plusSeconds(31), started.plusSeconds(61)))
                .isEmpty();
        assertThat(repository.findRecoverableRunIds(started.plusSeconds(51), 10))
                .contains(run.getRunId());
    }

    @Test
    void failedConfirmationResetsTheSameRunForRetry() {
        String proposalId = UUID.randomUUID().toString();
        repository.insert(new AgentRun(
                proposalId,
                "retry-session",
                "proposal",
                false,
                "normal-3c-v1",
                "commerce-decision-v1",
                null), null);

        AgentRun failed = new AgentRun(
                UUID.randomUUID().toString(),
                "retry-session",
                "confirm",
                true,
                "normal-3c-v1",
                "commerce-decision-v1",
                proposalId);
        repository.insert(failed, "retry-key-a");
        failed.prepareFailure(new IllegalStateException("quote unavailable"));
        failed.transition("failed");
        repository.updateWithEvent(
                failed,
                new RunEvent(
                        UUID.randomUUID().toString(),
                        1,
                        "run_failed",
                        Instant.now(),
                        Map.of("event", "run_failed")));

        repository.resetConfirmationForRetry(failed, "retry-key-b");

        assertThat(repository.findConfirmationByProposal("id_retry-session", proposalId))
                .contains(failed.getRunId());
        assertThat(failed.getStatus()).isEqualTo("queued");
        assertThat(repository.findIdempotentRun("id_retry-session", "retry-key-b"))
                .contains(failed.getRunId());
    }

    @Test
    void admissionAndInsertShareTheSameDatabaseBoundary() {
        String sessionId = "atomic-capacity-" + UUID.randomUUID();
        RunExecutionProperties oneActiveRun = new RunExecutionProperties(
                1,
                1,
                1,
                30,
                Duration.ofMinutes(2),
                Duration.ofMinutes(15),
                Duration.ofDays(30));
        repository.insertAdmitted(
                new AgentRun(
                        UUID.randomUUID().toString(),
                        sessionId,
                        "first",
                        false,
                        "normal-3c-v1",
                        "commerce-decision-v1",
                        null),
                null,
                oneActiveRun,
                Instant.now());

        assertThatThrownBy(() -> repository.insertAdmitted(
                new AgentRun(
                        UUID.randomUUID().toString(),
                        sessionId,
                        "second",
                        false,
                        "normal-3c-v1",
                        "commerce-decision-v1",
                        null),
                null,
                oneActiveRun,
                Instant.now()))
                .isInstanceOf(RunRepository.AdmissionRejectedException.class)
                .extracting(error -> ((RunRepository.AdmissionRejectedException) error).code())
                .isEqualTo("identity_concurrency_limit");
    }
    @Test
    void rejectsAFourthActiveRunForTheSameIdentityBeforeQueueing() {
        String sessionId = "capacity-" + UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            repository.insert(new AgentRun(
                    UUID.randomUUID().toString(),
                    sessionId,
                    "queued-" + index,
                    false,
                    "normal-3c-v1",
                    "commerce-decision-v1",
                    null), null);
        }

        assertThatThrownBy(() -> runs.create(
                sessionId,
                "capacity-key",
                "预算3000元买手机",
                false,
                "normal-3c-v1",
                "commerce-decision-v1",
                null))
                .isInstanceOf(RunService.RunCapacityException.class)
                .extracting(error -> ((RunService.RunCapacityException) error).code())
                .isEqualTo("identity_concurrency_limit");
    }

    private static AgentRun run(String prefix) {
        return new AgentRun(
                UUID.randomUUID().toString(),
                prefix + "-session",
                prefix,
                false,
                "normal-3c-v1",
                "commerce-decision-v1",
                null);
    }
}