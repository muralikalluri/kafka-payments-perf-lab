package lab.payments.notificationservice;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run-time control of the webhook simulator (latency and failure rates), used by the tests and by benchmark scripts.
 *
 * Deliberately insecure for demonstration. Do not deploy. (No authentication; anyone can change the simulator.)
 */
@RestController
class SimulatorController {

    record Change(Long latencyMs, Double failureRate, Double failAfterAcceptRate) {
    }

    private final SimulatorConfig config;

    SimulatorController(SimulatorConfig config) {
        this.config = config;
    }

    @GetMapping("/sim/config")
    Map<String, Object> current() {
        return Map.of("latencyMs", config.latencyMs(), "failureRate", config.failureRate(),
                "failAfterAcceptRate", config.failAfterAcceptRate());
    }

    @PutMapping("/sim/config")
    Map<String, Object> change(@RequestBody Change change) {
        config.update(change.latencyMs(), change.failureRate(), change.failAfterAcceptRate());
        return current();
    }
}
