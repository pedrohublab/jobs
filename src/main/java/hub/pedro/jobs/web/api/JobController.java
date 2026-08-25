package hub.pedro.jobs.web.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import hub.pedro.jobs.app.service.JobService;
import hub.pedro.jobs.web.api.in.NfsRequest;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public JobController(JobService jobService) {
        this.jobService = jobService;
        this.objectMapper = new ObjectMapper();
    }

    @PostMapping("/nfs")
    public ResponseEntity<String> processNfs(@RequestBody NfsRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            jobService.scheduleNFsProcessing(payload);
            return ResponseEntity.accepted().body("Nota Fiscal na fila!");
        } catch (JsonProcessingException e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao processar: " + e.getMessage());
        }
    }
}
