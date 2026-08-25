package hub.pedro.jobs.infra.database.cassandra.mapper;

import hub.pedro.jobs.domain.entity.Job;
import hub.pedro.jobs.infra.database.cassandra.persistance.JobCassandraModel;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

@Component
public class JobMapper {
    
    public JobCassandraModel toModel(Job domain) {
        if (domain == null) return null;
        JobCassandraModel model = new JobCassandraModel();
        model.setId(domain.getId());
        model.setCreatedAt(domain.getCreatedAt());
        model.setUpdatedAt(domain.getUpdatedAt());
        model.setScheduledAt(domain.getScheduledAt());
        model.setPayload(domain.getPayload());
        model.setStatus(domain.getStatus());
        model.setFinishedAt(domain.getFinishedAt());
        model.setAttempts(domain.getAttempts());
        if (domain.getErrors() != null) {
            model.setErrors(new ArrayList<>(domain.getErrors()));
        }
        return model;
    }

    public Job toDomain(JobCassandraModel model) {
        if (model == null) return null;
        Job.Builder builder = Job.builder();
        builder.id(model.getId());
        builder.createdAt(model.getCreatedAt());
        builder.updatedAt(model.getUpdatedAt());
        builder.scheduledAt(model.getScheduledAt());
        builder.payload(model.getPayload());
        builder.status(model.getStatus());
        builder.finishedAt(model.getFinishedAt());
        builder.attempts(model.getAttempts());
        if (model.getErrors() != null) {
            builder.errors(new ArrayList<>(model.getErrors()));
        }
        return builder.build();
    }
}
