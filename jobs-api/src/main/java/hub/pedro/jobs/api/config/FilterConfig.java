package hub.pedro.jobs.api.config;

import hub.pedro.jobs.api.web.filter.PayloadSizeFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {
    @Bean
    public FilterRegistrationBean<PayloadSizeFilter> filterRegistrationBean() {
        FilterRegistrationBean<PayloadSizeFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PayloadSizeFilter());
        registration.addUrlPatterns("/api/jobs/nfs", "/api/jobs/nfs/*");
        return registration;
    }
}
