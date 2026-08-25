package hub.pedro.jobs.infra.database.cassandra.persistance;

import java.util.UUID;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface SpringDataCassandraJobRepository
        extends CassandraRepository<JobCassandraModel, UUID> {}
