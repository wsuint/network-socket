package ws.fabricmod.networksocket.protocol;

/**
 * Any recoverable protocol error. Receivers must drop the offending frame and keep going
 * (except for the replay-count disconnect rule, which is handled by the connection).
 */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}