package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import lab.payments.common.Ids;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

/** F-12: record key and partition count differ by profile; ordering does not (ADR-0002). */
class PartitioningTest {

    private static final String CLIENT = "client-partitioning";

    @Test
    void recordKeyAndPartitionCountFollowTheProfile() {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String merchant = "MER-" + UUID.randomUUID().toString().substring(0, 6);
        String key = "k-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, merchant, 10)).statusCode()).isEqualTo(202);
        String paymentId = Ids.paymentId(CLIENT, key).toString();
        Lab.await("terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ?::uuid AND status_rank = 3", paymentId) == 1);

        String expected = Lab.tuned() ? debtor : merchant;
        assertThat(Lab.keyOf(Topics.INITIATED, paymentId)).isEqualTo(expected);
        assertThat(Lab.keyOf(Topics.VALIDATED, paymentId)).isEqualTo(expected);
        assertThat(Lab.keyOf(Topics.POSTED, paymentId)).isEqualTo(expected);

        int partitions = Lab.tuned() ? 12 : 3;
        for (String topic : new String[] {Topics.INITIATED, Topics.VALIDATED, Topics.POSTED,
                Topics.INITIATED + ".DLT", Topics.VALIDATED + ".DLT"}) {
            assertThat(Lab.partitionCount(topic)).as(topic).isEqualTo(partitions);
        }
    }
}
