package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.telemetry.Names;
import ac.thorium.mc.proto.Mitigate;
import ac.thorium.mc.proto.ResyncBlocks;
import ac.thorium.mc.proto.Setback;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.network.packet.client.play.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class Mitigator implements Consumer<Mitigate> {
    static final int SETBACK_COOLDOWN_TICKS = 5;
    static final int RESYNC_COOLDOWN_TICKS = 10;

    static final class Windows {
        private final Map<UUID, Long> cancelUntil = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastSetback = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastResync = new ConcurrentHashMap<>();

        void open(UUID p, long tick, int ticks) { cancelUntil.put(p, tick + ticks); }
        boolean shouldCancel(UUID p, long tick) { Long u = cancelUntil.get(p); return u != null && tick <= u; }
        void teleportConfirmed(UUID p) { cancelUntil.remove(p); }
        boolean allowSetback(UUID p, long tick) { return allow(lastSetback, p, tick, SETBACK_COOLDOWN_TICKS); }
        boolean allowResync(UUID p, long tick) { return allow(lastResync, p, tick, RESYNC_COOLDOWN_TICKS); }
        private static boolean allow(Map<UUID, Long> last, UUID p, long tick, int cooldown) {
            Long l = last.get(p);
            if (l != null && tick - l < cooldown) return false;
            last.put(p, tick); return true;
        }
        void forget(UUID p) { cancelUntil.remove(p); lastSetback.remove(p); lastResync.remove(p); }
    }

    private final ServerCompat compat;
    private final ErrorGate gate;
    private final PluginConfig cfg;
    private final LongSupplier tick;
    private final Windows windows = new Windows();

    public Mitigator(ServerCompat compat, ErrorGate gate, PluginConfig cfg, LongSupplier tick) {
        this.compat = compat; this.gate = gate; this.cfg = cfg; this.tick = tick;
    }

    @Override
    public void accept(Mitigate m) {
        if (!cfg.mitigate || !cfg.enforce) return;
        UUID id = Names.uuid(m.getPlayer().getUuid());
        Player p = id == null ? null : MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id);
        if (p == null) return;
        long now = tick.getAsLong();
        switch (m.getKindCase()) {
            case SETBACK -> {
                if (!windows.allowSetback(id, now)) return;
                Setback s = m.getSetback();
                p.scheduleNextTick(e -> gate.run("mitigate:setback", () -> {
                    Pos cur = p.getPosition();
                    float yaw = s.getKeepLook() ? cur.yaw() : s.getLook().getYaw();
                    float pitch = s.getKeepLook() ? cur.pitch() : s.getLook().getPitch();
                    p.teleport(new Pos(s.getPosition().getX(), s.getPosition().getY(), s.getPosition().getZ(), yaw, pitch));
                }));
            }
            case CANCEL -> windows.open(id, now, m.getCancel().getTicks());
            case BLOCKS -> {
                if (!windows.allowResync(id, now)) return;
                ResyncBlocks r = m.getBlocks();
                p.scheduleNextTick(e -> gate.run("mitigate:resync", () -> compat.resendSection(p, r.getSx(), r.getSz())));
            }
            default -> { }
        }
    }

    public void onPacket(PlayerPacketEvent event) {
        UUID id = event.getPlayer().getUuid();
        switch (event.getPacket()) {
            case ClientTeleportConfirmPacket t -> windows.teleportConfirmed(id);
            case ClientPlayerPositionPacket t -> cancelIfOpen(event, id);
            case ClientPlayerPositionAndRotationPacket t -> cancelIfOpen(event, id);
            case ClientPlayerRotationPacket t -> cancelIfOpen(event, id);
            case ClientPlayerPositionStatusPacket t -> cancelIfOpen(event, id);
            case ClientVehicleMovePacket t -> cancelIfOpen(event, id);
            default -> { }
        }
    }

    private void cancelIfOpen(PlayerPacketEvent event, UUID id) {
        if (windows.shouldCancel(id, tick.getAsLong())) event.setCancelled(true);
    }

    public void forget(UUID id) { windows.forget(id); }
}
