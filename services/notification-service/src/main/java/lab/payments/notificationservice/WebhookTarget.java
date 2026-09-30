package lab.payments.notificationservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Where the dispatcher delivers: the configured URL template, or this service's own simulator endpoint. */
@Component
class WebhookTarget {

    private final String template;
    private volatile int localPort;

    WebhookTarget(@Value("${lab.notification.webhook-url:}") String template) {
        this.template = template;
    }

    @EventListener
    void onStarted(WebServerInitializedEvent event) {
        this.localPort = event.getWebServer().getPort();
    }

    String urlFor(String clientId) {
        String base = template == null || template.isBlank()
                ? "http://localhost:" + localPort + "/webhooks/{clientId}" : template;
        return base.replace("{clientId}", clientId);
    }
}
