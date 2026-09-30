package lab.payments.paymentgateway;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * F-05 (baseline, intentional): a BLOCKING HTTP call to the client's webhook inside the payments.posted listener
 * thread. With one consumer thread (F-03), throughput of status updates is capped at one over the webhook latency, a
 * slow webhook delays every later status update, and worst-case time per poll can exceed max.poll.interval.ms.
 *
 * Correctness is deliberately preserved: this runs AFTER the status update has committed (PostedListener uses
 * autocommit), never inside a database transaction, and every failure is caught and counted. So a slow or failing
 * webhook cannot lose or double a status update. Notification itself is at-most-once here: a crash between the commit
 * and the call loses it, and a failed call is not retried.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f05", havingValue = "false", matchIfMissing = true)
class BlockingWebhookNotifier implements PostedNotifier {

    private static final Logger log = LoggerFactory.getLogger(BlockingWebhookNotifier.class);

    private final MeterRegistry meters;
    private final String urlTemplate;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    BlockingWebhookNotifier(MeterRegistry meters,
            @Value("${lab.notification.webhook-url:http://localhost:8083/webhooks/{clientId}}") String urlTemplate) {
        this.meters = meters;
        this.urlTemplate = urlTemplate;
    }

    @Override
    public void notifyPosted(PaymentPosted event) {
        long started = System.nanoTime();
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(
                            URI.create(urlTemplate.replace("{clientId}", event.clientId())))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", event.paymentId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(event))).build(),
                    HttpResponse.BodyHandlers.discarding());
            meters.counter(response.statusCode() / 100 == 2 ? "notifications.sent" : "notifications.failed").increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            meters.counter("notifications.failed").increment();
            log.debug("webhook call failed for {}: {}", event.paymentId(), e.toString());
        } finally {
            meters.timer("notifications.blocking.call").record(java.time.Duration.ofNanos(System.nanoTime() - started));
        }
    }
}
