package hub.pedro.jobs.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import hub.pedro.jobs.app.port.out.JobCreatedEvent;
import hub.pedro.jobs.app.port.out.JobEventPublisher;
import hub.pedro.jobs.domain.entity.Job;
import hub.pedro.jobs.domain.repository.JobRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

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
                    new JobCreatedEvent(job.getId(), job.getStatus(), job.getCreatedAt().toString());
            this.publisher.publish(event);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao processar a NF-e: " + e.getMessage());
        }
    }

    public void processarNf (UUID id) throws JsonProcessingException {
        Job job = repository.findById (id).orElseThrow(() -> new RuntimeException("NF-e nao encontrado"));

        try {
            System.out.println ("Fingindo que to processando otario");
            Thread.sleep(3000);
        }   catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally{
            job.finalizar ();
            repository.save(job);
            System.out.println ("Job processado e salvo no cassandra");
        }
    }
}
