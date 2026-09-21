package name.vaultinventory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entrypoint. Standalone extraction of MintUtils' InventoryVaultManager feature -
 * op-triggered, disk-persisted inventory snapshots with a manual /vault snapshot|list|restore
 * command set plus an automatic once-per-in-game-day snapshot of every online player.
 */
public class VaultInventoryMod implements ModInitializer {
    public static final String MOD_ID = "vault-inventory";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Vault Inventory initialized.");

        InventoryVaultManager.registerEvents();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                InventoryVaultManager.registerCommands(dispatcher));
    }
}
