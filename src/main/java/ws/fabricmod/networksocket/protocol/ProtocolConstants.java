package ws.fabricmod.networksocket.protocol;

/**
 * Wire-level constants shared by the client and the reference server.
 */
public final class ProtocolConstants {
    /** Fixed TCP frame size. One frame == exactly one application message. */
    public static final int FRAME_SIZE = 256;

    /** AES-GCM nonce size in bytes. */
    public static final int NONCE_SIZE = 12;

    /** AES-GCM authentication tag size in bytes. */
    public static final int TAG_SIZE = 16;

    /** One fixed trailing newline after the encrypted payload. */
    public static final int FRAME_TRAILER_SIZE = 1;

    /**
     * Plaintext is padded with newlines to this size before encryption. The encrypted
     * output is therefore always the same length, so the receiver never needs a
     * plaintext length field.
     */
    public static final int PADDED_PLAINTEXT_SIZE =
            FRAME_SIZE - NONCE_SIZE - TAG_SIZE - FRAME_TRAILER_SIZE;

    /** Padding byte inside the encrypted plaintext and the final frame trailer. */
    public static final byte PAD = (byte) '\n';

    /** Maximum accepted absolute difference between sender and receiver Unix time. */
    public static final long MAX_CLOCK_SKEW_SECONDS = 10;

    /** requestid charset and length. */
    public static final int REQUEST_ID_MAX_LENGTH = 64;

    /** Maximum number of times one requestid may be counted before the connection is dropped. */
    public static final int REPLAY_COUNT_LIMIT = 10;

    /** Receive-side request count table lifetime, in seconds. */
    public static final long REPLAY_WINDOW_SECONDS = 30;

    /** Maximum configured name size in UTF-8 bytes (leaves room for the worst-case request fields). */
    public static final int NAME_MAX_BYTES = 80;

    private ProtocolConstants() {
    }
}