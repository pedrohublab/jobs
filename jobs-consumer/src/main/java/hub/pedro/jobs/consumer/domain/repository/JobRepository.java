package hub.pedro.jobs.consumer.domain.repository;

import java.util.Optional;
import java.util.UUID;

import hub.pedro.jobs.consumer.domain.entity.Job;

public interface JobRepository {
    void save(Job job);
    Optional<Job> findById(UUID id);
}
