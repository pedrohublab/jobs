package hub.pedro.jobs.api.web.api.in;

import hub.pedro.jobs.api.app.service.JobService;
import hub.pedro.jobs.api.config.FilterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest(JobController.class)
@Import(FilterConfig.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @Test
    void shouldAcceptNfsProcessingRequest() throws Exception {
        UUID expectedId = UUID.randomUUID();
        when(jobService.scheduleNFsProcessing(any())).thenReturn(expectedId);
        mockMvc.perform(post("/api/jobs/nfs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"12345678000199\",\"valor\":100.0}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(expectedId.toString()));
    }
}

