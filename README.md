# Vault Inventory

Op-triggered, disk-persisted inventory backups for Fabric servers (Minecraft 26.3). Snapshot a player's inventory, armor, offhand and ender chest before a risky change, and restore it if something goes wrong.

## Commands

| Command | Description |
|---|---|
| `/vault snapshot [player] [label]` | Save a snapshot (all online players if none given) |
| `/vault list <player>` | Show a player's saved snapshots, newest first |
| `/vault restore <player> [#]` | Restore a snapshot (newest if no number given) |
| `/vault restore <player> safety` | Undo the last restore |

Every restore first saves the target's current inventory to a separate safety slot, so a bad restore is never a one-way trip. The safety slot is not counted against, or pruned by, the snapshot limit.

The server also auto-snapshots every online player once per in-game day.

## Permissions

Requires the `vaultinventory.command` permission node. Falls back to vanilla op level 2 when no permissions mod is installed; console always passes. Grant the node through LuckPerms (or any fabric-permissions-api provider) for non-op access.

## Configuration

`config/vault-inventory.json`, read on server start:

- `maxSnapshotsPerPlayer` (default `20`) - oldest snapshots beyond this are pruned
- `autoSnapshotIntervalTicks` (default `24000`, one vanilla day) - set to `0` to disable auto-snapshots

Snapshots are stored under `config/vault_inventory/<player-uuid>/`.

## Requirements

Fabric Loader, Fabric API, Java 25. Server-side only. Works in singleplayer with cheats enabled.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

MIT
