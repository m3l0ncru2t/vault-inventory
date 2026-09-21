# Vault Inventory

Op-triggered, disk-persisted inventory backups for Fabric servers (Minecraft 26.3). Snapshot a player's inventory, armor, offhand and ender chest before a risky change, and restore it if something goes wrong.

## Commands

| Command | Description |
|---|---|
| `/vault snapshot [player] [label]` | Save a snapshot (all online players if none given) |
| `/vault list <player>` | Saved snapshots, newest first, with clickable **Preview** / **Restore** buttons. Works for offline players |
| `/vault preview <player> [#\|at <time>\|safety]` | Show what a snapshot contains: stack and item counts and the most plentiful items. Works for offline players |
| `/vault restore <player> [#\|at <time>\|safety] [inventory\|enderchest\|equipment]` | Restore a snapshot, all of it or just one part. The player must be online |

`#` is the number shown in `/vault list`; it shifts as new snapshots arrive. `at <time>` is the snapshot's exact timestamp, which never changes, and is what the clickable buttons use.

**Death snapshots.** Every time a player dies, their inventory, armor and offhand are saved just before the items drop, and appear in the list marked `[death]`. Deaths where the player carried nothing are skipped, so repeated respawns can't fill the slots with empty copies. Death snapshots have their own limit and never push out manual or daily ones.

**Safety copy.** Every restore first saves the target's current state to a separate safety slot, so a bad restore is never a one-way trip: `/vault restore <player> safety` undoes it. The slot is not counted against, or pruned by, the snapshot limit.

The server also auto-snapshots every online player once per in-game day.

## Permissions

Requires the `vaultinventory.command` permission node. Falls back to vanilla op level 2 when no permissions mod is installed; console always passes. Grant the node through LuckPerms (or any fabric-permissions-api provider) for non-op access.

## Configuration

`config/vault-inventory.json`, read on server start:

- `maxSnapshotsPerPlayer` (default `20`) - oldest snapshots beyond this are pruned
- `autoSnapshotIntervalTicks` (default `24000`, one vanilla day) - set to `0` to disable auto-snapshots
- `snapshotOnDeath` (default `true`) - take a snapshot when a player dies
- `maxDeathSnapshotsPerPlayer` (default `10`) - death snapshots kept per player, separate from the limit above

Snapshots are stored under `config/vault_inventory/<player-uuid>/` (`deaths/` and `safety/` subfolders hold death snapshots and the safety copy).

## Requirements

Fabric Loader, Fabric API, Java 25. Server-side only. Works in singleplayer with cheats enabled.

## API for other mods

`name.vaultinventory.VaultInventoryApi` lets other mods take, list and restore snapshots: `snapshot(server, player, label)`, `list(uuid)` (works offline) and `restore(server, player, timestamp)`. Check `FabricLoader.getInstance().isModLoaded("vault-inventory")` before touching the class so your mod still loads without it.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

MIT
