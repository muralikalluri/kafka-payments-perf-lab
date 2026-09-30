package lab.payments.validationservice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lab.payments.common.AccountUpdated;
import lab.payments.common.Json;
import lab.payments.common.Topics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately insecure for demonstration. Do not deploy.
 *
 * F-13 (tuned, lab only): changes an account's status and publishes account.updated so cached copies
 * are invalidated. It exists so the cache-invalidation path can be exercised; it is not part of the
 * payment API and is only registered when lab.tuning.f13 is on. Protected by a shared admin token
 * (placeholder value in .env.example); a real deployment would use proper authentication and a
 * management-only port.
 */
@RestController
@ConditionalOnProperty(name = "lab.tuning.f13", havingValue = "true")
class LabAdminController {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final byte[] token;

    LabAdminController(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            @Value("${lab.admin.token}") String token) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    record StatusChange(String status) {
    }

    @PutMapping("/lab-admin/accounts/{id}")
    ResponseEntity<Map<String, Object>> changeStatus(@PathVariable String id,
            @RequestHeader(value = "X-Admin-Token", required = false) String presented,
            @RequestBody StatusChange change) throws Exception {
        if (presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "FORBIDDEN"));
        }
        if (change.status() == null || !List.of("ACTIVE", "BLOCKED").contains(change.status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_STATUS"));
        }
        List<Long> versions = jdbc.queryForList(
                "UPDATE accounts SET status = ?, version = version + 1 WHERE id = ? RETURNING version",
                Long.class, change.status(), id);
        if (versions.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        long version = versions.get(0);
        // The database is already updated; if this send fails the cached copy converges by TTL.
        kafka.send(Topics.ACCOUNT_UPDATED, id, Json.write(new AccountUpdated(id, version, change.status())))
                .get(10, TimeUnit.SECONDS);
        return ResponseEntity.ok(Map.of("accountId", id, "version", version, "status", change.status()));
    }
}
