package hub.pedro.jobs.app.port.out;

import com.fasterxml.jackson.core.JsonProcessingException;

public interface JobEventPublisher {
    void publish(JobCreatedEvent job) throws JsonProcessingException;
}
