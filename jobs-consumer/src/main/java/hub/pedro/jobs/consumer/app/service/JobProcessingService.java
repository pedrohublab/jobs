package hub.pedro.jobs.consumer.app.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import hub.pedro.jobs.consumer.domain.entity.Job;
import hub.pedro.jobs.consumer.domain.repository.JobRepository;

@Service
public class JobProcessingService {

    private static final Logger log = LoggerFactory.getLogger(JobProcessingService.class);

    private final JobRepository repository;

    public JobProcessingService(JobRepository repository) {
        this.repository = repository;
    }

    public void execute(UUID id) {
        log.info("[Worker] Buscando Job id={} para processamento assíncrono", id);
        repository.findById(id).ifPresentOrElse(
                this::doProcessamento,
                () -> log.warn("[Worker] Job id={} não encontrado na base de dados", id)
        );
    }

    private void doProcessamento(Job job) {
        try {
            log.info("[Worker] Iniciando processamento do Job id={}", job.getId());
            job.finalizar();
            repository.save(job);
            log.info("[Worker] Job id={} processado com SUCESSO. Status={}", job.getId(), job.getStatus());
        } catch (Exception e) {
            log.error("[Worker] Erro ao processar Job id={}: {}", job.getId(), e.getMessage(), e);
            job.markAsFailed(e.getMessage());
            repository.save(job);
        }
    }
}
