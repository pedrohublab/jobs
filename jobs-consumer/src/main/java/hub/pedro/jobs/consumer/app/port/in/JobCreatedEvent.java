package hub.pedro.jobs.consumer.app.port.in;

import hub.pedro.jobs.consumer.domain.interfaces.JobStatus;
import java.util.UUID;

public record JobCreatedEvent(UUID id, JobStatus status, byte[] payload, String createdAt) {
}