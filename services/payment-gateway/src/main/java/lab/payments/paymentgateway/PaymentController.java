package lab.payments.paymentgateway;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentController {

    private final PaymentService service;

    PaymentController(PaymentService service) {
        this.service = service;
    }

    /** X-Client-Id is trusted in the lab; a real deployment derives it from authentication. */
    @PostMapping("/payments")
    ResponseEntity<PaymentAccepted> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Client-Id") String clientId,
            @RequestBody PaymentRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.accept(clientId, idempotencyKey, request));
    }

    @GetMapping("/payments/{id}")
    ResponseEntity<PaymentView> get(@PathVariable UUID id,
            @RequestHeader("X-Client-Id") String clientId) {
        return service.view(id, clientId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
