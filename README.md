# Thorium for Minestom

Minestom port of the [Thorium](https://thorium.ac) server plugin. It streams the packets your
server already handles to the Thorium engine and applies the verdicts that come back. The
transport, buffers, world mirror and wire format are shared with the Bukkit plugin; only the
capture and enforcement layer is Minestom-specific.

Targets Minestom `2026.09.12-26.2` (Minecraft 26.2), Java 25.

## Install

Served by [JitPack](https://jitpack.io/#ThoriumAC/Thorium-Minestom-Plugin); no login needed. The
version is the git tag, which carries the Minecraft version it was built for (`1.0.1-26.2`,
`1.0.1-1.21.11`); use the one matching your Minestom.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("net.minestom:minestom:2026.09.12-26.2")
    implementation("com.github.ThoriumAC:Thorium-Minestom-Plugin:1.0.1-26.2")
}
```

The jar is shaded (its websocket and protobuf are relocated), so it brings no dependencies of its own.

To release, bump `version` in `build.gradle.kts`, then tag the commit on the branch being released
(`git tag 1.0.1-26.2 && git push origin 1.0.1-26.2`). JitPack builds it the first time someone asks for it.

## Use

```java
MinecraftServer server = MinecraftServer.init(new Auth.Online());
// ... instances, your own listeners ...
ThoriumMinestom thorium = ThoriumMinestom.start(Path.of("thorium"))
        .staff(p -> p.getPermissionLevel() >= 2)   // who gets alerts (default shown)
        .admin(p -> p.getPermissionLevel() >= 4);  // who may run /thorium status|capture|reconnect
server.start("0.0.0.0", 25565);
```

The first start writes `thorium/thorium.properties`. Set `server-token` (and `server-name` for a
network token), then run `/thorium reconnect`.

## Differences from the Bukkit plugin

- **Entity movement** is read from the entities each tick rather than from packets: Minestom batches
  movement to viewers into raw buffers that never reach `PlayerPacketOutEvent`. Spawns, metadata,
  velocity and everything else are still captured from packets. No JVM flags are needed.
- **Worlds are keyed by instance UUID**, not by name, because many instances share one dimension type.
- **Bans:** Minestom has no ban list. Set `ban-command` / `unban-command` to your own commands;
  without them a ban only kicks.
- **Permissions:** Minestom has none, so staff and admin are predicates (default: permission level).
- **Advanced Analytics** records only what Minestom fires: block break/place, death/kill,
  consume, item use, drop and pickup. Crafting, enchanting, fishing and bow shots do not exist
  unless your server implements them.
- **Block resync** resends the whole chunk column; Minestom has no per-section resend.
- **Engine side:** the Hello reports `SERVER_SOFTWARE_MINESTOM` (value 6, added to `proto/`). The
  engine's copy of the proto needs the same line. Checks that expect vanilla server behavior, such as
  NoFall expecting fall damage, will false-flag on servers that do not implement it.
