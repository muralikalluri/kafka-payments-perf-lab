package lab.payments.paymentgateway;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts POST requests by the kind of thread serving them, so it is observable that F-10 (virtual threads for the
 * gateway's blocking I/O) is really in effect rather than just configured.
 */
@Component
class RequestThreadMetrics extends OncePerRequestFilter {

    private final MeterRegistry meters;

    RequestThreadMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod())) {
            meters.counter("gateway.requests.threads", "kind",
                    Thread.currentThread().isVirtual() ? "virtual" : "platform").increment();
        }
        chain.doFilter(request, response);
    }
}
