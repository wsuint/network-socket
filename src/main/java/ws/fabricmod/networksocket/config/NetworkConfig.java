package ws.fabricmod.networksocket.config;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import ws.fabricmod.networksocket.NetworkSocket;
import ws.fabricmod.networksocket.protocol.WireMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-map persisted configuration: UI1's global network settings plus UI2's
 * name/status connection table. Stored in the overworld's {@code SavedDataStorage},
 * so it travels with the world save.
 */
public final class NetworkConfig extends SavedData {
    public static final String DEFAULT_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 9000;
    public static final String DEFAULT_TOKEN = "change-me";
    public static final int DEFAULT_MAX_CONNECT_COUNT = 10;
    public static final int DEFAULT_KEEPALIVE_TIME = 60;
    public static final int DEFAULT_TIMEOUT = 10;

    public static final SavedDataType<NetworkConfig> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NetworkSocket.MOD_ID, "network_config"),
            NetworkConfig::new,
            CompoundTag.CODEC.xmap(NetworkConfig::fromTag, NetworkConfig::toTag),
            DataFixTypes.LEVEL);

    private String address = DEFAULT_ADDRESS;
    private int port = DEFAULT_PORT;
    private String token = DEFAULT_TOKEN;
    private int maxConnectCount = DEFAULT_MAX_CONNECT_COUNT;
    private int keepaliveTime = DEFAULT_KEEPALIVE_TIME;
    private int timeout = DEFAULT_TIMEOUT;

    private final Map<String, ConnectionEntry> entries = new LinkedHashMap<>();

    public NetworkConfig() {
    }

    public static NetworkConfig get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public String address() {
        return address;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public int maxConnectCount() {
        return maxConnectCount;
    }

    public int keepaliveTime() {
        return keepaliveTime;
    }

    public int timeout() {
        return timeout;
    }

    public void setGlobal(String address, int port, String token,
                          int maxConnectCount, int keepaliveTime, int timeout) {
        this.address = address;
        this.port = port;
        this.token = token;
        this.maxConnectCount = maxConnectCount;
        this.keepaliveTime = keepaliveTime;
        this.timeout = timeout;
    }

    public ConnectionEntry getEntry(String name) {
        return entries.get(name);
    }

    public boolean containsEntry(String name) {
        return entries.containsKey(name);
    }

    public ConnectionEntry putEntry(ConnectionEntry entry) {
        return entries.put(entry.name(), entry);
    }

    public ConnectionEntry removeEntry(String name) {
        return entries.remove(name);
    }

    public Collection<ConnectionEntry> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public List<ConnectionEntry> sortedEntries() {
        List<ConnectionEntry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparing(ConnectionEntry::name));
        return result;
    }

    public int entryCount() {
        return entries.size();
    }

    private static NetworkConfig fromTag(CompoundTag tag) {
        NetworkConfig config = new NetworkConfig();
        config.address = sanitizeAddress(tag.getStringOr("address", DEFAULT_ADDRESS));
        config.port = sanitizePort(tag.getIntOr("port", DEFAULT_PORT));
        config.token = sanitizeToken(tag.getStringOr("token", DEFAULT_TOKEN));
        config.maxConnectCount = clamp(tag.getIntOr("maxConnectCount", DEFAULT_MAX_CONNECT_COUNT),
                1, 1000, DEFAULT_MAX_CONNECT_COUNT);
        config.keepaliveTime = clamp(tag.getIntOr("keepaliveTime", DEFAULT_KEEPALIVE_TIME),
                1, 86400, DEFAULT_KEEPALIVE_TIME);
        config.timeout = clamp(tag.getIntOr("timeout", DEFAULT_TIMEOUT),
                1, 3600, DEFAULT_TIMEOUT);

        ListTag list = tag.getListOrEmpty("entries");
        for (Tag element : list) {
            if (!(element instanceof CompoundTag entryTag)) {
                continue;
            }
            String name = entryTag.getStringOr("name", "");
            if (!isAcceptableName(name) || config.entries.containsKey(name)) {
                continue;
            }
            ConnectionStatus status = ConnectionStatus.byName(entryTag.getStringOr("status", ""));
            UUID owner = null;
            String ownerText = entryTag.getStringOr("owner", "");
            if (!ownerText.isEmpty()) {
                try {
                    owner = UUID.fromString(ownerText);
                } catch (IllegalArgumentException ignored) {
                    // Bad owner data is not fatal; the entry can still be used.
                }
            }
            config.entries.put(name, new ConnectionEntry(name, status, owner));
        }
        return config;
    }

    private static CompoundTag toTag(NetworkConfig config) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putString("address", config.address);
        tag.putInt("port", config.port);
        tag.putString("token", config.token);
        tag.putInt("maxConnectCount", config.maxConnectCount);
        tag.putInt("keepaliveTime", config.keepaliveTime);
        tag.putInt("timeout", config.timeout);

        ListTag list = new ListTag();
        for (ConnectionEntry entry : config.entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("name", entry.name());
            entryTag.putString("status", entry.status().name());
            if (entry.owner() != null) {
                entryTag.putString("owner", entry.owner().toString());
            }
            list.add(entryTag);
        }
        tag.put("entries", list);
        return tag;
    }

    private static boolean isAcceptableName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try {
            WireMessage.validateName(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String sanitizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_ADDRESS;
        }
        String trimmed = value.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    private static String sanitizeToken(String value) {
        if (value == null || value.isEmpty()) {
            return DEFAULT_TOKEN;
        }
        return value.length() > 256 ? value.substring(0, 256) : value;
    }

    private static int sanitizePort(int value) {
        return value < 1 || value > 65535 ? DEFAULT_PORT : value;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }
}