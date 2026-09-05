package hub.pedro.jobs.api.infra.database.postgresql.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import hub.pedro.jobs.api.domain.entity.Job;
import hub.pedro.jobs.api.domain.interfaces.JobRepository;
import hub.pedro.jobs.api.infra.database.postgresql.persistance.Nfsejob;

@Repository
public class PostgresJobRepository implements JobRepository {

    private final PostgresJpaRepository jpaRepository;

    public PostgresJobRepository(PostgresJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(Job job) {
        Nfsejob nfsejob = new Nfsejob();
        nfsejob.setId(job.getId());
        nfsejob.setStatus(job.getStatus());
        nfsejob.setPayload(job.getPayload());
        nfsejob.setCreatedAt(job.getCreatedAt());
        nfsejob.setUpdatedAt(job.getUpdatedAt());
        nfsejob.setScheduledAt(job.getScheduledAt());
        nfsejob.setFinishedAt(job.getFinishedAt());
        nfsejob.setAttempts(job.getAttempts());
        
        jpaRepository.save(nfsejob);
    }

    @Override
    public Optional<Job> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(entity -> Job.builder()
                        .id(entity.getId())
                        .createdAt(entity.getCreatedAt())
                        .updatedAt(entity.getUpdatedAt())
                        .payload(entity.getPayload())
                        .scheduledAt(entity.getScheduledAt())
                        .finishedAt(entity.getFinishedAt())
                        .status(entity.getStatus())
                        .attempts(entity.getAttempts())
                        .build());
    }
}
