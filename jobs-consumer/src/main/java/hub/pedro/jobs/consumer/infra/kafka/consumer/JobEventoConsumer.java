package hub.pedro.jobs.consumer.infra.kafka.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import hub.pedro.jobs.consumer.app.port.in.JobCreatedEvent;
import hub.pedro.jobs.consumer.app.service.JobProcessingService;

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
    public void onMessage(@Payload String payload, Acknowledgment acknowledgment) {
        try {
            JobCreatedEvent event = objectMapper.readValue(payload, JobCreatedEvent.class);
            log.info("[Consumer] Evento recebido com sucesso. jobId={}", event.id());
            jobProcessingService.execute(event.id());
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        } catch (Exception e) {
            log.error("[Consumer] Erro ao deserializar ou processar mensagem do Kafka. payload={}", payload, e);
        }
    }
}
