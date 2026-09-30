package lab.payments.paymentgateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentInitiated;
import lab.payments.common.Topics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    static final String ACCEPTED = "ACCEPTED";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    private final boolean keyByDebtor;
    private final boolean outbox;
    private final TransactionTemplate tx;
    private final Counter accepted;
    private final Counter replayed;

    public PaymentService(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, MeterRegistry meters,
            @Value("${lab.tuning.f12:false}") boolean keyByDebtor,
            @Value("${lab.tuning.f07:false}") boolean outbox, TransactionTemplate tx) {
        this.keyByDebtor = keyByDebtor;
        this.outbox = outbox;
        this.tx = tx;
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.accepted = meters.counter("payments.accepted");
        this.replayed = meters.counter("payments.idempotent.replays");
    }

    /**
     * Idempotent accept. Baseline shape (intentional anti-patterns):
     * F-07: the Kafka send happens INSIDE the DB transaction, holding a connection, the advisory
     *       lock and the debtor sequence row lock for the whole broker round trip.
     * F-01: the send is synchronous (send().get()) per request.
     * F-06: duplicate lookup is a sequential scan (no index on the idempotency key).
     * Failure mode: if the commit fails after a successful send, the client retries, derives the
     * same paymentId and debtorSeq, and the ledger's dedup on paymentId absorbs the extra event.
     */
    public PaymentAccepted accept(String clientId, String idempotencyKey, PaymentRequest req) {
        if (!req.isValid()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
        }
        if (outbox) {
            return acceptWithOutbox(clientId, idempotencyKey, req);
        }
        return tx.execute(status -> acceptBaseline(clientId, idempotencyKey, req));
    }

    /**
     * F-07 (tuned): the transaction only touches the database (no broker round trip, no advisory
     * lock). The event goes into the outbox in the same transaction and is published after commit.
     * F-06 (tuned) provides the unique (client_id, idempotency_key) index that ON CONFLICT needs.
     * If a concurrent duplicate wins the insert, this transaction is rolled back, which also
     * undoes its debtor-sequence increment (a spent number with no payment would stall the debtor),
     * and the committed row is returned as a replay.
     */
    private PaymentAccepted acceptWithOutbox(String clientId, String idempotencyKey, PaymentRequest req) {
        byte[] hash = sha256(req.canonical());
        try {
            return tx.execute(status -> {
                Optional<Existing> existing = find(clientId, idempotencyKey);
                if (existing.isPresent()) {
                    return replay(existing.get(), hash);
                }
                UUID paymentId = Ids.paymentId(clientId, idempotencyKey);
                long seq = nextDebtorSeq(req.debtorAccountId());
                PaymentAccepted response = new PaymentAccepted(paymentId, ACCEPTED);
                int inserted = jdbc.update("""
                        INSERT INTO payments(payment_id, client_id, idempotency_key, request_hash,
                            debtor_account_id, creditor_account_id, merchant_id, amount_minor, currency,
                            debtor_seq, status, status_rank, response_body)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,1,?)
                        ON CONFLICT DO NOTHING""",
                        paymentId, clientId, idempotencyKey, hash, req.debtorAccountId(),
                        req.creditorAccountId(), req.merchantId(), req.amountMinor(), req.currency(),
                        seq, ACCEPTED, Json.write(response));
                if (inserted == 0) {
                    throw new LostInsertRace();
                }
                PaymentInitiated event = new PaymentInitiated(PaymentInitiated.SCHEMA_VERSION,
                        Ids.eventId(paymentId, "initiated"), paymentId, clientId, idempotencyKey,
                        req.debtorAccountId(), req.creditorAccountId(), req.merchantId(),
                        req.amountMinor(), req.currency(), seq, Instant.now());
                String key = keyByDebtor ? req.debtorAccountId() : req.merchantId();
                jdbc.update("INSERT INTO outbox(msg_key, payload) VALUES (?,?)", key, Json.write(event));
                accepted.increment();
                return response;
            });
        } catch (LostInsertRace race) {
            return tx.execute(status -> replay(find(clientId, idempotencyKey).orElseThrow(), hash));
        }
    }

    private static class LostInsertRace extends RuntimeException {
        LostInsertRace() {
            super(null, null, false, false);
        }
    }

    private PaymentAccepted replay(Existing existing, byte[] hash) {
        if (!Arrays.equals(existing.hash(), hash)) {
            throw new Exceptions.IdempotencyKeyReusedException();
        }
        replayed.increment();
        return Json.read(existing.responseBody(), PaymentAccepted.class);
    }

    private PaymentAccepted acceptBaseline(String clientId, String idempotencyKey, PaymentRequest req) {
        if (!req.isValid()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
        }
        byte[] hash = sha256(req.canonical());

        // Serialises concurrent duplicates: the loser waits, then sees the committed row.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { },
                clientId + ":" + idempotencyKey);

        Optional<Existing> existing = find(clientId, idempotencyKey);
        if (existing.isPresent()) {
            if (!Arrays.equals(existing.get().hash(), hash)) {
                throw new Exceptions.IdempotencyKeyReusedException();
            }
            replayed.increment();
            return Json.read(existing.get().responseBody(), PaymentAccepted.class);
        }

        UUID paymentId = Ids.paymentId(clientId, idempotencyKey);
        long seq = nextDebtorSeq(req.debtorAccountId());
        PaymentAccepted response = new PaymentAccepted(paymentId, ACCEPTED);

        jdbc.update("""
                INSERT INTO payments(payment_id, client_id, idempotency_key, request_hash,
                    debtor_account_id, creditor_account_id, merchant_id, amount_minor, currency,
                    debtor_seq, status, status_rank, response_body)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,1,?)""",
                paymentId, clientId, idempotencyKey, hash, req.debtorAccountId(),
                req.creditorAccountId(), req.merchantId(), req.amountMinor(), req.currency(),
                seq, ACCEPTED, Json.write(response));

        PaymentInitiated event = new PaymentInitiated(PaymentInitiated.SCHEMA_VERSION,
                Ids.eventId(paymentId, "initiated"), paymentId, clientId, idempotencyKey,
                req.debtorAccountId(), req.creditorAccountId(), req.merchantId(),
                req.amountMinor(), req.currency(), seq, Instant.now());
        try {
            // F-12: baseline keys by merchant id (hot partition for a large merchant); tuned keys by
            // debtor account id, which also keeps one debtor's payments on one partition.
            String key = keyByDebtor ? req.debtorAccountId() : req.merchantId();
            kafka.send(Topics.INITIATED, key, Json.write(event))
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exceptions.BrokerUnavailableException(e);
        } catch (Exception e) {
            throw new Exceptions.BrokerUnavailableException(e);
        }
        accepted.increment();
        return response;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentView> view(UUID paymentId, String clientId) {
        // Another client's payment is indistinguishable from a missing one (404, not 403).
        List<PaymentView> rows = jdbc.query("""
                SELECT payment_id, status, reason_code, debtor_account_id, creditor_account_id,
                       amount_minor, currency
                FROM payments WHERE payment_id = ? AND client_id = ?""",
                (rs, i) -> new PaymentView(rs.getObject("payment_id", UUID.class),
                        rs.getString("status"), rs.getString("reason_code"),
                        rs.getString("debtor_account_id"), rs.getString("creditor_account_id"),
                        rs.getLong("amount_minor"), rs.getString("currency").trim()),
                paymentId, clientId);
        return rows.stream().findFirst();
    }

    private Optional<Existing> find(String clientId, String key) {
        return jdbc.query("""
                SELECT request_hash, response_body FROM payments
                WHERE client_id = ? AND idempotency_key = ?""",
                (rs, i) -> new Existing(rs.getBytes("request_hash"), rs.getString("response_body")),
                clientId, key).stream().findFirst();
    }

    /** Row-locks the debtor's counter until commit, so seq order equals commit order per debtor. */
    private long nextDebtorSeq(String debtorAccountId) {
        Long seq = jdbc.queryForObject("""
                INSERT INTO debtor_sequences(debtor_account_id, last_seq) VALUES (?, 1)
                ON CONFLICT (debtor_account_id)
                DO UPDATE SET last_seq = debtor_sequences.last_seq + 1
                RETURNING last_seq""", Long.class, debtorAccountId);
        return seq;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Existing(byte[] hash, String responseBody) {
    }
}
