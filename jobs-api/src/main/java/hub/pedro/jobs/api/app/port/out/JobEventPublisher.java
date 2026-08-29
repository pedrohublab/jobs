package hub.pedro.jobs.api.app.port.out;

/**
 * JobEventPublisher
 */
public interface JobEventPublisher {
    void publish(JobCreatedEvent job);
    
}
