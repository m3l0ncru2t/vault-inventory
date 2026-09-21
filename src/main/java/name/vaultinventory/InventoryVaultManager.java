package name.vaultinventory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.damagesource.DamageSource;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Inventory Vault: snapshots of a player's inventory, armor/offhand and ender chest, persisted to
 * disk so they survive a server restart. A safety net for admin testing sessions and for lost gear -
 * snapshot before loading a new mod, restore if it wipes someone.
 *
 * Storage, per player, under config/vault_inventory/&lt;uuid&gt;/:
 *   &lt;epoch-millis&gt;.dat          manual and daily auto snapshots, capped by maxSnapshotsPerPlayer
 *   deaths/&lt;epoch-millis&gt;.dat  taken the moment the player dies (before drops), own cap
 *   safety/pre-restore.dat     the single copy of the target's state saved just before a restore
 * /vault list shows the first two merged, newest first. Restores address a snapshot by that
 * displayed number or by its exact timestamp ("at"); the timestamp form never shifts as new
 * snapshots arrive, which is what the clickable buttons in /vault list use.
 *
 * Item data is serialized with vanilla's own ValueOutput/ValueInput + ItemStackWithSlot.CODEC
 * (the same machinery Player.addAdditionalSaveData/readAdditionalSaveData uses for real
 * playerdata), so components and enchantments round-trip instead of just item ids and counts.
 *
 * Restoring requires the target to be online, since it writes straight into their live
 * Inventory / EntityEquipment / PlayerEnderChestContainer objects rather than editing an offline
 * player's data file. Listing and previewing work for offline players. A restore can apply the
 * whole snapshot or just one part of it (inventory, ender chest, equipment). Every restore first
 * saves the target's current state to the safety slot, so it can be undone with
 * "/vault restore &lt;player&gt; safety".
 *
 * Commands require the "vaultinventory.command" permission node (via fabric-permissions-api),
 * falling back to vanilla op level 2 if no permissions mod is installed - console always passes,
 * since this is meant to work as a recovery tool even if in-game op access is what broke.
 *
 * Auto-snapshots every online player once per configured interval, tied to the overworld's clock.
 * Onset of a new interval queues every online player, then drains one snapshot per server tick so
 * the disk I/O never bunches up into a tick hitch.
 */
final class InventoryVaultManager {
    private InventoryVaultManager() {}

    private static final String SAFETY_LABEL = "pre-restore safety snapshot";
    private static final String PERMISSION_NODE = "vaultinventory.command";
    private static final Path VAULT_DIR = FabricLoader.getInstance().getConfigDir().resolve("vault_inventory");
    private static final int PREVIEW_MAX_NAMES = 10;

    private static Long lastAutoSnapshotPeriod = null;
    private static final Deque<UUID> pendingAutoSnapshots = new ArrayDeque<>();

