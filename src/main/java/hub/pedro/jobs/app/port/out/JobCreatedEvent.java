package hub.pedro.jobs.app.port.out;

import hub.pedro.jobs.domain.interfaces.JobStatus;
import java.util.UUID;

public record JobCreatedEvent(UUID id, JobStatus status, String createdAt) {}
