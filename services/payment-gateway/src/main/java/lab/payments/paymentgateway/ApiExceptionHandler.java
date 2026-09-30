package lab.payments.paymentgateway;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(Exceptions.IdempotencyKeyReusedException.class)
    ResponseEntity<Map<String, String>> reused(Exceptions.IdempotencyKeyReusedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "IDEMPOTENCY_KEY_REUSED"));
    }

    @ExceptionHandler(Exceptions.BrokerUnavailableException.class)
    ResponseEntity<Map<String, String>> broker(Exceptions.BrokerUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "BROKER_UNAVAILABLE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
