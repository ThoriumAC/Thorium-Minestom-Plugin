# Thorium for Minestom

Minestom port of the [Thorium](https://thorium.ac) server plugin. It streams the packets your
server already handles to the Thorium engine and applies the verdicts that come back.

Targets Minestom `2026.09.12-26.2` (Minecraft 26.2), Java 25.

## Install

Served by [JitPack](https://jitpack.io/#ThoriumAC/Thorium-Minestom-Plugin); no login needed. The
version is the git tag, which carries the Minecraft version it was built for (`1.1.0-26.2`,
`1.1.0-1.21.11`); use the one matching your Minestom.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("net.minestom:minestom:2026.09.12-26.2")
    implementation("com.github.ThoriumAC:Thorium-Minestom-Plugin:1.1.0-26.2")
}
```


## Use

```java
MinecraftServer server = MinecraftServer.init(new Auth.Online());
// ... instances, your own listeners ...
ThoriumMinestom thorium = ThoriumMinestom.start(Path.of("thorium"))   // data folder, for captures
        .permissions((player, node) -> myPerms.has(player, node));       // optional, see below
server.start("0.0.0.0", 25565);
```

The first start writes `config/thorium.properties` (an old `<data folder>/thorium.properties` is
moved there). Run `/thorium setup <code>` with a code from the dashboard, or set `server-token` and
`server-name` by hand and run `/thorium reconnect`.
