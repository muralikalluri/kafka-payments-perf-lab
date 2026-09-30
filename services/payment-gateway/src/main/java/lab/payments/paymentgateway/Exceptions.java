package lab.payments.paymentgateway;

final class Exceptions {

    private Exceptions() {
    }

    static class IdempotencyKeyReusedException extends RuntimeException {
        IdempotencyKeyReusedException() {
            super("Idempotency-Key was already used with a different request body");
        }
    }

    static class BrokerUnavailableException extends RuntimeException {
        BrokerUnavailableException(Throwable cause) {
            super("Could not publish payment event", cause);
        }
    }
}
