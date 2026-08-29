package hub.pedro.jobs.api.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import hub.pedro.jobs.api.domain.entity.Job;
import hub.pedro.jobs.api.domain.interfaces.JobRepository;
import hub.pedro.jobs.api.infra.database.postgresql.persistance.OutboxEventJpaEntity;
import hub.pedro.jobs.api.infra.database.postgresql.repository.PostgresOutboxJpaRepository;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private PostgresOutboxJpaRepository outboxRepository;

    private ObjectMapper objectMapper;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jobService = new JobService(jobRepository, outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("Should save Job and Outbox event atomically")
    void shouldSaveJobAndOutboxEventAtomically() {
        byte[] payload = "{\"test\":\"data\"}".getBytes(StandardCharsets.UTF_8);

        UUID jobId = jobService.scheduleNFsProcessing(payload);

        assertNotNull(jobId);

        verify(jobRepository, times(1)).save(any(Job.class));

        ArgumentCaptor<OutboxEventJpaEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventJpaEntity.class);
        verify(outboxRepository, times(1)).save(outboxCaptor.capture());

        OutboxEventJpaEntity savedOutbox = outboxCaptor.getValue();
        assertEquals("JOB", savedOutbox.getAggregateType());
        assertEquals(jobId.toString(), savedOutbox.getAggregateId());
        assertEquals("job-created", savedOutbox.getEventType());
        assertFalse(savedOutbox.isProcessed());
        assertTrue(savedOutbox.getPayload().contains(jobId.toString()));
    }
}
