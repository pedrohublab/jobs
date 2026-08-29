package hub.pedro.jobs.api.infra.database.postgresql.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import hub.pedro.jobs.api.infra.database.postgresql.persistance.OutboxEventJpaEntity;
import hub.pedro.jobs.api.infra.database.postgresql.repository.PostgresOutboxJpaRepository;

@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
    private static final String TOPIC = "job-created";

    private final PostgresOutboxJpaRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelayWorker(PostgresOutboxJpaRepository outboxRepository,
                             KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEventJpaEntity> pendingEvents = outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc();

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEventJpaEntity event : pendingEvents) {
            try {
                kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload()).get();
                event.setProcessed(true);
                outboxRepository.save(event);
                log.info("Evento do Outbox publicado no Kafka com sucesso. outboxId={} aggregateId={}", event.getId(), event.getAggregateId());
            } catch (Exception e) {
                log.warn("Kafka indisponível no momento. Tentará novamente no próximo ciclo. outboxId={}", event.getId());
                break;
            }
        }
    }
}
