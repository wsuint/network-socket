package ws.fabricmod.networksocket.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import ws.fabricmod.networksocket.NetworkSocket;
import ws.fabricmod.networksocket.block.TcpInterfaceBlockEntity;
import ws.fabricmod.networksocket.config.ConnectionEntry;
import ws.fabricmod.networksocket.config.ConnectionStatus;
import ws.fabricmod.networksocket.config.NetworkConfig;
import ws.fabricmod.networksocket.network.payload.BlockNameResultS2C;
import ws.fabricmod.networksocket.network.payload.ConfigDataS2C;
import ws.fabricmod.networksocket.network.payload.ConnectionEntryView;
import ws.fabricmod.networksocket.network.payload.OpenBlockScreenS2C;
import ws.fabricmod.networksocket.network.payload.SaveConfigC2S;
import ws.fabricmod.networksocket.network.payload.TableS2C;
import ws.fabricmod.networksocket.permission.AccessControl;
import ws.fabricmod.networksocket.protocol.ProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns every TCP connection of one running Minecraft server and keeps the per-map
 * configuration table in sync with the connection state.
 *
 * <p>All mutable Minecraft/config state is only touched on the server thread. Network
 * threads post their callbacks through {@link #submit(Runnable)}.</p>
 */
public final class ConnectionManager {
    private static volatile ConnectionManager instance;

    private final MinecraftServer server;
    private final NetworkConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, TcpConnection> connections = new ConcurrentHashMap<>();
    private final Set<TcpInterfaceBlockEntity> blocks =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Set<TcpInterfaceBlockEntity>> blocksByName = new HashMap<>();
    private final Map<UUID, List<Component>> pendingNotices = new HashMap<>();
    private volatile boolean shuttingDown;

    private ConnectionManager(MinecraftServer server, NetworkConfig config) {
        this.server = server;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tcp-interface-manager");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::maintainConnections, 250L, 250L, TimeUnit.MILLISECONDS);
    }

    public static ConnectionManager get(MinecraftServer server) {
        ConnectionManager current = instance;
        return current != null && current.server == server ? current : null;
    }

    public static synchronized void start(MinecraftServer server) {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
        ConnectionManager manager = new ConnectionManager(server, NetworkConfig.get(server));
        instance = manager;
        manager.reconnectAll();
        NetworkSocket.LOGGER.info("TCP interface connection manager started");
    }

    public static synchronized void stop(MinecraftServer server) {
        ConnectionManager current = instance;
        if (current != null && current.server == server) {
            current.shutdown();
            instance = null;
        }
    }

    public NetworkConfig config() {
        return config;
    }

    private void maintainConnections() {
        long now = System.currentTimeMillis();
        for (TcpConnection connection : connections.values()) {
            try {
                connection.maintain(now);
            } catch (RuntimeException e) {
                NetworkSocket.LOGGER.error("Error while maintaining TCP connection {}", connection.name(), e);
            }
        }
    }

    private void submit(Runnable task) {
        if (shuttingDown) {
            return;
        }
        try {
            server.execute(() -> {
                if (!shuttingDown) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            NetworkSocket.LOGGER.debug("Server is shutting down; dropping TCP callback");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Block registry                                                      */
    /* ------------------------------------------------------------------ */

    public void registerBlock(TcpInterfaceBlockEntity blockEntity) {
        if (shuttingDown) {
            return;
        }
        blocks.add(blockEntity);
        addToNameMap(blockEntity.getName(), blockEntity);
    }

    public void unregisterBlock(TcpInterfaceBlockEntity blockEntity) {
        blocks.remove(blockEntity);
        for (Map.Entry<String, Set<TcpInterfaceBlockEntity>> entry : blocksByName.entrySet()) {
            entry.getValue().remove(blockEntity);
        }
        blocksByName.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void renameBlock(TcpInterfaceBlockEntity blockEntity, String oldName, String newName) {
        if (shuttingDown) {
            return;
        }
        blocks.add(blockEntity);
        if (!oldName.isEmpty()) {
            Set<TcpInterfaceBlockEntity> oldSet = blocksByName.get(oldName);
            if (oldSet != null) {
                oldSet.remove(blockEntity);
                if (oldSet.isEmpty()) {
                    blocksByName.remove(oldName);
                }
            }
        }
        addToNameMap(newName, blockEntity);
    }

    private void addToNameMap(String name, TcpInterfaceBlockEntity blockEntity) {
        if (name == null || name.isEmpty()) {
            return;
        }
        blocksByName.computeIfAbsent(name, ignored ->
                Collections.newSetFromMap(new IdentityHashMap<>())).add(blockEntity);
    }

    /* ------------------------------------------------------------------ */
    /* Redstone / response handling                                        */
    /* ------------------------------------------------------------------ */

    public void onRedstoneChanged(TcpInterfaceBlockEntity blockEntity, int power) {
        if (shuttingDown) {
            return;
        }
        String name = blockEntity.getName();
        if (name.isEmpty()) {
            NetworkSocket.LOGGER.debug("Redstone changed but the TCP interface block has no name");
            return;
        }
        ConnectionEntry entry = config.getEntry(name);
        if (entry == null || entry.status() != ConnectionStatus.CONNECTED) {
            NetworkSocket.LOGGER.debug("Redstone changed for '{}' but status is {}",
                    name, entry == null ? "not-in-table" : entry.status());
            return;
        }
        TcpConnection connection = connections.get(name);
        if (connection == null || !connection.isConnected()) {
            NetworkSocket.LOGGER.debug("Redstone changed for '{}' but the TCP connection is missing", name);
            return;
        }
        NetworkSocket.LOGGER.info("Sending TCP request '{}' power={}", name, power);
        connection.sendRequest(power);
    }

    private void handleResponse(TcpConnection connection, int power, int tick) {
        if (shuttingDown || connections.get(connection.name()) != connection) {
            return;
        }
        ConnectionEntry entry = config.getEntry(connection.name());
        if (entry == null || entry.status() != ConnectionStatus.CONNECTED) {
            return;
        }
        Set<TcpInterfaceBlockEntity> targets = blocksByName.get(connection.name());
        if (targets == null || targets.isEmpty()) {
            NetworkSocket.LOGGER.debug("No loaded TCP interface block is registered for name '{}'",
                    connection.name());
            return;
        }
        NetworkSocket.LOGGER.info("Received TCP response for '{}' power={} tick={}",
                connection.name(), power, tick);
        for (TcpInterfaceBlockEntity target : List.copyOf(targets)) {
            target.applyResponse(power, tick);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Connection lifecycle                                                */
    /* ------------------------------------------------------------------ */

    private void reconnectAll() {
        for (ConnectionEntry entry : config.sortedEntries()) {
            if (entry.status() == ConnectionStatus.CONNECTING) {
                entry.setStatus(ConnectionStatus.NOT_CONNECTED);
            }
        }
        save();

        for (ConnectionEntry entry : config.sortedEntries()) {
            if (entry.status() != ConnectionStatus.CONNECTED) {
                continue;
            }
            if (connections.size() >= config.maxConnectCount()) {
                entry.setStatus(ConnectionStatus.DISCONNECTED);
                notifyOwner(entry, "message.network-socket.max_connections",
                        String.valueOf(config.maxConnectCount()));
                continue;
            }
            entry.setStatus(ConnectionStatus.CONNECTING);
            connect(entry);
        }
        save();
        broadcastTable();
    }

    private void connect(ConnectionEntry entry) {
        if (shuttingDown) {
            return;
        }
        TcpConnection previous = connections.remove(entry.name());
        if (previous != null) {
            previous.closeSilently();
        }
        if (connections.size() >= config.maxConnectCount()) {
            entry.setStatus(ConnectionStatus.DISCONNECTED);
            save();
            broadcastTable();
            notifyOwner(entry, "message.network-socket.max_connections",
                    String.valueOf(config.maxConnectCount()));
            return;
        }

        ConnectionSettings settings = new ConnectionSettings(
                config.address(), config.port(), config.token(),
                config.keepaliveTime(), config.timeout());
        TcpConnection connection = new TcpConnection(entry.name(), settings, new ListenerImpl());
        connections.put(entry.name(), connection);
        connection.start();
        NetworkSocket.LOGGER.info("Connecting TCP interface '{}' to {}:{}", entry.name(),
                settings.address(), settings.port());
    }

    public void disconnect(String name, boolean updateStatus) {
        TcpConnection connection = connections.remove(name);
        if (connection != null) {
            connection.closeSilently();
        }
        if (updateStatus) {
            ConnectionEntry entry = config.getEntry(name);
            if (entry != null) {
                entry.setStatus(ConnectionStatus.DISCONNECTED);
                save();
                broadcastTable();
            }
        }
    }

    private void handleConnected(TcpConnection connection) {
        if (shuttingDown) {
            connection.closeSilently();
            return;
        }
        if (connections.get(connection.name()) != connection) {
            connection.closeSilently();
            return;
        }
        ConnectionEntry entry = config.getEntry(connection.name());
        if (entry == null || entry.status() != ConnectionStatus.CONNECTING) {
            connections.remove(connection.name(), connection);
            connection.closeSilently();
            return;
        }
        entry.setStatus(ConnectionStatus.CONNECTED);
        save();
        broadcastTable();
        notifyOwner(entry, "message.network-socket.connect_success", connection.name());
        NetworkSocket.LOGGER.info("TCP interface '{}' connected", connection.name());
    }

    private void handleConnectFailed(TcpConnection connection, String detail) {
        if (!takeIfCurrent(connection)) {
            return;
        }
        ConnectionEntry entry = config.getEntry(connection.name());
        if (entry != null && (entry.status() == ConnectionStatus.CONNECTING
                || entry.status() == ConnectionStatus.CONNECTED)) {
            entry.setStatus(ConnectionStatus.DISCONNECTED);
            save();
            broadcastTable();
            notifyOwner(entry, "message.network-socket.connect_failed",
                    connection.name(), detail == null ? "" : detail);
        }
        NetworkSocket.LOGGER.warn("TCP interface '{}' failed to connect: {}", connection.name(), detail);
    }

    private void handleDisconnected(TcpConnection connection, TcpConnection.DisconnectReason reason) {
        if (!takeIfCurrent(connection)) {
            return;
        }
        ConnectionEntry entry = config.getEntry(connection.name());
        if (entry != null && (entry.status() == ConnectionStatus.CONNECTING
                || entry.status() == ConnectionStatus.CONNECTED)) {
            entry.setStatus(ConnectionStatus.DISCONNECTED);
            save();
            broadcastTable();
            String key = switch (reason) {
                case HEARTBEAT_TIMEOUT -> "message.network-socket.heartbeat_timeout";
                case REPLAY_ABUSE -> "message.network-socket.replay_abort";
                case SEND_FAILED, IO_ERROR -> "message.network-socket.connection_lost";
                case CONNECT_FAILED -> "message.network-socket.connect_failed";
            };
            notifyOwner(entry, key, connection.name());
        }
        NetworkSocket.LOGGER.info("TCP interface '{}' disconnected ({})", connection.name(), reason);
    }

    /** Removes the connection if it is still the current one for its name. */
    private boolean takeIfCurrent(TcpConnection connection) {
        if (shuttingDown) {
            connection.closeSilently();
            return false;
        }
        return connections.remove(connection.name(), connection);
    }

    private final class ListenerImpl implements TcpConnection.Listener {
        private ListenerImpl() {
        }

        @Override
        public void onConnected(TcpConnection connection) {
            submit(() -> handleConnected(connection));
        }

        @Override
        public void onConnectFailed(TcpConnection connection, String detail) {
            submit(() -> handleConnectFailed(connection, detail));
        }

        @Override
        public void onDisconnected(TcpConnection connection, TcpConnection.DisconnectReason reason) {
            submit(() -> handleDisconnected(connection, reason));
        }

        @Override
        public void onResponse(TcpConnection connection, int power, int tick) {
            submit(() -> handleResponse(connection, power, tick));
        }

        @Override
        public void onKeepaliveOk(TcpConnection connection) {
            // Nothing else to do; the receive counters were already updated by the connection.
        }
    }

    /* ------------------------------------------------------------------ */
    /* Block removal / name changes                                        */
    /* ------------------------------------------------------------------ */

    public void removeBlockConfig(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        TcpConnection connection = connections.remove(name);
        if (connection != null) {
            connection.closeSilently();
        }
        ConnectionEntry removed = config.removeEntry(name);
        if (removed != null) {
            save();
            broadcastTable();
            NetworkSocket.LOGGER.info("Removed TCP interface config '{}' because its block was removed", name);
        }
    }

    public void openBlockScreen(ServerPlayer player, BlockPos pos) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TcpInterfaceBlockEntity tcp)) {
            return;
        }
        String name = tcp.getName();
        ConnectionEntry entry = config.getEntry(name);
        ConnectionStatus status = entry == null ? ConnectionStatus.NOT_CONNECTED : entry.status();
        ServerPlayNetworking.send(player, new OpenBlockScreenS2C(pos, name, status.ordinal()));
    }

    public void setBlockName(ServerPlayer player, BlockPos pos, String requestedName) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TcpInterfaceBlockEntity block)) {
            return;
        }

        String newName = requestedName == null ? "" : requestedName;
        ConnectionStatus currentStatus = ConnectionStatus.NOT_CONNECTED;

        if (!newName.isEmpty()) {
            try {
                ws.fabricmod.networksocket.protocol.WireMessage.validateName(newName);
            } catch (IllegalArgumentException e) {
                player.sendSystemMessage(Component.translatable(
                        isNameTooLong(newName)
                                ? "message.network-socket.name_too_long"
                                : "message.network-socket.name_invalid"));
                ConnectionEntry existing = config.getEntry(block.getName());
                ServerPlayNetworking.send(player, new BlockNameResultS2C(false,
                        block.getName(), existing == null ? 0 : existing.status().ordinal()));
                return;
            }
        }

        String oldName = block.getName();
        if (!newName.equals(oldName) && !newName.isEmpty() && config.containsEntry(newName)) {
            player.sendSystemMessage(Component.translatable("message.network-socket.name_duplicate", newName));
            ConnectionEntry existing = config.getEntry(oldName);
            ServerPlayNetworking.send(player, new BlockNameResultS2C(false, oldName,
                    existing == null ? 0 : existing.status().ordinal()));
            return;
        }

        if (newName.equals(oldName)) {
            ConnectionEntry existing = config.getEntry(oldName);
            if (existing == null && !oldName.isEmpty()) {
                // A block loaded from a structure or moved by a piston can carry a name whose
                // table row was removed; saving the same name recreates the row in "not connected".
                existing = new ConnectionEntry(oldName, ConnectionStatus.NOT_CONNECTED, null);
                config.putEntry(existing);
                save();
                broadcastTable();
            }
            ServerPlayNetworking.send(player, new BlockNameResultS2C(true, oldName,
                    existing == null ? 0 : existing.status().ordinal()));
            return;
        }

        ConnectionEntry removed = oldName.isEmpty() ? null : config.removeEntry(oldName);
        if (removed != null) {
            TcpConnection connection = connections.remove(oldName);
            if (connection != null) {
                connection.closeSilently();
            }
        }

        block.setName(newName);
        if (!newName.isEmpty()) {
            ConnectionEntry entry = new ConnectionEntry(newName, ConnectionStatus.NOT_CONNECTED, null);
            config.putEntry(entry);
            currentStatus = entry.status();
        }
        save();
        broadcastTable();
        ServerPlayNetworking.send(player, new BlockNameResultS2C(true, newName, currentStatus.ordinal()));
        player.sendSystemMessage(Component.translatable("message.network-socket.name_saved"));
    }

    /* ------------------------------------------------------------------ */
    /* UI actions                                                          */
    /* ------------------------------------------------------------------ */

    public void sendConfigData(ServerPlayer player) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        ConfigDataS2C payload = new ConfigDataS2C(
                config.address(), config.port(), config.token(),
                config.maxConnectCount(), config.keepaliveTime(), config.timeout(),
                collectRows());
        ServerPlayNetworking.send(player, payload);
    }

    public void saveConfig(ServerPlayer player, SaveConfigC2S payload) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        String address = payload.address() == null ? "" : payload.address().trim();
        String token = payload.token() == null ? "" : payload.token();
        if (address.isEmpty() || address.length() > 255
                || payload.port() < 1 || payload.port() > 65535
                || token.isEmpty() || token.length() > 256
                || payload.maxConnectCount() < 1 || payload.maxConnectCount() > 1000
                || payload.keepaliveTime() < 1 || payload.keepaliveTime() > 86400
                || payload.timeout() < 1 || payload.timeout() > 3600) {
            player.sendSystemMessage(Component.translatable("message.network-socket.config_invalid"));
            return;
        }

        config.setGlobal(address, payload.port(), token,
                payload.maxConnectCount(), payload.keepaliveTime(), payload.timeout());
        save();
        broadcastTable();
        sendConfigData(player);
        player.sendSystemMessage(Component.translatable("message.network-socket.config_saved"));
    }

    public void toggleConnection(ServerPlayer player, String name, boolean enable) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        ConnectionEntry entry = config.getEntry(name);
        if (entry == null) {
            player.sendSystemMessage(Component.translatable("message.network-socket.entry_missing", name));
            return;
        }

        if (enable) {
            if (entry.status() == ConnectionStatus.CONNECTED
                    || entry.status() == ConnectionStatus.CONNECTING) {
                player.sendSystemMessage(Component.translatable(
                        "message.network-socket.already_connecting", name));
                return;
            }
            if (connections.size() >= config.maxConnectCount()) {
                player.sendSystemMessage(Component.translatable(
                        "message.network-socket.max_connections",
                        String.valueOf(config.maxConnectCount())));
                return;
            }
            entry.setOwner(player.getUUID());
            entry.setStatus(ConnectionStatus.CONNECTING);
            save();
            broadcastTable();
            player.sendSystemMessage(Component.translatable(
                    "message.network-socket.connect_started", name));
            connect(entry);
        } else {
            disconnect(name, false);
            entry.setStatus(ConnectionStatus.DISCONNECTED);
            save();
            broadcastTable();
            player.sendSystemMessage(Component.translatable(
                    "message.network-socket.connection_closed", name));
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!AccessControl.isAuthorized(player)) {
            pendingNotices.remove(player.getUUID());
            return;
        }
        List<Component> notices = pendingNotices.remove(player.getUUID());
        if (notices == null) {
            return;
        }
        for (Component notice : notices) {
            player.sendSystemMessage(notice);
        }
    }

    /**
     * Debug helper for /tcpi test: send a request through the existing connection.
     * Returns false (and messages the player) when the name/status/connection is not usable.
     */
    public boolean sendTestRequest(ServerPlayer player, String name, int power) {
        if (!AccessControl.isAdmin(player)) {
            return false;
        }
        ConnectionEntry entry = config.getEntry(name);
        if (entry == null) {
            player.sendSystemMessage(Component.translatable(
                    "message.network-socket.test_no_entry", name));
            return false;
        }
        if (entry.status() != ConnectionStatus.CONNECTED) {
            player.sendSystemMessage(Component.translatable(
                    "message.network-socket.test_not_connected",
                    name, Component.translatable(entry.status().translationKey())));
            return false;
        }
        TcpConnection connection = connections.get(name);
        if (connection == null || !connection.isConnected()) {
            player.sendSystemMessage(Component.translatable(
                    "message.network-socket.test_unavailable"));
            return false;
        }
        NetworkSocket.LOGGER.info("Sending TEST TCP request '{}' power={}", name, power);
        connection.sendRequest(power);
        player.sendSystemMessage(Component.translatable(
                "message.network-socket.test_sent", name, String.valueOf(power)));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static boolean isNameTooLong(String name) {
        return name.getBytes(StandardCharsets.UTF_8).length > ProtocolConstants.NAME_MAX_BYTES;
    }

    private List<ConnectionEntryView> collectRows() {
        List<ConnectionEntryView> rows = new ArrayList<>();
        for (ConnectionEntry entry : config.sortedEntries()) {
            rows.add(new ConnectionEntryView(entry.name(), entry.status().ordinal()));
        }
        return rows;
    }

    private void broadcastTable() {
        if (server.getPlayerList() == null) {
            return;
        }
        TableS2C payload = new TableS2C(collectRows());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (AccessControl.isAuthorized(player)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private void save() {
        config.setDirty();
    }

    private void notifyOwner(ConnectionEntry entry, String key, Object... args) {
        if (entry == null || entry.owner() == null) {
            NetworkSocket.LOGGER.info("Notification for '{}': {} (no owner online)",
                    entry == null ? "?" : entry.name(), key);
            return;
        }
        Component message = Component.translatable(key, args);
        ServerPlayer player = server.getPlayerList() == null
                ? null : server.getPlayerList().getPlayer(entry.owner());
        if (player != null) {
            if (AccessControl.isAuthorized(player)) {
                player.sendSystemMessage(message);
            }
            return;
        }
        List<Component> queued = pendingNotices.computeIfAbsent(entry.owner(), ignored -> new ArrayList<>());
        if (queued.size() >= 8) {
            queued.remove(0);
        }
        queued.add(message);
    }

    private void shutdown() {
        shuttingDown = true;
        scheduler.shutdownNow();
        for (TcpConnection connection : connections.values()) {
            connection.closeSilently();
        }
        connections.clear();
        blocks.clear();
        blocksByName.clear();
        pendingNotices.clear();
    }
}