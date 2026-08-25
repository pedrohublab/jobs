package hub.pedro.jobs.domain.repository;

import hub.pedro.jobs.domain.entity.Job;

public interface JobRepository {
    void save(Job job);
}
