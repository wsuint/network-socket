package ws.fabricmod.networksocket.protocol;

import java.util.Objects;

/**
 * One application-layer message carried in exactly one 256-byte encrypted frame.
 *
 * <pre>
 * request     : type=request,name=xxxx,power=数字,requestid=xxxx,timestamp=数字
 * response    : type=response,power=数字,tick=数字,requestid=xxxx,timestamp=数字
 * keepalive   : type=keepalive,name=name,requestid=xxxx,timestamp=数字
 * keepaliveok : type=keepaliveok,requestid=xxxx,timestamp=数字
 * </pre>
 *
 * <p>In this implementation {@code tick} is the number of game ticks for which the
 * client should hold the received redstone power (server-defined hold duration).</p>
 */
public record WireMessage(Type type, String name, int power, int tick, String requestId, long timestamp) {

    public enum Type {
        REQUEST("request"),
        RESPONSE("response"),
        KEEPALIVE("keepalive"),
        KEEPALIVE_OK("keepaliveok");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Type fromWireName(String value) throws ProtocolException {
            for (Type type : values()) {
                if (type.wireName.equals(value)) {
                    return type;
                }
            }
            throw new ProtocolException("unknown message type: " + value);
        }
    }

    public WireMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(requestId, "requestId");
        if (!RequestIds.isValid(requestId)) {
            throw new IllegalArgumentException("requestid is missing or contains illegal characters");
        }
        if (timestamp <= 0L) {
            throw new IllegalArgumentException("timestamp must be a positive Unix time");
        }
    }

    public static WireMessage request(String name, int power, String requestId, long timestamp) {
        validateName(name);
        validatePower(power);
        return new WireMessage(Type.REQUEST, name, power, 0, requestId, timestamp);
    }

    public static WireMessage response(int power, int tick, String requestId, long timestamp) {
        validatePower(power);
        validateTick(tick);
        return new WireMessage(Type.RESPONSE, null, power, tick, requestId, timestamp);
    }

    public static WireMessage keepalive(String name, String requestId, long timestamp) {
        validateName(name);
        return new WireMessage(Type.KEEPALIVE, name, 0, 0, requestId, timestamp);
    }

    public static WireMessage keepaliveOk(String requestId, long timestamp) {
        return new WireMessage(Type.KEEPALIVE_OK, null, 0, 0, requestId, timestamp);
    }

    public String encode() {
        return switch (type) {
            case REQUEST -> "type=request,name=" + name + ",power=" + power
                    + ",requestid=" + requestId + ",timestamp=" + timestamp;
            case RESPONSE -> "type=response,power=" + power + ",tick=" + tick
                    + ",requestid=" + requestId + ",timestamp=" + timestamp;
            case KEEPALIVE -> "type=keepalive,name=" + name
                    + ",requestid=" + requestId + ",timestamp=" + timestamp;
            case KEEPALIVE_OK -> "type=keepaliveok,requestid=" + requestId
                    + ",timestamp=" + timestamp;
        };
    }

    /**
     * Strict parser. Field order and field count are fixed; unknown or duplicated fields are rejected.
     */
    public static WireMessage parse(String text) throws ProtocolException {
        if (text == null) {
            throw new ProtocolException("empty plaintext");
        }
        String[] parts = text.split(",", -1);
        Type type;
        try {
            type = Type.fromWireName(field(parts[0], "type"));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new ProtocolException("empty plaintext");
        }

        try {
            return switch (type) {
                case REQUEST -> {
                    requireFields(parts, 5, type);
                    String name = field(parts[1], "name");
                    int power = parsePower(field(parts[2], "power"));
                    String requestId = field(parts[3], "requestid");
                    long timestamp = parseTimestamp(field(parts[4], "timestamp"));
                    validateName(name);
                    validateRequestId(requestId);
                    yield new WireMessage(type, name, power, 0, requestId, timestamp);
                }
                case RESPONSE -> {
                    requireFields(parts, 5, type);
                    int power = parsePower(field(parts[1], "power"));
                    int tick = parseTick(field(parts[2], "tick"));
                    String requestId = field(parts[3], "requestid");
                    long timestamp = parseTimestamp(field(parts[4], "timestamp"));
                    validateRequestId(requestId);
                    yield new WireMessage(type, null, power, tick, requestId, timestamp);
                }
                case KEEPALIVE -> {
                    requireFields(parts, 4, type);
                    String name = field(parts[1], "name");
                    String requestId = field(parts[2], "requestid");
                    long timestamp = parseTimestamp(field(parts[3], "timestamp"));
                    validateName(name);
                    validateRequestId(requestId);
                    yield new WireMessage(type, name, 0, 0, requestId, timestamp);
                }
                case KEEPALIVE_OK -> {
                    requireFields(parts, 3, type);
                    String requestId = field(parts[1], "requestid");
                    long timestamp = parseTimestamp(field(parts[2], "timestamp"));
                    validateRequestId(requestId);
                    yield new WireMessage(type, null, 0, 0, requestId, timestamp);
                }
            };
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage() == null ? "malformed message" : e.getMessage(), e);
        }
    }

    private static void requireFields(String[] parts, int expected, Type type) throws ProtocolException {
        if (parts.length != expected) {
            throw new ProtocolException("wrong field count for " + type.wireName()
                    + ": expected " + expected + ", got " + parts.length);
        }
    }

    private static String field(String part, String key) throws ProtocolException {
        int equals = part.indexOf('=');
        if (equals <= 0) {
            throw new ProtocolException("missing field: " + key);
        }
        String actualKey = part.substring(0, equals);
        if (!actualKey.equals(key)) {
            throw new ProtocolException("expected field '" + key + "', got '" + actualKey + "'");
        }
        return part.substring(equals + 1);
    }

    public static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > ProtocolConstants.NAME_MAX_BYTES) {
            throw new IllegalArgumentException("name is too long");
        }
        if (name.indexOf(',') >= 0 || name.indexOf('=') >= 0
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("name must not contain comma, equals or newline");
        }
    }

    public static void validateRequestId(String requestId) {
        if (!RequestIds.isValid(requestId)) {
            throw new IllegalArgumentException("requestid is missing or contains illegal characters");
        }
    }

    public static void validatePower(int power) {
        if (power < 0 || power > 15) {
            throw new IllegalArgumentException("power must be between 0 and 15");
        }
    }

    public static void validateTick(int tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must not be negative");
        }
    }

    public static int parsePower(String value) {
        return parseBoundedInt(value, 0, 15, "power");
    }

    public static int parseTick(String value) {
        return parseBoundedInt(value, 0, Integer.MAX_VALUE, "tick");
    }

    public static long parseTimestamp(String value) {
        if (value.isEmpty() || value.length() > 19) {
            throw new IllegalArgumentException("invalid timestamp");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("invalid timestamp");
            }
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid timestamp", e);
        }
        if (parsed <= 0L) {
            throw new IllegalArgumentException("invalid timestamp");
        }
        return parsed;
    }

    private static int parseBoundedInt(String value, int min, int max, String fieldName) {
        if (value.isEmpty() || value.length() > 10) {
            throw new IllegalArgumentException("invalid " + fieldName);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("invalid " + fieldName);
            }
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + fieldName, e);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("invalid " + fieldName);
        }
        return (int) parsed;
    }
}