    // MAINHAND is deliberately excluded - it's already covered by Inventory.save() (it's just
    // whichever hotbar slot is selected), so including it here would duplicate that item.
    private static final EquipmentSlot[] ARMOR_AND_OFFHAND_SLOTS = {
            EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Which part of a snapshot a restore applies. */
    enum Part {
        ALL("all", "full inventory"),
        INVENTORY("inventory", "inventory"),
        ENDERCHEST("enderchest", "ender chest"),
        EQUIPMENT("equipment", "armor and offhand");

        final String command;
        final String description;

        Part(String command, String description) {
            this.command = command;
            this.description = description;
        }
    }

    /** One stored snapshot file. {@code death} marks the ones taken automatically on death. */
    record Entry(Path file, long timestamp, boolean death) {}

    /** Which snapshot a command refers to. */
    private record Ref(int index, Long timestamp, boolean safety) {
        static Ref newest() { return new Ref(1, null, false); }
        static Ref index(int index) { return new Ref(index, null, false); }
        static Ref at(long timestamp) { return new Ref(0, timestamp, false); }
        static Ref safetyCopy() { return new Ref(0, null, true); }
    }

    private record Resolved(Path file, String description) {}

    private interface PartRunner {
        int run(CommandContext<CommandSourceStack> context, Part part) throws CommandSyntaxException;
    }

    static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(InventoryVaultManager::tick);
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player && VaultConfig.get().snapshotOnDeath) {
                snapshotOnDeath(player, source);
            }
            return true;
        });
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var restoreTarget = Commands.argument("target", EntityArgument.player());
        restoreTarget.executes(context -> restore(context, Ref.newest(), Part.ALL));
        addParts(restoreTarget, (context, part) -> restore(context, Ref.newest(), part));

        var restoreSafety = Commands.literal("safety").executes(context -> restore(context, Ref.safetyCopy(), Part.ALL));
        addParts(restoreSafety, (context, part) -> restore(context, Ref.safetyCopy(), part));

        var restoreAtTime = Commands.argument("timestamp", LongArgumentType.longArg(0))
                .executes(context -> restore(context, Ref.at(LongArgumentType.getLong(context, "timestamp")), Part.ALL));
        addParts(restoreAtTime, (context, part) ->
                restore(context, Ref.at(LongArgumentType.getLong(context, "timestamp")), part));

        var restoreIndex = Commands.argument("index", IntegerArgumentType.integer(1))
                .executes(context -> restore(context, Ref.index(IntegerArgumentType.getInteger(context, "index")), Part.ALL));
        addParts(restoreIndex, (context, part) ->
                restore(context, Ref.index(IntegerArgumentType.getInteger(context, "index")), part));

        restoreTarget.then(restoreSafety)
                .then(Commands.literal("at").then(restoreAtTime))
                .then(restoreIndex);

        var previewTarget = Commands.argument("target", GameProfileArgument.gameProfile())
                .executes(context -> preview(context.getSource(), singleProfile(context), Ref.newest()))
                .then(Commands.literal("safety")
                        .executes(context -> preview(context.getSource(), singleProfile(context), Ref.safetyCopy())))
                .then(Commands.literal("at")
                        .then(Commands.argument("timestamp", LongArgumentType.longArg(0))
                                .executes(context -> preview(context.getSource(), singleProfile(context),
                                        Ref.at(LongArgumentType.getLong(context, "timestamp"))))))
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(context -> preview(context.getSource(), singleProfile(context),
                                Ref.index(IntegerArgumentType.getInteger(context, "index")))));

        dispatcher.register(Commands.literal("vault")
                .requires(InventoryVaultManager::hasPermission)
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> Component.literal("§b§l[VAULT] §7Usage:"), false);
                    source.sendSuccess(() -> Component.literal("§b/vault snapshot [player] [label] §7- save a snapshot (all online players if none given)"), false);
                    source.sendSuccess(() -> Component.literal("§b/vault list <player> §7- saved snapshots, with clickable Preview/Restore (works offline)"), false);
                    source.sendSuccess(() -> Component.literal("§b/vault preview <player> [#|at <time>|safety] §7- show what a snapshot contains (works offline)"), false);
                    source.sendSuccess(() -> Component.literal("§b/vault restore <player> [#|at <time>|safety] [inventory|enderchest|equipment] §7- restore all or part of a snapshot (player must be online)"), false);
                    source.sendSuccess(() -> Component.literal("§7Every restore saves the current state first - undo with §b/vault restore <player> safety§7."), false);
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
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .executes(context -> listSnapshots(context.getSource(), singleProfile(context)))))
                .then(Commands.literal("preview").then(previewTarget))
                .then(Commands.literal("restore").then(restoreTarget))
        );
    }

    private static void addParts(ArgumentBuilder<CommandSourceStack, ?> node, PartRunner runner) {
        for (Part part : Part.values()) {
            if (part != Part.ALL) {
                node.then(Commands.literal(part.command).executes(context -> runner.run(context, part)));
            }
        }
    }

    private static NameAndId singleProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "target");
        if (profiles.size() != 1) {
            throw new SimpleCommandExceptionType(Component.literal("Specify exactly one player.")).create();
        }
        return profiles.iterator().next();
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

    // ================= death snapshot =================

    private static void snapshotOnDeath(ServerPlayer player, DamageSource source) {
        // Nothing to lose means nothing worth keeping - and empty copies would otherwise rotate
        // real ones out of the death slots when a player dies repeatedly (arena respawn loops).
        if (carriesNothing(player)) {
            return;
        }
        try {
            MinecraftServer server = player.level().getServer();
            CompoundTag tag = buildSnapshot(server, player, "death (" + source.getMsgId() + ")");
            Path dir = deathDir(player.getUUID());
            Files.createDirectories(dir);
            NbtIo.writeCompressed(tag, dir.resolve(System.currentTimeMillis() + ".dat"));
            pruneOldSnapshots(dir, VaultConfig.get().maxDeathSnapshotsPerPlayer);
        } catch (Exception e) {
            // Must never interfere with the death itself.
            VaultInventoryMod.LOGGER.error("[InventoryVault] Death snapshot failed for {}.", player.getScoreboardName(), e);
        }
    }

    private static boolean carriesNothing(ServerPlayer player) {
        if (!player.getInventory().isEmpty()) {
            return false;
        }
        for (EquipmentSlot slot : ARMOR_AND_OFFHAND_SLOTS) {
            if (!player.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
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

    static void writeSnapshot(MinecraftServer server, ServerPlayer player, String label) throws IOException {
        CompoundTag tag = buildSnapshot(server, player, label);

        Path dir = playerVaultDir(player.getUUID());
        Files.createDirectories(dir);
        Path file = dir.resolve(System.currentTimeMillis() + ".dat");
        NbtIo.writeCompressed(tag, file);

        pruneOldSnapshots(dir, VaultConfig.get().maxSnapshotsPerPlayer);
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

    private static void pruneOldSnapshots(Path dir, int max) throws IOException {
        List<Path> files = listSnapshotFiles(dir);
        for (int i = max; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    // ================= list =================

    private static int listSnapshots(CommandSourceStack source, NameAndId target) {
        List<Entry> entries = listEntries(target.id());
        if (entries.isEmpty()) {
            source.sendFailure(Component.literal("§cNo vault snapshots for §e" + target.name() + "§c."));
            return 0;
        }

        boolean online = source.getServer().getPlayerList().getPlayer(target.id()) != null;
        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §7Snapshots for §e" + target.name()
                + "§7 (newest first)" + (online ? "" : " §8- offline, preview only") + ":"), false);

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            CompoundTag meta = readMetaTag(entry.file());
            String label = (meta != null) ? meta.getStringOr("Label", "") : "";

            MutableComponent line = Component.literal("§7  [" + (i + 1) + "] §f" + formatTime(entry.timestamp())
                    + (entry.death() ? " §c[death]" : "")
                    + (label.isEmpty() ? "" : " §7- " + label));
            line.append(" ").append(button("§9[Preview]", "/vault preview " + target.name() + " at " + entry.timestamp(),
                    "§9Click to see what's in this snapshot"));
            if (online) {
                line.append(" ").append(button("§a[Restore]", "/vault restore " + target.name() + " at " + entry.timestamp(),
                        "§aClick to restore this snapshot\n§7(undo with /vault restore " + target.name() + " safety)"));
            }
            source.sendSuccess(() -> line, false);
        }

        Path safety = safetyFile(target.id());
        if (Files.isRegularFile(safety)) {
            CompoundTag meta = readMetaTag(safety);
            String time = meta != null ? formatTime(meta.getLongOr("Timestamp", 0L)) : "?";
            MutableComponent line = Component.literal("§7  [safety] §f" + time + " §7- saved just before the last restore");
            line.append(" ").append(button("§9[Preview]", "/vault preview " + target.name() + " safety",
                    "§9Click to see what's in the safety copy"));
            if (online) {
                line.append(" ").append(button("§a[Restore]", "/vault restore " + target.name() + " safety",
                        "§aClick to undo the last restore"));
            }
            source.sendSuccess(() -> line, false);
        }
        return entries.size();
    }

    private static MutableComponent button(String text, String command, String hover) {
        return Component.literal(text).withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static String formatTime(long epochMillis) {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static CompoundTag readMetaTag(Path file) {
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to read snapshot {}.", file, e);
            return null;
        }
    }

    // ================= preview =================

    private static int preview(CommandSourceStack source, NameAndId target, Ref ref) {
        Resolved resolved = resolve(source, target, ref);
        if (resolved == null) {
            return 0;
        }
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(resolved.file(), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to read snapshot {} for {}.", resolved.file(), target.name(), e);
            source.sendFailure(Component.literal("§cFailed to read that snapshot - see server log."));
            return 0;
        }

        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, source.getServer().registryAccess(), tag);
        List<ItemStack> inventory = stacks(input, "Inventory");
        List<ItemStack> ender = stacks(input, "EnderItems");
        List<ItemStack> equipment = stacks(input, "Equipment");

        String label = tag.getStringOr("Label", "");
        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §7Preview of §e" + target.name() + "§7's " + resolved.description()
                + " §8(" + formatTime(tag.getLongOr("Timestamp", 0L)) + (label.isEmpty() ? "" : ", " + label) + ")"), false);
        source.sendSuccess(() -> Component.literal(summarize("Inventory", inventory)), false);
        source.sendSuccess(() -> Component.literal(summarize("Ender chest", ender)), false);
        source.sendSuccess(() -> Component.literal(summarize("Armor/offhand", equipment)), false);
        return 1;
    }

    private static List<ItemStack> stacks(ValueInput input, String key) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStackWithSlot entry : input.listOrEmpty(key, ItemStackWithSlot.CODEC)) {
            if (!entry.stack().isEmpty()) {
                result.add(entry.stack());
            }
        }
        return result;
    }

    /** "Heading (N stacks, M items): Diamond x64, Elytra x1, ..." with the most plentiful items first. */
    private static String summarize(String heading, List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return "§7  " + heading + ": §8empty";
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        int items = 0;
        for (ItemStack stack : stacks) {
            totals.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            items += stack.getCount();
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        StringBuilder text = new StringBuilder("§7  " + heading + " §8(" + stacks.size() + " stacks, " + items + " items): ");
        for (int i = 0; i < sorted.size() && i < PREVIEW_MAX_NAMES; i++) {
            if (i > 0) {
                text.append("§7, ");
            }
            text.append("§f").append(sorted.get(i).getKey()).append(" §7x").append(sorted.get(i).getValue());
        }
        if (sorted.size() > PREVIEW_MAX_NAMES) {
            text.append("§7, +").append(sorted.size() - PREVIEW_MAX_NAMES).append(" more");
        }
        return text.toString();
    }

    // ================= restore =================

    private static int restore(CommandContext<CommandSourceStack> context, Ref ref, Part part) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");

        Resolved resolved = resolve(source, new NameAndId(target.getUUID(), target.getScoreboardName()), ref);
        if (resolved == null) {
            return 0;
        }
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(resolved.file(), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to read snapshot {} for {}.", resolved.file(), target.getScoreboardName(), e);
            source.sendFailure(Component.literal("§cFailed to restore §e" + target.getScoreboardName() + "§c - see server log."));
            return 0;
        }

        try {
            applyWithSafety(source.getServer(), target, tag, part);
        } catch (IOException e) {
            VaultInventoryMod.LOGGER.error("[InventoryVault] Failed to take a safety snapshot of {} before restoring - aborting restore.", target.getScoreboardName(), e);
            source.sendFailure(Component.literal("§cAborted: failed to save §e" + target.getScoreboardName() + "§c's current inventory first - see server log."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§b§l[VAULT] §aRestored §e" + target.getScoreboardName()
                + "§a's " + part.description + " from " + resolved.description()
                + ". §7(Undo with /vault restore " + target.getScoreboardName() + " safety)"), true);
        target.sendSystemMessage(Component.literal("§b§l[VAULT] §7Your " + part.description
                + " has been restored from a vault snapshot."));
        return 1;
    }

    /**
     * Saves the target's current state into the single safety slot, then applies {@code tag}. The tag was
     * already read into memory by the caller, so replacing the safety file here can't pull it away - which
     * also means restoring the safety copy just swaps it with the current state.
     */
    static void applyWithSafety(MinecraftServer server, ServerPlayer target, CompoundTag tag, Part part) throws IOException {
        writeSafetySnapshot(server, target);
        applySnapshot(server, target, tag, part);
    }

    private static void applySnapshot(MinecraftServer server, ServerPlayer player, CompoundTag tag, Part part) {
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag);

        if (part == Part.ALL || part == Part.INVENTORY) {
            player.getInventory().load(input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC));
        }
        if (part == Part.ALL || part == Part.ENDERCHEST) {
            player.getEnderChestInventory().fromSlots(input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC));
        }
        if (part == Part.ALL || part == Part.EQUIPMENT) {
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
    }

    /** Turns a command's snapshot reference into a file, or tells the source why it can't. */
    private static Resolved resolve(CommandSourceStack source, NameAndId target, Ref ref) {
        if (ref.safety()) {
            Path file = safetyFile(target.id());
            if (!Files.isRegularFile(file)) {
                source.sendFailure(Component.literal("§cNo safety copy for §e" + target.name() + "§c - nothing has been restored yet."));
                return null;
            }
            return new Resolved(file, "safety copy");
        }

        List<Entry> entries = listEntries(target.id());
        if (entries.isEmpty()) {
            source.sendFailure(Component.literal("§cNo vault snapshots for §e" + target.name() + "§c."));
            return null;
        }

        if (ref.timestamp() != null) {
            for (Entry entry : entries) {
                if (entry.timestamp() == ref.timestamp()) {
                    return new Resolved(entry.file(), "snapshot from " + formatTime(entry.timestamp()));
                }
            }
            source.sendFailure(Component.literal("§cNo snapshot at that time for §e" + target.name() + "§c - it may have been pruned. Use /vault list."));
            return null;
        }

        if (ref.index() < 1 || ref.index() > entries.size()) {
            source.sendFailure(Component.literal("§c" + target.name() + " only has §e" + entries.size()
                    + "§c snapshot(s) - use /vault list to see them."));
            return null;
        }
        return new Resolved(entries.get(ref.index() - 1).file(), "snapshot #" + ref.index());
    }

    // ================= file helpers =================

    /** The one pre-restore safety copy per player. It sits in a subfolder so the numbered listing never sees it. */
    private static Path safetyFile(UUID uuid) {
        return playerVaultDir(uuid).resolve("safety").resolve("pre-restore.dat");
    }

    private static Path deathDir(UUID uuid) {
        return playerVaultDir(uuid).resolve("deaths");
    }

    private static Path playerVaultDir(UUID uuid) {
        return VAULT_DIR.resolve(uuid.toString());
    }

    /** Every listed snapshot (manual, daily and death), newest first. */
    static List<Entry> listEntries(UUID uuid) {
        List<Entry> entries = new ArrayList<>();
        for (Path file : listSnapshotFiles(playerVaultDir(uuid))) {
            entries.add(new Entry(file, fileTimestamp(file), false));
        }
        for (Path file : listSnapshotFiles(deathDir(uuid))) {
            entries.add(new Entry(file, fileTimestamp(file), true));
        }
        entries.sort(Comparator.comparingLong(Entry::timestamp).reversed());
        return entries;
    }

    private static long fileTimestamp(Path file) {
        String name = file.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, name.length() - ".dat".length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
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

    /** Label stored inside a snapshot file, or "" if it can't be read. */
    static String readLabel(Path file) {
        CompoundTag meta = readMetaTag(file);
        return meta != null ? meta.getStringOr("Label", "") : "";
    }

    static CompoundTag readTag(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }
}
