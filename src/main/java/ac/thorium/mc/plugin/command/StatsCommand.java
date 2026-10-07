package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.proto.Activity;
import ac.thorium.mc.proto.ActivityEvent;
import ac.thorium.mc.proto.UpStream;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.entity.Player;

import java.util.function.Supplier;

public final class StatsCommand extends Command {
    public StatsCommand(Supplier<EngineConnection> connection, Supplier<Telemetry> telemetry) {
        super("stats");
        ArgumentWord action = ArgumentType.Word("action").from("hide", "show");
        setDefaultExecutor((sender, ctx) -> sender.sendMessage("Usage: /stats <hide|show>"));
        addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
            boolean hide = ctx.get(action).equals("hide");
            EngineConnection c = connection.get();
            Telemetry t = telemetry.get();
            boolean sent = c != null && t != null && c.send(UpStream.newBuilder().setActivity(Activity.newBuilder().addEvents(
                    ActivityEvent.newBuilder().setPlayer(t.ref(p)).setAtMs(System.currentTimeMillis())
                            .setKind(hide ? "stats_hide" : "stats_show"))).build());
            sender.sendMessage(!sent ? "Couldn't reach Thorium right now. Try again shortly."
                    : hide ? "You're hidden from this network's public stats." : "You're visible on this network's public stats again.");
        }, action);
    }
}
