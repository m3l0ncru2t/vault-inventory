package name.vaultinventory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reloaded fresh on every server (re)start - there's no in-game reload command, since these
 * values only matter at the point a snapshot is taken or pruned. Edit config/vault-inventory.json
 * and restart to change them.
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
