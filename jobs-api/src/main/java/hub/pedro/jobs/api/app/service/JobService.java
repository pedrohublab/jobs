package hub.pedro.jobs.api.app.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import hub.pedro.jobs.api.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.api.domain.entity.Job;
import hub.pedro.jobs.api.domain.interfaces.JobRepository;
import hub.pedro.jobs.api.infra.database.postgresql.persistance.OutboxEventJpaEntity;
import hub.pedro.jobs.api.infra.database.postgresql.repository.PostgresOutboxJpaRepository;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repository;
    private final PostgresOutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    JobService(JobRepository repository,
                      PostgresOutboxJpaRepository outboxRepository,
                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID scheduleNFsProcessing(byte[] payload) {
        Job job = Job.createNewJob(payload);
        repository.save(job);
        log.info("Job criado e salvo no banco. id={} status={}", job.getId(), job.getStatus());

        JobCreatedEvent event = new JobCreatedEvent(
                job.getId(), job.getStatus(), payload, job.getCreatedAt().toString());

        try {
            String jsonPayload = objectMapper.writeValueAsString(event);
            OutboxEventJpaEntity outboxEvent = new OutboxEventJpaEntity(
                    UUID.randomUUID(),
                    "JOB",
                    job.getId().toString(),
                    "job-created",
                    jsonPayload
            );
            outboxRepository.save(outboxEvent);
            log.info("Evento salvo no Outbox. id={} jobId={}", outboxEvent.getId(), job.getId());
        } catch (Exception e) {
            log.error("Erro ao serializar ou salvar evento no Outbox. jobId={}", job.getId(), e);
            throw new RuntimeException("Falha ao registrar evento no Outbox", e);
        }

        return job.getId();
    }
}
