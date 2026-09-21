package name.vaultinventory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Inventory Vault: op-triggered snapshots of a player's inventory, armor/offhand, and
 * ender chest, persisted to disk so they survive a server restart. This is a manual
 * safety net for admin testing sessions - snapshot before loading a new mod, restore
 * if it wipes someone.
 *
 * Every snapshot is written to its own file under
 * config/vault_inventory/<player-uuid>/<epoch-millis>.dat. How many are kept per player, and
 * how often the auto-snapshot below fires, are configurable in config/vault-inventory.json
 * (see VaultConfig) - defaults are 20 snapshots and once per vanilla day (24000 ticks).
 *
 * Item data is serialized with vanilla's own ValueOutput/ValueInput + ItemStackWithSlot.CODEC
 * (the same machinery Player.addAdditionalSaveData/readAdditionalSaveData uses for real
 * playerdata), so it round-trips components/enchantments/etc. correctly instead of just
 * dumping raw item ids and counts.
 *
 * Restoring requires the target to be online, since it writes straight into their live
 * Inventory / EntityEquipment / PlayerEnderChestContainer objects rather than editing an
 * offline player's data file on disk. It also always saves the target's *current* state first
 * before applying the requested one, so picking the wrong index is never a one-way trip. That
 * safety copy lives in its own single slot per player (config/vault_inventory/<uuid>/safety/
 * pre-restore.dat): each restore replaces it, it never counts toward the snapshot limit, and so
 * repeated restores can never push the real snapshots out. Restore it with
 * "/vault restore <player> safety".
 *
 * Commands require the "vaultinventory.command" permission node (via fabric-permissions-api),
 * falling back to vanilla op level 2 if no permissions mod is installed - console always passes
 * regardless, since this is meant to work as a recovery tool even if in-game op access is
 * itself part of what broke.
 *
 * Also auto-snapshots every online player once per configured interval, tied to the overworld's
 * clock (Level.getOverworldClockTime() - the modern replacement for the old getDayTime()).
 * Onset of a new interval queues every currently-online player, then drains one snapshot per
 * server tick instead of writing them all in the same tick - at the default 24000-tick (20
 * real minute) interval, even a large online count drains in well under a second, spread
 * thinly enough that the disk I/O never bunches up into a tick hitch.
 */
final class InventoryVaultManager {
    private InventoryVaultManager() {}

    private static final String SAFETY_LABEL = "pre-restore safety snapshot";
    private static final String PERMISSION_NODE = "vaultinventory.command";
    private static final Path VAULT_DIR = FabricLoader.getInstance().getConfigDir().resolve("vault_inventory");

    private static Long lastAutoSnapshotPeriod = null;
    private static final Deque<UUID> pendingAutoSnapshots = new ArrayDeque<>();

