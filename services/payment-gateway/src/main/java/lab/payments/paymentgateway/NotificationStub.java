package lab.payments.paymentgateway;

import lab.payments.common.PaymentPosted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** MVP: notification-service is stubbed (SPEC §12). The real webhook simulator is "Later". */
@Component
class NotificationStub {

    private static final Logger log = LoggerFactory.getLogger(NotificationStub.class);

    void notifyOutcome(PaymentPosted event) {
        log.debug("notification stub: payment {} -> {}", event.paymentId(), event.outcome());
    }
}
