package hub.pedro.jobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.Bean;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
public class JobsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobsApplication.class, args);
    }

}
