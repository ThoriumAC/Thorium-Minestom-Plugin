package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.plugin.world.WorldSampler;
import ac.thorium.mc.proto.Activity;
import ac.thorium.mc.proto.ActivityEvent;
import ac.thorium.mc.proto.UpStream;
import ac.thorium.mc.proto.Vec3;
import net.kyori.adventure.key.Keyed;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityDeathEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Server-confirmed player interactions for Advanced Analytics. Only what Minestom itself fires is recorded:
 * crafting, enchanting, fishing and bow shots do not exist unless the server implements them.
 * Nothing is queued while the network has Advanced Analytics off. Chat and commands are never recorded.
 */
public final class ActivityEvents {
    private static final int MAX_QUEUED = 20_000;
    private static final int MAX_PER_FRAME = 2_000;

    private final NetworkSettings settings;
    private final Supplier<EngineConnection> connection;
    private final Telemetry telemetry;
    private final ErrorGate gate;
    private final Queue<ActivityEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private Task timer;

    public ActivityEvents(NetworkSettings settings, Supplier<EngineConnection> connection, Telemetry telemetry, ErrorGate gate) {
        this.settings = settings; this.connection = connection; this.telemetry = telemetry; this.gate = gate;
    }

    public void start() {
        timer = MinecraftServer.getSchedulerManager().buildTask(() -> gate.run("activity:flush", this::flush)).repeat(TaskSchedule.tick(20)).schedule();
    }

    public void stop() { if (timer != null) timer.cancel(); timer = null; queue.clear(); size.set(0); }

    void flush() {
        EngineConnection c = connection.get();
        while (!queue.isEmpty()) {
            List<ActivityEvent> batch = new ArrayList<>();
            ActivityEvent e;
            while (batch.size() < MAX_PER_FRAME && (e = queue.poll()) != null) batch.add(e);
            size.addAndGet(-batch.size());
            if (batch.isEmpty() || c == null) return;
            if (!c.send(UpStream.newBuilder().setActivity(Activity.newBuilder().addAllEvents(batch)).build())) return;
        }
    }

    private void record(Player p, String kind, String subject, String detail, Player target, Instance world, Point at, double amount) {
        if (!settings.advancedAnalytics() || p == null) return;
        if (size.incrementAndGet() > MAX_QUEUED) { size.decrementAndGet(); return; }
        ActivityEvent.Builder b = ActivityEvent.newBuilder()
                .setPlayer(telemetry.ref(p)).setAtMs(System.currentTimeMillis()).setKind(kind)
                .setSubject(subject == null ? "" : subject).setDetail(detail == null ? "" : detail).setAmount(amount);
        if (target != null) b.setTarget(telemetry.ref(target));
        if (world != null && at != null) b.setWorld(WorldSampler.key(world)).setPosition(Vec3.newBuilder().setX(at.x()).setY(at.y()).setZ(at.z()));
        queue.add(b.build());
    }

    // Bukkit-style names (STONE, DIAMOND_SWORD) so the dashboard reads the same across platforms.
    private static String name(Keyed k) { return k == null ? "" : k.key().value().toUpperCase(Locale.ROOT); }

    private static String type(ItemStack it) { return it == null || it.isAir() ? "" : name(it.material()); }

    public void register(EventNode<Event> node) {
        node.addListener(PlayerBlockBreakEvent.class, e -> {
            if (e.isCancelled()) return;
            gate.run("activity:break", () -> record(e.getPlayer(), "block_break", name(e.getBlock()), type(e.getPlayer().getItemInMainHand()), null, e.getInstance(), e.getBlockPosition(), 1));
        });
        node.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (e.isCancelled()) return;
            gate.run("activity:place", () -> record(e.getPlayer(), "block_place", name(e.getBlock()), "", null, e.getInstance(), e.getBlockPosition(), 1));
        });
        node.addListener(EntityDeathEvent.class, e -> gate.run("activity:death", () -> {
            if (!(e.getEntity() instanceof LivingEntity dead)) return;
            Damage last = dead.getLastDamageSource();
            Player killer = last != null && last.getAttacker() instanceof Player k ? k : null;
            Player victim = dead instanceof Player v ? v : null;
            if (killer != null) record(killer, "kill", name(dead.getEntityType()), type(killer.getItemInMainHand()), victim, dead.getInstance(), dead.getPosition(), 1);
            if (victim != null) record(victim, "death", last == null ? "" : last.getType().key().value().toUpperCase(Locale.ROOT),
                    killer == null ? "" : type(killer.getItemInMainHand()), killer, victim.getInstance(), victim.getPosition(), 1);
        }));
        node.addListener(PlayerFinishItemUseEvent.class, e -> {
            if (!e.getItemStack().has(DataComponents.CONSUMABLE)) return;
            gate.run("activity:consume", () -> record(e.getPlayer(), "consume", type(e.getItemStack()), "", null, e.getPlayer().getInstance(), e.getPlayer().getPosition(), 1));
        });
        node.addListener(PlayerUseItemEvent.class, e -> {
            if (e.isCancelled() || e.getItemStack().isAir()) return;
            gate.run("activity:use", () -> record(e.getPlayer(), "item_use", type(e.getItemStack()), "", null, e.getPlayer().getInstance(), e.getPlayer().getPosition(), 1));
        });
        node.addListener(ItemDropEvent.class, e -> {
            if (e.isCancelled()) return;
            gate.run("activity:drop", () -> record(e.getPlayer(), "drop", type(e.getItemStack()), "", null, e.getPlayer().getInstance(), e.getPlayer().getPosition(), e.getItemStack().amount()));
        });
        node.addListener(PickupItemEvent.class, e -> {
            if (e.isCancelled() || !(e.getLivingEntity() instanceof Player p)) return;
            gate.run("activity:pickup", () -> record(p, "pickup", type(e.getItemStack()), "", null, p.getInstance(), p.getPosition(), e.getItemStack().amount()));
        });
    }
}
