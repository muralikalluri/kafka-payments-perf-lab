package lab.payments.notificationservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Behaviour of the webhook simulator; changeable at run time through {@link SimulatorController}. */
@Component
public class SimulatorConfig {

    private volatile long latencyMs;
    private volatile double failureRate;
    private volatile double failAfterAcceptRate;

    SimulatorConfig(@Value("${lab.webhook.latency-ms:5}") long latencyMs,
            @Value("${lab.webhook.failure-rate:0}") double failureRate,
            @Value("${lab.webhook.fail-after-accept-rate:0}") double failAfterAcceptRate) {
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
        this.failAfterAcceptRate = failAfterAcceptRate;
    }

    long latencyMs() { return latencyMs; }
    double failureRate() { return failureRate; }
    double failAfterAcceptRate() { return failAfterAcceptRate; }

    void update(Long latencyMs, Double failureRate, Double failAfterAcceptRate) {
        if (latencyMs != null) this.latencyMs = latencyMs;
        if (failureRate != null) this.failureRate = failureRate;
        if (failAfterAcceptRate != null) this.failAfterAcceptRate = failAfterAcceptRate;
    }
}
