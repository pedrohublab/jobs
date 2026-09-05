package hub.pedro.jobs.api.web.api.in;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import hub.pedro.jobs.api.app.service.JobService;   

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping(value = "/nfs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> processNfs(@RequestBody byte[] rawPayLoad) {
        try {
            UUID jobId = jobService.scheduleNFsProcessing(rawPayLoad);

            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to process payload: " + e.getMessage()));
        }
    }
}
