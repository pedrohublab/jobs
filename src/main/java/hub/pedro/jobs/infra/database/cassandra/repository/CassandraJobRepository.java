package hub.pedro.jobs.infra.database.cassandra.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import hub.pedro.jobs.domain.entity.Job;
import hub.pedro.jobs.domain.repository.JobRepository;
import hub.pedro.jobs.infra.database.cassandra.mapper.JobMapper;
import hub.pedro.jobs.infra.database.cassandra.persistance.JobCassandraModel;
import hub.pedro.jobs.infra.database.cassandra.persistance.SpringDataCassandraJobRepository;

@Repository
public class CassandraJobRepository implements JobRepository {

    private static final Logger logger = LoggerFactory.getLogger(CassandraJobRepository.class);

    private SpringDataCassandraJobRepository repository;
    private JobMapper jobMapper;

    public CassandraJobRepository(SpringDataCassandraJobRepository repository, JobMapper jobMapper) {
        this.repository = repository;
        this.jobMapper = jobMapper;
    }

    @Override
    public void save(Job job) {
        JobCassandraModel jobModel = jobMapper.toModel(job);
        repository.save(jobModel);
        logger.info("Job salvo com sucesso!");
    }
}
