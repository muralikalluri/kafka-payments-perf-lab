package lab.payments.ledgerservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which settlement account a transaction posts through.
 *
 * F-11 (baseline, intentional): one account per currency for every posting, so every payment takes a row lock on the
 * same row and payments serialise on it once more than one consumer thread runs.
 * F-11 (tuned): one of several shard accounts per transaction, chosen from the source partition, so concurrent
 * consumer threads rarely share a settlement row. Because a payment's two settlement legs net to zero inside its own
 * transaction, the choice needs no coordination and every shard is zero after every commit.
 */
@Component
class SettlementAccounts {

    static final String PREFIX = "SETTLE-";

    private final boolean sharded;
    private final int shards;

    SettlementAccounts(@Value("${lab.tuning.f11:false}") boolean sharded, @Value("${lab.settlement.shards:16}") int shards) {
        this.sharded = sharded;
        this.shards = shards;
    }

    /** @param shardHint any int (the source partition); ignored when not sharded */
    String id(String currency, int shardHint) {
        return sharded ? String.format("%s%s-%02d", PREFIX, currency, Math.floorMod(shardHint, shards)) : PREFIX + currency;
    }

    boolean isSharded() {
        return sharded;
    }

    /** Settlement accounts are not customer accounts and can never be a payment's debtor or creditor. */
    static boolean isReserved(String accountId) {
        return accountId != null && accountId.startsWith(PREFIX);
    }
}
