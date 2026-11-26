package ws.fabricmod.networksocket.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM with a fixed 256-byte frame and no base64.
 *
 * <p>Frame layout:</p>
 * <pre>
 * [ nonce 12 ][ AES-GCM ciphertext 227 + tag 16 ][ '\n' 1 ] = 256 bytes
 * </pre>
 *
 * <p>The application plaintext is UTF-8 encoded, padded with {@code '\n'} to exactly
 * {@link ProtocolConstants#PADDED_PLAINTEXT_SIZE} bytes, and then encrypted. Because the
 * encrypted length is constant, the receiver knows exactly how many bytes to decrypt
 * without transmitting an unauthenticated length field. The final frame byte is a fixed
 * trailer; the padding inside the ciphertext is authenticated by GCM.</p>
 *
 * <p>Key: {@code SHA-256(token UTF-8)}.</p>
 */
public final class FrameCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte PAD = ProtocolConstants.PAD;

    private FrameCodec() {
    }

    public static byte[] keyFromToken(String token) {
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Encrypts one application message into exactly 256 bytes.
     *
     * @throws ProtocolException if the UTF-8 plaintext exceeds 227 bytes
     */
    public static byte[] encrypt(String plaintext, byte[] key) throws ProtocolException {
        if (plaintext == null) {
            throw new ProtocolException("plaintext must not be null");
        }
        byte[] raw = plaintext.getBytes(StandardCharsets.UTF_8);
        if (raw.length > ProtocolConstants.PADDED_PLAINTEXT_SIZE) {
            throw new ProtocolException("plaintext is " + raw.length + " bytes, exceeding the "
                    + ProtocolConstants.PADDED_PLAINTEXT_SIZE + "-byte limit");
        }

        byte[] padded = new byte[ProtocolConstants.PADDED_PLAINTEXT_SIZE];
        Arrays.fill(padded, PAD);
        System.arraycopy(raw, 0, padded, 0, raw.length);

        byte[] nonce = new byte[ProtocolConstants.NONCE_SIZE];
        RANDOM.nextBytes(nonce);

        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(ProtocolConstants.TAG_SIZE * 8, nonce));
            ciphertext = cipher.doFinal(padded);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("AES-GCM encryption failed", e);
        }

        byte[] frame = new byte[ProtocolConstants.FRAME_SIZE];
        System.arraycopy(nonce, 0, frame, 0, nonce.length);
        System.arraycopy(ciphertext, 0, frame, nonce.length, ciphertext.length);
        frame[ProtocolConstants.FRAME_SIZE - 1] = PAD;
        return frame;
    }

    /**
     * Reverses {@link #encrypt(String, byte[])} and removes the authenticated newline
     * padding. A plaintext that exactly fills all 227 bytes has no padding newline.
     */
    public static String decrypt(byte[] frame, byte[] key) throws ProtocolException {
        if (frame == null || frame.length != ProtocolConstants.FRAME_SIZE) {
            throw new ProtocolException("frame must be exactly 256 bytes");
        }
        if (frame[ProtocolConstants.FRAME_SIZE - 1] != PAD) {
            throw new ProtocolException("frame is missing its trailing newline");
        }

        byte[] nonce = Arrays.copyOfRange(frame, 0, ProtocolConstants.NONCE_SIZE);
        byte[] ciphertext = Arrays.copyOfRange(frame, ProtocolConstants.NONCE_SIZE,
                ProtocolConstants.FRAME_SIZE - 1);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(ProtocolConstants.TAG_SIZE * 8, nonce));
            plaintext = cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("AES-GCM decryption failed", e);
        }

        if (plaintext.length != ProtocolConstants.PADDED_PLAINTEXT_SIZE) {
            throw new ProtocolException("unexpected plaintext length: " + plaintext.length);
        }

        int end = 0;
        while (end < plaintext.length && plaintext[end] != PAD) {
            end++;
        }
        if (end == 0) {
            throw new ProtocolException("plaintext is empty");
        }
        for (int i = end; i < plaintext.length; i++) {
            if (plaintext[i] != PAD) {
                throw new ProtocolException("invalid plaintext padding");
            }
        }
        return new String(plaintext, 0, end, StandardCharsets.UTF_8);
    }
}