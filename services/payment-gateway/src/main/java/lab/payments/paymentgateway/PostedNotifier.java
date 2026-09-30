package lab.payments.paymentgateway;

import lab.payments.common.PaymentPosted;

/** What the gateway does about a payment reaching its terminal status: F-05 baseline calls the webhook, tuned does not. */
interface PostedNotifier {

    void notifyPosted(PaymentPosted event);
}
