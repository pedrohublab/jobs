package hub.pedro.jobs;

import org.springframework.boot.SpringApplication;

public class TestJobsApplication {

    public static void main(String[] args) {
        SpringApplication.from(JobsApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
