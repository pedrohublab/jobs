package hub.pedro.jobs.infra.kafka.consumer;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hub.pedro.jobs.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.app.service.JobService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class JobEventoConsumer {
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public JobEventoConsumer(JobService jobService) {
        this.jobService = jobService;
        this.objectMapper = new ObjectMapper();
    }

    @KafkaListener(topics = "job-created", groupId = "jobs-group")
    public void consumirEvento(String payload) {
        try {
            System.out.println ("Received: " + payload);
            JobCreatedEvent evento = objectMapper.readValue(payload, JobCreatedEvent.class);
            jobService.processarNf (evento.id());
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException (e);
        }
    }
}
