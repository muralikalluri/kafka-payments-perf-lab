package lab.payments.paymentgateway;

import lab.payments.common.PaymentPosted;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** F-05 (tuned): the gateway makes no webhook call; notification-service consumes payments.posted on its own. */
@Component
@ConditionalOnProperty(name = "lab.tuning.f05", havingValue = "true")
class NoopNotifier implements PostedNotifier {

    @Override
    public void notifyPosted(PaymentPosted event) {
        // intentionally empty
    }
}
