package hub.pedro.jobs.api.infra.database.postgresql.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import hub.pedro.jobs.api.infra.database.postgresql.persistance.Nfsejob;

public interface PostgresJpaRepository extends JpaRepository<Nfsejob, UUID> {
}

