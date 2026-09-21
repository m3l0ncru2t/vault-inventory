package name.vaultinventory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Public entry point for other mods. Everything else in this package is internal.
 *
 * Call these on the server thread. Detect the mod first with
 * {@code FabricLoader.getInstance().isModLoaded("vault-inventory")} so your own mod still loads
 * without it - keep any reference to this class behind that check.
 */
public final class VaultInventoryApi {
    private VaultInventoryApi() {}

    /**
     * A stored snapshot.
     *
     * @param timestamp epoch millis it was taken; identifies it for {@link #restore}
     * @param label     free-text label ("bulk snapshot", "death (lava)", or whatever an admin typed), may be empty
     * @param death     true if it was taken automatically when the player died
     */
    public record SnapshotInfo(long timestamp, String label, boolean death) {}

    /** Saves a snapshot of the player's inventory, armor/offhand and ender chest, same as {@code /vault snapshot}. */
    public static void snapshot(MinecraftServer server, ServerPlayer player, String label) throws IOException {
        InventoryVaultManager.writeSnapshot(server, player, label);
    }

    /** Every stored snapshot for the player (works while they are offline), newest first. */
    public static List<SnapshotInfo> list(UUID player) {
        return InventoryVaultManager.listEntries(player).stream()
                .map(entry -> new SnapshotInfo(entry.timestamp(), InventoryVaultManager.readLabel(entry.file()), entry.death()))
                .toList();
    }

    /**
     * Restores the snapshot taken at {@code timestamp} into the online player, saving their current state
     * to the safety slot first (undo with {@code /vault restore <player> safety}).
     *
     * @return false if no snapshot with that timestamp exists (for example it was pruned)
     */
    public static boolean restore(MinecraftServer server, ServerPlayer player, long timestamp) throws IOException {
        for (InventoryVaultManager.Entry entry : InventoryVaultManager.listEntries(player.getUUID())) {
            if (entry.timestamp() == timestamp) {
                CompoundTag tag = InventoryVaultManager.readTag(entry.file());
                InventoryVaultManager.applyWithSafety(server, player, tag, InventoryVaultManager.Part.ALL);
                return true;
            }
        }
        return false;
    }
}
