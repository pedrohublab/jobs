package hub.pedro.jobs.api.app.port.out;

public interface JobEventPublisher {
    void publish(JobCreatedEvent job);
}
