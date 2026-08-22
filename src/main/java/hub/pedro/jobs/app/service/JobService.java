package hub.pedro.jobs.app.service;

import hub.pedro.jobs.domain.repository.JobRepository;

public class JobService {
    private final JobRepository jobRepository;

    public JobService (JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }
}
