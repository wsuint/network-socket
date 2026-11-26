package ws.fabricmod.networksocket.permission;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import ws.fabricmod.networksocket.NetworkSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-wide UUID whitelist. It is stored in the overworld's {@link SavedData}, so every
 * dimension of one running server sees the same list and it travels with the world save.
 *
 * <p>Whitelist membership grants configuration access only; it never grants permission to
 * manage the whitelist itself.</p>
 */
public final class WhitelistData extends SavedData {
    public static final SavedDataType<WhitelistData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(NetworkSocket.MOD_ID, "whitelist"),
            WhitelistData::new,
            CompoundTag.CODEC.xmap(WhitelistData::fromTag, WhitelistData::toTag),
            DataFixTypes.LEVEL);

    private final Set<UUID> players = new LinkedHashSet<>();

    public WhitelistData() {
    }

    public static WhitelistData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean contains(UUID playerId) {
        return playerId != null && players.contains(playerId);
    }

    public boolean add(UUID playerId) {
        if (playerId == null || !players.add(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean remove(UUID playerId) {
        if (playerId == null || !players.remove(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    public List<UUID> all() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    public int size() {
        return players.size();
    }

    private static WhitelistData fromTag(CompoundTag tag) {
        WhitelistData data = new WhitelistData();
        ListTag list = tag.getListOrEmpty("players");
        for (Tag element : list) {
            if (!(element instanceof CompoundTag entry)) {
                continue;
            }
            String text = entry.getStringOr("uuid", "");
            if (text.isEmpty()) {
                continue;
            }
            try {
                data.players.add(UUID.fromString(text));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID entries instead of failing the whole whitelist.
            }
        }
        return data;
    }

    private static CompoundTag toTag(WhitelistData data) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        ListTag list = new ListTag();
        for (UUID playerId : data.players) {
            CompoundTag entry = new CompoundTag();
            entry.putString("uuid", playerId.toString());
            list.add(entry);
        }
        tag.put("players", list);
        return tag;
    }
}