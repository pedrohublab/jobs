package hub.pedro.jobs.consumer.infra.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hub.pedro.jobs.consumer.app.port.in.JobCreatedEvent;
import hub.pedro.jobs.consumer.app.service.JobProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Adapter de entrada: escuta o tópico Kafka 'job-created' e
 * delega o processamento para JobProcessingService de forma assíncrona.
 *
 * O método consumirEvento() retorna imediatamente após disparar o @Async,
 * mantendo o consumer thread livre para processar o próximo evento.
 */
@Component
public class JobEventoConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobEventoConsumer.class);

    private final JobProcessingService jobProcessingService;
    private final ObjectMapper objectMapper;

    public JobEventoConsumer(JobProcessingService jobProcessingService, ObjectMapper objectMapper) {
        this.jobProcessingService = jobProcessingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "job-created", groupId = "jobs-consumer-group")
    public void consumirEvento(String payload) {
        log.info("[Consumer] Evento recebido. payload={}", payload);
        try {
            JobCreatedEvent evento = objectMapper.readValue(payload, JobCreatedEvent.class);
            // @Async: retorna imediatamente, processamento ocorre em thread do pool
            jobProcessingService.processarNf(evento.id());
        } catch (JsonProcessingException e) {
            log.error("[Consumer] Falha ao desserializar evento Kafka: {}", e.getMessage(), e);
            throw new RuntimeException("Falha ao desserializar evento Kafka", e);
        }
    }
}
