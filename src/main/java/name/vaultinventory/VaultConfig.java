package name.vaultinventory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loaded from config/vault-inventory.json at startup. The values can also be changed live with
 * /vault set (which saves the file); hand edits to the file still need a restart. Every setting is
 * a whole number (on/off toggles are 0/1) so they share one command and one listing format.
 */
final class VaultConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vault-inventory.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static VaultConfig instance;

    /** Oldest snapshots beyond this many (per player) are deleted after each new one. */
    int maxSnapshotsPerPlayer = 20;

    /** In-game ticks between automatic snapshots of every online player (24000 = one vanilla
     *  day). Set to 0 (or negative) to disable auto-snapshotting entirely - /vault snapshot
     *  still works either way. */
    long autoSnapshotIntervalTicks = 24000L;

    /** Take a snapshot of a player's inventory/armor/offhand the moment they die, before their items drop.
     *  Skipped when they are carrying nothing, so respawn loops can't fill the slots with empty copies. */
    boolean snapshotOnDeath = true;

    /** Death snapshots have their own limit, separate from maxSnapshotsPerPlayer, so frequent deaths
     *  (minigames, PvP) can never push manual and daily snapshots out. */
    int maxDeathSnapshotsPerPlayer = 10;

    /** A setting's name (the JSON field), default, allowed range and what it does. */
    record Def(String key, int def, int min, int max, String help) {}

    static final List<Def> DEFS = List.of(
            new Def("maxSnapshotsPerPlayer", 20, 1, 1000, "manual and daily snapshots kept per player; older ones are deleted"),
            new Def("autoSnapshotIntervalTicks", 24000, 0, 2_000_000, "game ticks between automatic snapshots of everyone online (24000 = one day, 0 = off)"),
            new Def("snapshotOnDeath", 1, 0, 1, "1 = snapshot a player's items the moment they die, 0 = don't"),
            new Def("maxDeathSnapshotsPerPlayer", 10, 1, 1000, "death snapshots kept per player, separate from the limit above"));

    static Def def(String key) {
        return DEFS.stream().filter(d -> d.key().equals(key)).findFirst().orElse(null);
    }

    synchronized int value(String key) {
        return switch (key) {
            case "maxSnapshotsPerPlayer" -> maxSnapshotsPerPlayer;
            case "autoSnapshotIntervalTicks" -> (int) Math.max(0, Math.min(Integer.MAX_VALUE, autoSnapshotIntervalTicks));
            case "snapshotOnDeath" -> snapshotOnDeath ? 1 : 0;
            case "maxDeathSnapshotsPerPlayer" -> maxDeathSnapshotsPerPlayer;
            default -> throw new IllegalArgumentException(key);
        };
    }

    /** Applies a value (already range-checked by the caller) and saves the file. */
    synchronized void set(String key, int value) {
        switch (key) {
            case "maxSnapshotsPerPlayer" -> maxSnapshotsPerPlayer = value;
            case "autoSnapshotIntervalTicks" -> autoSnapshotIntervalTicks = value;
            case "snapshotOnDeath" -> snapshotOnDeath = value == 1;
            case "maxDeathSnapshotsPerPlayer" -> maxDeathSnapshotsPerPlayer = value;
            default -> throw new IllegalArgumentException(key);
        }
        save();
    }

    static synchronized VaultConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static VaultConfig load() {
        if (Files.exists(FILE)) {
            try {
                VaultConfig loaded = GSON.fromJson(Files.readString(FILE), VaultConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                VaultInventoryMod.LOGGER.error("[VaultConfig] Failed to read {}, using defaults.", FILE, e);
            }
        }
        VaultConfig defaults = new VaultConfig();
        defaults.save();
        return defaults;
    }

    private void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[VaultConfig] Failed to write {}.", FILE, e);
        }
    }
}
