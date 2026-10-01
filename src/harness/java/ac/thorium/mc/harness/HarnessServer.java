package ac.thorium.mc.harness;

import ac.thorium.mc.plugin.ThoriumMinestom;
import net.kyori.adventure.text.Component;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server Thorium-Mineflayer boots for a Minestom row: a vanilla-shaped flat world, every player op,
 * and the few vanilla commands the bots send (Minestom ships none). Only the argument shapes the bots
 * use are parsed.
 */
public final class HarnessServer {
    private static final Pattern SELECTOR_ARG = Pattern.compile("(\\w+)=(!?)([^,\\]]+)");
    private static InstanceContainer world;

    public static void main(String[] args) throws Exception {
        long booted = System.nanoTime();
        String secret = System.getenv().getOrDefault("VELOCITY_SECRET", "");
        MinecraftServer server = MinecraftServer.init(secret.isEmpty() ? new Auth.Offline() : new Auth.Velocity(secret));

        // Vanilla's superflat layers, so ground is at y=-61 and players stand at -60 as on every 1.18+ row.
        world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setGenerator(u -> {
            u.modifier().fillHeight(-64, -63, Block.BEDROCK);
            u.modifier().fillHeight(-63, -61, Block.DIRT);
            u.modifier().fillHeight(-61, -60, Block.GRASS_BLOCK);
        });
        MinecraftServer.getGlobalEventHandler()
                .addListener(AsyncPlayerConfigurationEvent.class, e -> {
                    e.setSpawningInstance(world);
                    e.getPlayer().setRespawnPoint(new Pos(0.5, -60, 0.5));
                })
                .addListener(PlayerSpawnEvent.class, e -> e.getPlayer().setPermissionLevel(4));

        command("fill", a -> {
            int x1 = i(a[0]), y1 = i(a[1]), z1 = i(a[2]), x2 = i(a[3]), y2 = i(a[4]), z2 = i(a[5]);
            Block b = Block.fromKey(key(a[6]));
            for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++)
                for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) world.loadChunk(cx, cz).join();
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) world.setBlock(x, y, z, b);
        });
        // Relative, zero view change: vanilla's /tp x y z leaves the client's look alone.
        command("tp", a -> player(a[0]).teleport(new Pos(d(a[1]), d(a[2]), d(a[3])), null, RelativeFlags.VIEW));
        command("summon", a -> {
            EntityType type = EntityType.fromKey(key(a[0]));
            Entity e = type == EntityType.VILLAGER ? new LivingEntity(type) : new Entity(type);
            e.setInstance(world, new Pos(d(a[1]), d(a[2]), d(a[3])));
        });
        command("kill", a -> {
            Map<String, String> sel = selector(a[0]);
            world.getEntities().stream().filter(e -> !(e instanceof Player) && matches(e, sel)).toList().forEach(Entity::remove);
        });
        command("effect", a -> player(a[1]).addEffect(new Potion(PotionEffect.fromKey(key(a[2])), i(a[4]), i(a[3]) * 20)));
        command("item", a -> player(a[2]).setEquipment(EquipmentSlot.CHESTPLATE, ItemStack.of(Material.fromKey(key(a[5])))));
        command("ride", a -> {
            Player p = player(a[0]);
            Map<String, String> sel = selector(a[2]);
            world.getEntities().stream().filter(e -> !(e instanceof Player) && matches(e, sel))
                    .min(Comparator.comparingDouble(e -> e.getPosition().distanceSquared(p.getPosition())))
                    .ifPresent(v -> v.addPassenger(p));
        });

        ThoriumMinestom.start(Path.of(System.getenv().getOrDefault("THORIUM_DATA", "thorium")));
        server.start("0.0.0.0", 25565);
        // The harness waits for vanilla's boot line, which Minestom does not print.
        System.out.printf("Done (%.3fs)! For help, type \"help\"%n", (System.nanoTime() - booted) / 1e9);
    }

    private static void command(String name, Consumer<String[]> body) {
        Command c = new Command(name);
        ArgumentStringArray args = ArgumentType.StringArray("args");
        c.addSyntax((sender, ctx) -> {
            try { body.accept(ctx.get(args)); }
            catch (RuntimeException e) { sender.sendMessage(Component.text("/" + name + " failed: " + e)); }
        }, args);
        MinecraftServer.getCommandManager().register(c);
    }

    // "@e[type=!player,x=1,y=2,z=3,distance=..6]" -> {type=!player, x=1, ...}; the "!" stays on the value.
    private static Map<String, String> selector(String s) {
        Map<String, String> out = new java.util.HashMap<>();
        Matcher m = SELECTOR_ARG.matcher(s);
        while (m.find()) out.put(m.group(1), m.group(2) + m.group(3));
        return out;
    }

    private static boolean matches(Entity e, Map<String, String> sel) {
        String type = sel.get("type");
        if (type != null) {
            boolean is = e.getEntityType().key().asString().equals(key(type.replace("!", "")));
            if (is == type.startsWith("!")) return false;
        }
        String dist = sel.get("distance");
        if (dist == null || !sel.containsKey("x")) return true;
        Pos at = new Pos(d(sel.get("x")), d(sel.get("y")), d(sel.get("z")));
        double r = d(dist.replace("..", ""));
        return e.getPosition().distanceSquared(at) <= r * r;
    }

    private static Player player(String name) {
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (p == null) throw new IllegalArgumentException("no player " + name);
        return p;
    }

    private static String key(String name) { return name.contains(":") ? name : "minecraft:" + name; }
    private static double d(String s) { return Double.parseDouble(s); }
    private static int i(String s) { return (int) Math.floor(d(s)); }
}
