package hub.pedro.jobs.api.domain.entity;

import hub.pedro.jobs.api.domain.shared.JobStatus;
import org.jmolecules.ddd.annotation.AggregateRoot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public Job(UUID id, Instant createdAt, Instant updatedAt, byte[] payload, Instant scheduledAt, Instant finishedAt, JobStatus status, Integer attempts, List<String> errors) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.payload = payload != null ? new String(payload, java.nio.charset.StandardCharsets.UTF_8) : null;
        this.scheduledAt = scheduledAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.attempts = attempts;
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
    }

    public Job(UUID id, Instant createdAt, Instant updatedAt, String payload, Instant scheduledAt, Instant finishedAt, JobStatus status, Integer attempts, List<String> errors) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.payload = payload;
        this.scheduledAt = scheduledAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.attempts = attempts;
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
    }

    public static Job createNewJob(byte[] payload) {
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

    public static Builder builder() { return new Builder(); }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getPayload() { return payload; }
    public byte[] getPayloadBytes() { return payload != null ? payload.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0]; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public JobStatus getStatus() { return status; }
    public Integer getAttempts() { return attempts; }
    public List<String> getErrors() { return errors; }

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

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder payload(byte[] payload) {
            this.payload = payload != null ? new String(payload, java.nio.charset.StandardCharsets.UTF_8) : null;
            return this;
        }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder scheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; return this; }
        public Builder finishedAt(Instant finishedAt) { this.finishedAt = finishedAt; return this; }
        public Builder status(JobStatus status) { this.status = status; return this; }
        public Builder attempts(Integer attempts) { this.attempts = attempts; return this; }
        public Builder errors(List<String> errors) { this.errors = errors; return this; }
        public Job build() {
            return new Job(id, createdAt, updatedAt, payload, scheduledAt, finishedAt, status, attempts, errors);
        }
    }
}
