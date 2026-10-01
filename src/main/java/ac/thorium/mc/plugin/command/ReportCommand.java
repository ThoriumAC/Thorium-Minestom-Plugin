package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.proto.Report;
import ac.thorium.mc.proto.UpStream;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

import java.util.function.Supplier;

import static ac.thorium.mc.plugin.command.ThoriumCommand.send;

public final class ReportCommand extends Command {
    private final Supplier<NetworkSettings> settings;
    private final Supplier<EngineConnection> connection;
    private final Supplier<Telemetry> telemetry;

    public ReportCommand(Supplier<NetworkSettings> settings, Supplier<EngineConnection> connection, Supplier<Telemetry> telemetry) {
        super("report");
        this.settings = settings; this.connection = connection; this.telemetry = telemetry;
        ArgumentWord target = ArgumentType.Word("player");
        target.setSuggestionCallback((sender, ctx, suggestion) -> {
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) suggestion.addEntry(new SuggestionEntry(p.getUsername()));
        });
        ArgumentStringArray reason = ArgumentType.StringArray("reason");
        setDefaultExecutor((sender, ctx) -> send(sender, "§7Usage: /report <player> [reason]"));
        addSyntax((sender, ctx) -> report(sender, ctx.get(target), ""), target);
        addSyntax((sender, ctx) -> report(sender, ctx.get(target), String.join(" ", ctx.get(reason))), target, reason);
    }

    private void report(CommandSender sender, String targetName, String reason) {
        if (!(sender instanceof Player reporter)) { send(sender, "Players only."); return; }
        NetworkSettings s = settings.get();
        if (s == null || !s.reportsEnabled()) { send(sender, "§cReports are turned off on this server."); return; }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
        if (target == null) { send(sender, "§cThat player is not online."); return; }
        if (target == reporter) { send(sender, "§cYou can't report yourself."); return; }
        EngineConnection c = connection.get();
        Telemetry t = telemetry.get();
        boolean sent = c != null && t != null && c.send(UpStream.newBuilder().setReport(Report.newBuilder()
                .setReporter(t.ref(reporter)).setSuspect(t.ref(target)).setReason(reason)).build());
        send(sender, sent ? "§aReport sent. Thanks." : "§cCouldn't send the report right now. Try again shortly.");
    }
}
