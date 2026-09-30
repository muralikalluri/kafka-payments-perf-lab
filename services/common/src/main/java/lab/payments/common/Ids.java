package lab.payments.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Deterministic identifiers so retries and redeliveries produce identical ids. */
public final class Ids {

    private static final UUID NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private Ids() {
    }

    public static UUID paymentId(String clientId, String idempotencyKey) {
        return uuidV5(clientId + ":" + idempotencyKey);
    }

    public static UUID eventId(UUID paymentId, String stage) {
        return uuidV5(paymentId + ":" + stage);
    }

    static UUID uuidV5(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits())
                    .array());
            byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            ByteBuffer buf = ByteBuffer.wrap(h);
            return new UUID(buf.getLong(), buf.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
