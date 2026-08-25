package hub.pedro.jobs.domain.entity;

import hub.pedro.jobs.domain.interfaces.JobStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jmolecules.ddd.annotation.AggregateRoot;

@AggregateRoot
public class Job {
    private UUID id;
    private Instant createdAt;
    private Instant updatedAt;
    private String payload;
    private Instant scheduledAt;
    private Instant finishedAt;
    private JobStatus status;
    private Integer attempts;
    private List<String> errors;

    protected Job() {}

    private Job(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            String payload,
            Instant scheduledAt,
            Instant finishedAt,
            JobStatus status,
            Integer attempts,
            List<String> errors) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.payload = payload;
        this.scheduledAt = scheduledAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.attempts = attempts;
        this.errors = new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Job createNewJob(String payload) {
        return new Builder()
                .id(UUID.randomUUID())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .payload(payload)
                .status(JobStatus.PENDING)
                .attempts(0)
                .errors(new ArrayList<>())
                .build();
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void markAsFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.attempts++;
        if (this.errors != null) {
            this.errors.add(errorMessage);
        }
        this.updatedAt = Instant.now();
    }

    public void complete() {
        this.finishedAt = Instant.now();
        this.status = JobStatus.DONE;
        this.updatedAt = Instant.now();
    }

    public void scheduleFor(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
        this.status = JobStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    public static class Builder {
        private UUID id;
        private Instant createdAt;
        private Instant updatedAt;
        private String payload;
        private Instant scheduledAt;
        private Instant finishedAt;
        private JobStatus status;
        private Integer attempts;
        private List<String> errors;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder scheduledAt(Instant scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder status(JobStatus status) {
            this.status = status;
            return this;
        }

        public Builder attempts(Integer attempts) {
            this.attempts = attempts;
            return this;
        }

        public Builder errors(List<String> errors) {
            this.errors = errors;
            return this;
        }

        public Job build() {
            return new Job(
                    id,
                    createdAt,
                    updatedAt,
                    payload,
                    scheduledAt,
                    finishedAt,
                    status,
                    attempts,
                    errors);
        }
    }
}
