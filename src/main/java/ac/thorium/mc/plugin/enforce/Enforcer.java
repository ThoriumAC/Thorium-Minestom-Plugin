package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.telemetry.Names;
import ac.thorium.mc.proto.Verdict;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.network.ConnectionManager;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class Enforcer implements Consumer<Verdict> {
    private final ErrorGate gate;
    private final StaffAlerts alerts;
    private final PluginConfig cfg;
    private final NetworkSettings settings;
    private final Logger log;

    public Enforcer(ErrorGate gate, StaffAlerts alerts, PluginConfig cfg, NetworkSettings settings, Logger log) {
        this.gate = gate; this.alerts = alerts; this.cfg = cfg; this.settings = settings; this.log = log;
    }

    @Override
    public void accept(Verdict v) {
        Outcome o = EnforcementDecision.decide(v, cfg.enforce, cfg.useBanCommand(), cfg.useUnbanCommand());
        log.info(MessageFormat.consoleLine(v, o));
        if (o == Outcome.LOG_ONLY) return;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> gate.run("enforce", () -> apply(v, o)));
    }

    private void apply(Verdict v, Outcome o) {
        String name = v.getPlayer().getUsername();
        ConnectionManager players = MinecraftServer.getConnectionManager();
        if (o == Outcome.UNBAN_COMMAND) {
            command(cfg.unbanCommand.replace("%player%", name));
            return;
        }
        if (o == Outcome.UNBAN_BUKKIT) {
            log.warning("Thorium: dashboard unbanned " + name + ", but Minestom has no ban list; set unban-command to lift bans made by ban-command");
            return;
        }
        Component alertLine = MessageFormat.format(cfg.alertFormat, v);
        for (Player staff : alerts.recipients(players.getOnlinePlayers())) staff.sendMessage(alertLine);

        UUID id = Names.uuid(v.getPlayer().getUuid());
        Player target = id == null ? null : players.getOnlinePlayerByUuid(id);
        if (target == null) target = players.getOnlinePlayerByUsername(name);
        if (o == Outcome.STAFF_ALERT) return;

        if (o == Outcome.BAN_COMMAND) {
            command(cfg.banCommand.replace("%player%", name).replace("%reason%", v.getReason()).replace("%check%", v.getAlertType()));
            return;
        }
        if (o == Outcome.BAN_BUKKIT) log.warning("Thorium: ban for " + name + " only kicks: Minestom has no ban list, set ban-command to persist bans");
        if (target == null) return;
        Player p = target;
        p.scheduleNextTick(e -> gate.run("enforce:player", () -> {
            switch (o) {
                case WARN_PLAYER -> p.sendMessage(MessageFormat.format(cfg.warnFormat, v));
                case KICK -> p.kick(MessageFormat.format(settings.kickMessage(cfg.kickFormat), v));
                case BAN_BUKKIT -> p.kick(MessageFormat.format(settings.banMessage(settings.kickMessage(cfg.kickFormat)), v));
                default -> { }
            }
        }));
    }

    private static void command(String cmd) { MinecraftServer.getCommandManager().executeServerCommand(cmd); }
}
