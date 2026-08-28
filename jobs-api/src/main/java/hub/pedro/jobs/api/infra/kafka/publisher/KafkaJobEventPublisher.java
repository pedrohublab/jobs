package hub.pedro.jobs.api.infra.kafka.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import hub.pedro.jobs.api.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.api.app.port.out.JobEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
            kafkaTemplate.send(TOPIC, job.id().toString(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Falha ao publicar evento no Kafka. jobId={}", job.id(), ex);
                        } else {
                            log.info("Evento publicado. topic={} partition={} offset={}",
                                    TOPIC,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("Erro ao serializar ou disparar envio para o Kafka. jobId={}", job.id(), e);
        }
    }
}
