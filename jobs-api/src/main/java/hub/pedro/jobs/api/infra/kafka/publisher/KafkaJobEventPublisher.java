package hub.pedro.jobs.api.infra.kafka.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import hub.pedro.jobs.api.app.port.out.JobCreatedEvent;


@Component
public class KafkaJobEventPublisher implements JobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobEventPublisher.class);
    private static final String TOPIC = "job-created";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaJobEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(JobCreatedEvent job) {
        try {
            String payload = objectMapper.writeValueAsString(job);
            kafkaTemplate.send(TOPIC, job.id().toString(), payload).get();
            log.info("Evento publicado no Kafka com sucesso. topic={} jobId={}", TOPIC, job.id());
        } catch (Exception e) {
            log.error("Erro ao publicar evento no Kafka. jobId={}", job.id(), e);
            throw new RuntimeException("Falha ao publicar evento no Kafka", e);
        }
    }
}
