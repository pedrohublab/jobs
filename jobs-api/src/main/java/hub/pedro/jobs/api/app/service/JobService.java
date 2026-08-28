package hub.pedro.jobs.api.app.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import hub.pedro.jobs.api.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.api.app.port.out.JobEventPublisher;
import hub.pedro.jobs.api.domain.entity.Job;
import hub.pedro.jobs.api.domain.interfaces.JobRepository;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repository;
    private final JobEventPublisher publisher;

    public JobService(JobRepository repository, JobEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    public UUID scheduleNFsProcessing(byte[] payload) {
        Job job = Job.createNewJob(payload);
        repository.save(job);
        log.info("Job criado. id={} status={}", job.getId(), job.getStatus());

        JobCreatedEvent event = new JobCreatedEvent(
                job.getId(), job.getStatus(), payload, job.getCreatedAt().toString());

        publisher.publish(event);

        log.info("Evento disparado para publicação no Kafka. id={}", job.getId());
        return job.getId();
    }
}
