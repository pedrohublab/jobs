package hub.pedro.jobs.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import hub.pedro.jobs.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.app.port.out.JobEventPublisher;
import hub.pedro.jobs.domain.entity.Job;
import hub.pedro.jobs.domain.repository.JobRepository;
import org.springframework.stereotype.Service;

@Service
public class JobService {
    private final JobRepository repository;
    private final JobEventPublisher publisher;

    public JobService(JobRepository repository, JobEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    public void scheduleNFsProcessing(String payload) throws JsonProcessingException {
        try {
            Job job = Job.createNewJob(payload);
            this.repository.save(job);
            JobCreatedEvent event =
                    new JobCreatedEvent(job.getId(), job.getStatus(), job.getCreatedAt());
            this.publisher.publish(event);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao processar a NF-e: " + e.getMessage());
        }
    }
}
