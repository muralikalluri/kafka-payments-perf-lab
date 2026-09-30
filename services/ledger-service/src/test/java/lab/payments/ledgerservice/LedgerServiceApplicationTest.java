package lab.payments.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@SpringBootTest(properties = LedgerServiceApplication.CONFIG_NAME)
class LedgerServiceApplicationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAndAccountsAreSeeded() {
        Integer demo = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id LIKE 'ACC-%'", Integer.class);
        assertThat(demo).isEqualTo(1000);
        // F-11: one baseline settlement account plus the shard accounts, all zero and never negative.
        Integer settlement = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE id LIKE 'SETTLE-%' AND balance_minor = 0 AND overdraft = false", Integer.class);
        assertThat(settlement).isEqualTo(17);
    }

    /** T9: the CHECK constraint is a backstop even if application code is wrong. */
    @Test
    void databaseRejectsNegativeBalanceWithoutOverdraft() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE accounts SET balance_minor = -1 WHERE id = 'ACC-0001'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
