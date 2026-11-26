package ws.fabricmod.networksocket.protocol;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates requestid values ([A-Za-z0-9_-]{1,64}) and Unix timestamps.
 *
 * <p>12 random bytes encode to 16 URL-safe base64 characters (96 bits), which leaves
 * enough room inside the 256-byte frame for reasonably long names and makes accidental
 * reuse inside a 30-second window vanishingly unlikely.</p>
 */
public final class RequestIds {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_BYTES = 12;

    private RequestIds() {
    }

    public static String newRequestId() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Current Unix time in whole seconds. */
    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    public static boolean isValid(String requestId) {
        if (requestId == null || requestId.isEmpty()
                || requestId.length() > ProtocolConstants.REQUEST_ID_MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < requestId.length(); i++) {
            char c = requestId.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}