package hub.pedro.jobs.infra.kafka.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hub.pedro.jobs.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.app.port.out.JobEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaJobEventPublisher implements JobEventPublisher {
    KafkaTemplate<String, String> kafkaTemplate;
    ObjectMapper objectMapper;

    public KafkaJobEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void publish(JobCreatedEvent job) throws JsonProcessingException {
        String payload = objectMapper.writeValueAsString(job);
        kafkaTemplate.send("job-created", payload);
    }
}
