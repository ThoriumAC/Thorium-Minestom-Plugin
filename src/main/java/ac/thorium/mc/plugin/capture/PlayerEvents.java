package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.telemetry.MetaBuilder;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.entity.EntityTeleportEvent;
import net.minestom.server.event.entity.EntityVelocityEvent;
import net.minestom.server.event.inventory.InventoryClickEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Server-side player events the engine cannot see in packets. */
public final class PlayerEvents {
    // Minestom velocities are blocks per second; the protocol is blocks per client tick.
    private static final double CLIENT_TPS = 20.0;

    private final Telemetry telemetry;
    private final PacketCapture capture;
    private final ErrorGate gate;
    private final NetworkSettings settings;

    public PlayerEvents(Telemetry telemetry, PacketCapture capture, ErrorGate gate, NetworkSettings settings) {
        this.telemetry = telemetry; this.capture = capture; this.gate = gate; this.settings = settings;
    }

    public void register(EventNode<Event> node) {
        node.addListener(PlayerSpawnEvent.class, e -> gate.run("event:spawn", () -> {
            Player p = e.getPlayer();
            if (e.isFirstSpawn()) {
                telemetry.track(p);
                telemetry.event(p, EventFactory.join(ip(p), ServerCompat.protocol(p), "", p.getGameMode().ordinal(), p.getEntityId()));
            } else {
                telemetry.event(p, EventFactory.world(MetaBuilder.dimension(e.getInstance())));
            }
        }));
        node.addListener(PlayerDisconnectEvent.class, e -> gate.run("event:quit", () -> {
            Player p = e.getPlayer();
            telemetry.event(p, EventFactory.quit(""));
            telemetry.untrack(p);
            capture.forget(p.getUuid());
        }));
        node.addListener(EntityTeleportEvent.class, e -> gate.run("event:teleport", () -> {
            if (!(e.getEntity() instanceof Player p)) return;
            Pos to = e.getNewPosition();
            telemetry.event(p, EventFactory.teleport(to.x(), to.y(), to.z(), to.yaw(), to.pitch(), "plugin"));
        }));
        node.addListener(EntityVelocityEvent.class, e -> {
            if (e.isCancelled() || !(e.getEntity() instanceof Player p)) return;
            gate.run("event:velocity", () -> {
                Vec v = e.getVelocity();
                telemetry.event(p, EventFactory.velocity(v.x() / CLIENT_TPS, v.y() / CLIENT_TPS, v.z() / CLIENT_TPS));
            });
        });
        node.addListener(EntityDamageEvent.class, e -> {
            if (e.isCancelled() || !(e.getEntity() instanceof Player p)) return;
            gate.run("event:damage", () -> telemetry.event(p, EventFactory.damage(e.getDamage().getType().key().value(), e.getDamage().getAmount(), 0)));
        });
        node.addListener(InventoryClickEvent.class, e -> telemetry.inventoryChanged(e.getPlayer()));
        node.addListener(InventoryCloseEvent.class, e -> telemetry.inventoryChanged(e.getPlayer()));
        node.addListener(PlayerGameModeChangeEvent.class, e -> {
            if (e.isCancelled()) return;
            gate.run("event:gamemode", () -> telemetry.event(e.getPlayer(), EventFactory.gamemode(e.getNewGameMode().ordinal())));
        });
        node.addListener(PlayerFinishItemUseEvent.class, e -> {
            if (!e.isRiptideSpinAttack()) return;
            gate.run("event:riptide", () -> telemetry.event(e.getPlayer(), EventFactory.boost("riptide")));
        });
        node.addListener(PlayerRespawnEvent.class, e -> gate.run("event:respawn", () -> telemetry.event(e.getPlayer(), EventFactory.respawn())));
    }

    private String ip(Player p) {
        SocketAddress a = p.getPlayerConnection().getRemoteAddress();
        return a instanceof InetSocketAddress i ? EventFactory.hostAddress(i, settings.sendIps()) : "";
    }
}
