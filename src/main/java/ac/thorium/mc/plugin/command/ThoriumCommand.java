package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.ThoriumMinestom;
import ac.thorium.mc.plugin.telemetry.CaptureWriter;
import ac.thorium.mc.plugin.world.WorldMirror;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ThoriumCommand extends Command {
    private final ThoriumMinestom thorium;

    public ThoriumCommand(ThoriumMinestom thorium) {
        super("thorium");
        this.thorium = thorium;
        ArgumentStringArray args = ArgumentType.StringArray("args");
        setDefaultExecutor((sender, ctx) -> run(sender, new String[0]));
        addSyntax((sender, ctx) -> run(sender, ctx.get(args)), args);
    }

    static void send(CommandSender to, String legacy) { to.sendMessage(LegacyComponentSerializer.legacySection().deserialize(legacy)); }

    public static List<String> statusLines(String version, String mcVersion, boolean configured, String connectionLine, String telemetryLine, boolean enforce) {
        List<String> l = new ArrayList<>();
        l.add("§8[§cThorium§8] §fminestom " + version);
        l.add("§7server: §fMinestom " + mcVersion);
        l.add(configured ? "§7connection: §f" + connectionLine : "§cnot configured §7(set server-token in thorium.properties)");
        if (configured) l.add("§7telemetry: §f" + telemetryLine);
        l.add("§7enforce: §f" + (enforce ? "on" : "off"));
        return l;
    }

    public static List<String> compatLines(String version, String mcVersion, String javaVersion, boolean onlineMode, String forwarding,
                                           boolean cracked, boolean worldEnabled, int heldSections) {
        List<String> l = new ArrayList<>();
        l.add("§8[§cThorium§8] §fcompat report §7minestom " + version);
        l.add("§7server: §fMinestom " + mcVersion + " §7on Java §f" + javaVersion);
        l.add("§7identity: §fonline-mode " + (onlineMode ? "on" : "off") + ", forwarding " + forwarding + " §7-> players " + (cracked ? "cracked" : "authenticated"));
        l.add(worldEnabled ? "§7world: §fstreaming, " + heldSections + " sections held" : "§7world: §foff §7(the engine has not asked for terrain)");
        return l;
    }

    private void run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = thorium.isAdmin(sender);
        switch (sub) {
            case "status" -> {
                if (!admin) { send(sender, "§cNo permission."); return; }
                boolean configured = thorium.connection() != null;
                for (String line : statusLines(ThoriumMinestom.VERSION, thorium.compat().mcVersion(), configured,
                        configured ? thorium.connection().statusLine() : "", configured ? thorium.telemetry().statusLine() : "", thorium.config().enforce)) {
                    send(sender, line);
                }
            }
            case "compat" -> {
                if (!admin) { send(sender, "§cNo permission."); return; }
                for (String line : thorium.compatLines()) send(sender, line);
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) { send(sender, "Players only."); return; }
                if (!thorium.staffAlerts().isStaff(p)) { send(sender, "§cNo permission."); return; }
                boolean on = thorium.staffAlerts().toggle(p.getUuid());
                send(sender, on ? "§aThorium alerts enabled." : "§7Thorium alerts disabled.");
            }
            case "capture" -> {
                if (!admin) { send(sender, "§cNo permission."); return; }
                if (thorium.telemetry() == null) { send(sender, "§cNot connected."); return; }
                String name = args.length > 1 ? args[1].replaceAll("[^A-Za-z0-9_-]", "") : "";
                String owner = sender instanceof Player p ? p.getUuid().toString() : "@console";
                if (name.isEmpty() || name.equalsIgnoreCase("stop")) {
                    if (!(sender instanceof Player)) {
                        List<CaptureWriter> all = thorium.telemetry().stopCaptures();
                        if (all.isEmpty()) { send(sender, "§7No capture running. Usage: /thorium capture <label>"); return; }
                        for (CaptureWriter c : all) send(sender, "§aSaved " + c.file().getName() + " (" + c.frames() + " frames)");
                        return;
                    }
                    CaptureWriter c = thorium.telemetry().stopCapture(owner);
                    send(sender, c == null ? "§7No capture running. Usage: /thorium capture <label>" : "§aSaved " + c.file().getName() + " (" + c.frames() + " frames)");
                    return;
                }
                File f = thorium.dataDir().resolve("captures/" + name + "-" + System.currentTimeMillis() / 1000 + ".bin").toFile();
                try { thorium.telemetry().startCapture(owner, f); send(sender, "§aCapturing to " + f.getName() + ". Run /thorium capture stop when done."); }
                catch (IOException e) { send(sender, "§cCould not open " + f + ": " + e.getMessage()); }
            }
            case "reconnect" -> {
                if (!admin) { send(sender, "§cNo permission."); return; }
                thorium.reconnect();
                send(sender, "§7Thorium: reconnecting…");
            }
            default -> send(sender, "§7Usage: /thorium <status|compat|alerts|reconnect|capture <label>|capture stop>");
        }
    }
}