    // MAINHAND is deliberately excluded - it's already covered by Inventory.save() (it's just
    // whichever hotbar slot is selected), so including it here would duplicate that item.
    private static final EquipmentSlot[] ARMOR_AND_OFFHAND_SLOTS = {
            EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(InventoryVaultManager::tick);
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vault")
                .requires(InventoryVaultManager::hasPermission)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§b§l[VAULT] §7Usage:"), false);
                    context.getSource().sendSuccess(() -> Component.literal("§b/vault snapshot [player] [label] §7- save a snapshot (all online players if none given)"), false);
                    context.getSource().sendSuccess(() -> Component.literal("§b/vault list <player>          §7- show that player's saved snapshots"), false);
                    context.getSource().sendSuccess(() -> Component.literal("§b/vault restore <player> [#]   §7- restore a snapshot (newest if no # given)"), false);
                    context.getSource().sendSuccess(() -> Component.literal("§b/vault restore <player> safety §7- undo the last restore (the copy saved just before it)"), false);
                    return 1;
                })
                .then(Commands.literal("snapshot")
                        .executes(context -> snapshotAllOnline(context.getSource()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> snapshotOne(context.getSource(),
                                        EntityArgument.getPlayer(context, "target"), ""))
                                .then(Commands.argument("label", StringArgumentType.greedyString())
                                        .executes(context -> snapshotOne(context.getSource(),
                                                EntityArgument.getPlayer(context, "target"),
                                                StringArgumentType.getString(context, "label"))))))
                .then(Commands.literal("list")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> listSnapshots(context.getSource(),
                                        EntityArgument.getPlayer(context, "target")))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> restoreSnapshot(context.getSource(),
                                        EntityArgument.getPlayer(context, "target"), 1))
                                .then(Commands.literal("safety")
                                        .executes(context -> restoreSafety(context.getSource(),
                                                EntityArgument.getPlayer(context, "target"))))
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(context -> restoreSnapshot(context.getSource(),
                                                EntityArgument.getPlayer(context, "target"),
                                                IntegerArgumentType.getInteger(context, "index"))))))
        );
    }

    // Console is allowed here regardless of what the permission check says - this is a
    // recovery tool, it should still work if in-game op/permission access is itself part of
    // what broke.
    private static boolean hasPermission(CommandSourceStack source) {
        if (source.getPlayer() == null) {
            return true;
        }
        return Permissions.check(source, PERMISSION_NODE, 2);
    }

    // ================= auto-snapshot =================

    private static void tick(MinecraftServer server) {
        long intervalTicks = VaultConfig.get().autoSnapshotIntervalTicks;
        if (intervalTicks <= 0) {
            return; // disabled in config
        }

        long period = server.overworld().getOverworldClockTime() / intervalTicks;
        if (lastAutoSnapshotPeriod == null) {
            lastAutoSnapshotPeriod = period; // don't fire on the very first tick after (re)start
        } else if (period != lastAutoSnapshotPeriod) {
            lastAutoSnapshotPeriod = period;
            pendingAutoSnapshots.clear();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                pendingAutoSnapshots.add(player.getUUID());
            }
            VaultInventoryMod.LOGGER.info("[InventoryVault] Auto-snapshot period {} started - snapshotting {} online player(s).",
                    period, pendingAutoSnapshots.size());
        }

        // One write per tick, not all of them in this one - see the class doc for why.
        UUID uuid = pendingAutoSnapshots.poll();
        if (uuid == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) {
            return; // logged off before their turn came up - just skip them for this period
        }
        try {
            writeSnapshot(server, player, "auto (period " + lastAutoSnapshotPeriod + ")");
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Auto-snapshot failed for {}.", player.getScoreboardName(), e);
        }
    }

    // ================= snapshot =================

    private static int snapshotOne(CommandSourceStack source, ServerPlayer player, String label) {
        try {
            writeSnapshot(source.getServer(), player, label);
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to snapshot {}.", player.getScoreboardName(), e);
            source.sendFailure(Component.literal("§cFailed to snapshot §e" + player.getScoreboardName() + "§c - see server log."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §aSnapshotted §e" + player.getScoreboardName() + "§a's inventory."), true);
        return 1;
    }

    private static int snapshotAllOnline(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                writeSnapshot(server, player, "bulk snapshot");
                count++;
            } catch (IOException e) {
                VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to snapshot {} during bulk snapshot.", player.getScoreboardName(), e);
            }
        }
        int savedCount = count;
        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §aSnapshotted §e" + savedCount + "§a online player(s)."), true);
        return count;
    }

    /** The player's inventory, armor/offhand and ender chest as a tag, ready to write to a file. */
    private static CompoundTag buildSnapshot(MinecraftServer server, ServerPlayer player, String label) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());

        output.putString("PlayerName", player.getScoreboardName());
        output.putLong("Timestamp", System.currentTimeMillis());
        output.putString("Label", label);

        player.getInventory().save(output.list("Inventory", ItemStackWithSlot.CODEC));
        player.getEnderChestInventory().storeAsSlots(output.list("EnderItems", ItemStackWithSlot.CODEC));

        ValueOutput.TypedOutputList<ItemStackWithSlot> equipment = output.list("Equipment", ItemStackWithSlot.CODEC);
        for (EquipmentSlot slot : ARMOR_AND_OFFHAND_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                equipment.add(new ItemStackWithSlot(slot.ordinal(), stack));
            }
        }
        return output.buildResult();
    }

    private static void writeSnapshot(MinecraftServer server, ServerPlayer player, String label) throws IOException {
        CompoundTag tag = buildSnapshot(server, player, label);

        Path dir = playerVaultDir(player.getUUID());
        Files.createDirectories(dir);
        Path file = dir.resolve(System.currentTimeMillis() + ".dat");
        NbtIo.writeCompressed(tag, file);

        pruneOldSnapshots(dir);
    }

    /** Replaces the player's single pre-restore safety copy. Never prunes: it isn't one of the numbered snapshots. */
    private static void writeSafetySnapshot(MinecraftServer server, ServerPlayer player) throws IOException {
        CompoundTag tag = buildSnapshot(server, player, SAFETY_LABEL);
        Path file = safetyFile(player.getUUID());
        Files.createDirectories(file.getParent());
        // Write beside it and move into place, so a crash mid-write can't leave a half-written safety copy.
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temp);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void pruneOldSnapshots(Path dir) throws IOException {
        List<Path> files = listSnapshotFiles(dir);
        int max = VaultConfig.get().maxSnapshotsPerPlayer;
        for (int i = max; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    // ================= list =================

    private static int listSnapshots(CommandSourceStack source, ServerPlayer target) {
        List<Path> files = listSnapshotFiles(playerVaultDir(target.getUUID()));
        if (files.isEmpty()) {
            source.sendFailure(Component.literal("§cNo vault snapshots for §e" + target.getScoreboardName() + "§c."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §7Snapshots for §e" + target.getScoreboardName()
                + "§7 (newest first):"), false);
        for (int i = 0; i < files.size(); i++) {
            int index = i + 1;
            CompoundTag meta = readMetaTag(files.get(i));
            String time = (meta != null)
                    ? TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(meta.getLongOr("Timestamp", 0L)))
                    : files.get(i).getFileName().toString();
            String label = (meta != null) ? meta.getStringOr("Label", "") : "";
            String suffix = label.isEmpty() ? "" : " §7- " + label;
            source.sendSuccess(() -> Component.literal("§7  [" + index + "] §f" + time + suffix), false);
        }
        Path safety = safetyFile(target.getUUID());
        if (Files.isRegularFile(safety)) {
            CompoundTag meta = readMetaTag(safety);
            String time = meta != null ? TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(meta.getLongOr("Timestamp", 0L))) : "?";
            source.sendSuccess(() -> Component.literal("§7  [safety] §f" + time + " §7- saved just before the last restore"
                    + " (/vault restore " + target.getScoreboardName() + " safety)"), false);
        }
        return files.size();
    }

    private static CompoundTag readMetaTag(Path file) {
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to read snapshot {}.", file, e);
            return null;
        }
    }

    // ================= restore =================

    private static int restoreSnapshot(CommandSourceStack source, ServerPlayer target, int index) {
        List<Path> files = listSnapshotFiles(playerVaultDir(target.getUUID()));
        if (files.isEmpty()) {
            source.sendFailure(Component.literal("§cNo vault snapshots for §e" + target.getScoreboardName() + "§c."));
            return 0;
        }
        if (index < 1 || index > files.size()) {
            source.sendFailure(Component.literal("§c" + target.getScoreboardName() + " only has §e" + files.size()
                    + "§c snapshot(s) - use /vault list to see them."));
            return 0;
        }

        Path file = files.get(index - 1);
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to read snapshot {} for {}.", file, target.getScoreboardName(), e);
            source.sendFailure(Component.literal("§cFailed to restore §e" + target.getScoreboardName() + "§c - see server log."));
            return 0;
        }
        return applyWithSafety(source, target, tag, "snapshot #" + index);
    }

    /** Undo: put back the copy saved just before the last restore. */
    private static int restoreSafety(CommandSourceStack source, ServerPlayer target) {
        Path file = safetyFile(target.getUUID());
        if (!Files.isRegularFile(file)) {
            source.sendFailure(Component.literal("§cNo safety copy for §e" + target.getScoreboardName() + "§c - nothing has been restored yet."));
            return 0;
        }
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to read the safety copy {} for {}.", file, target.getScoreboardName(), e);
            source.sendFailure(Component.literal("§cFailed to restore §e" + target.getScoreboardName() + "§c - see server log."));
            return 0;
        }
        return applyWithSafety(source, target, tag, "the safety copy");
    }

    /**
     * Saves the target's current state into the single safety slot, then applies {@code tag}. The tag was
     * already read into memory by the caller, so replacing the safety file here can't pull it away - which
     * also means restoring the safety copy just swaps it with the current state.
     */
    private static int applyWithSafety(CommandSourceStack source, ServerPlayer target, CompoundTag tag, String what) {
        try {
            writeSafetySnapshot(source.getServer(), target);
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to take a safety snapshot of {} before restoring - aborting restore.", target.getScoreboardName(), e);
            source.sendFailure(Component.literal("§cAborted: failed to save §e" + target.getScoreboardName() + "§c's current inventory first - see server log."));
            return 0;
        }

        applySnapshot(source.getServer(), target, tag);

        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §aRestored §e" + target.getScoreboardName()
                + "§a's inventory from " + what + ". §7(Undo with /vault restore " + target.getScoreboardName() + " safety)"), true);
        target.sendSystemMessage(Component.literal("§b§l[VAULT] §7Your inventory has been restored from a vault snapshot."));
        return 1;
    }

    private static void applySnapshot(MinecraftServer server, ServerPlayer player, CompoundTag tag) {
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag);

        player.getInventory().load(input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC));
        player.getEnderChestInventory().fromSlots(input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC));

        for (EquipmentSlot slot : ARMOR_AND_OFFHAND_SLOTS) {
            player.setItemSlot(slot, ItemStack.EMPTY);
        }
        EquipmentSlot[] allSlots = EquipmentSlot.values();
        for (ItemStackWithSlot entry : input.listOrEmpty("Equipment", ItemStackWithSlot.CODEC)) {
            if (entry.slot() >= 0 && entry.slot() < allSlots.length) {
                player.setItemSlot(allSlots[entry.slot()], entry.stack());
            }
        }
    }

    // ================= file helpers =================

    /** The one pre-restore safety copy per player. It sits in a subfolder so the numbered listing never sees it. */
    private static Path safetyFile(UUID uuid) {
        return playerVaultDir(uuid).resolve("safety").resolve("pre-restore.dat");
    }

    private static Path playerVaultDir(UUID uuid) {
        return VAULT_DIR.resolve(uuid.toString());
    }

    /** Newest-first. Filenames are epoch-millis, so lexicographic == chronological order
     *  for as long as they stay the same digit count (i.e. until the year 2286). */
    private static List<Path> listSnapshotFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".dat"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to list snapshots in {}.", dir, e);
            return List.of();
        }
    }
}
