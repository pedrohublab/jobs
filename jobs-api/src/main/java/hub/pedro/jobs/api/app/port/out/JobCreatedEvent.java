package hub.pedro.jobs.api.app.port.out;
import hub.pedro.jobs.api.domain.shared.JobStatus;
import java.util.UUID;

public record JobCreatedEvent(UUID id, JobStatus status, byte[] payload, String createdAt) {

}
