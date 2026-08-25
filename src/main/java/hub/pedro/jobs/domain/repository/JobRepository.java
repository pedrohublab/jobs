package hub.pedro.jobs.domain.repository;

import hub.pedro.jobs.domain.entity.Job;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    void save(Job job);
    Optional<Job> findById (UUID id);
}
