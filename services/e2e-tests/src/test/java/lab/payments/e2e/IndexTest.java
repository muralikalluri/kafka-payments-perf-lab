package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-06: the indexes are absent in baseline (intentional anti-pattern) and present when tuned. */
class IndexTest {

    private static boolean hasIndex(String schema, String table, String... columns) {
        // pg_indexes.indexdef looks like "CREATE [UNIQUE] INDEX name ON schema.table USING btree (a, b)"
        String expected = "(" + String.join(", ", columns) + ")";
        return Lab.count("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND tablename = ? AND indexdef LIKE ?",
                schema, table, "%" + expected + "%") > 0;
    }

    @Test
    void idempotencyKeyAndPostingsIndexesFollowTheProfile() {
        assertThat(hasIndex("gateway", "payments", "client_id", "idempotency_key")).isEqualTo(Lab.tuned());
        assertThat(hasIndex("ledger", "postings", "account_id", "created_at")).isEqualTo(Lab.tuned());
    }
}
