package hub.pedro.jobs.api.domain.interfaces;

import java.util.Optional;
import java.util.UUID;

import hub.pedro.jobs.api.domain.entity.Job;

public interface JobRepository {
    void save(Job job);
    Optional<Job> findById(UUID id);
}
