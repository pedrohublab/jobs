package hub.pedro.jobs.api.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class PayloadSizeFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PayloadSizeFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        long maxSize = 102400L;
        long requestSize = request.getContentLengthLong();

        log.info("Request size: {} bytes", requestSize);

        if (requestSize > maxSize) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.getWriter().write("Payload size exceeds maximum allowed limit of " + maxSize + " bytes.");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
