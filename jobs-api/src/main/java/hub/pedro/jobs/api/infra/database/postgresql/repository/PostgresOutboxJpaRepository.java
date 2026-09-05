package hub.pedro.jobs.api.infra.database.postgresql.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import hub.pedro.jobs.api.infra.database.postgresql.persistance.OutboxEventJpaEntity;

public interface PostgresOutboxJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {
    List<OutboxEventJpaEntity> findTop50ByProcessedFalseOrderByCreatedAtAsc();
}
