package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.transport.ConnectionState;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.plugin.capture.OutboundCapture;
import ac.thorium.mc.plugin.capture.SampleFactory;
import ac.thorium.mc.plugin.world.WorldMirror;
import ac.thorium.mc.proto.*;
import ac.thorium.mc.plugin.capture.ItemNames;
import ac.thorium.mc.plugin.world.WorldSampler;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.common.PingPacket;
import net.minestom.server.potion.TimedPotion;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class Telemetry {
    private final EngineConnection conn;
    private final SampleBuffer buffer;
    private final WorldMirror world;
    private final ServerCompat compat;
    private final ErrorGate gate;
    private final String pluginVersion;
    private final boolean cracked;
    private final Logger log;

    private final Map<UUID, PlayerRef> roster = new ConcurrentHashMap<>();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerRef> byEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, Teleport> pendingTeleport = new ConcurrentHashMap<>();
    private volatile OutboundCapture outbound;

    private record Teleport(int id, double x, double y, double z) {}

    public void setOutbound(OutboundCapture o) { this.outbound = o; }
    private final TransactionTracker transactions = new TransactionTracker(System::nanoTime);
    private final AtomicLong tick = new AtomicLong();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Thorium-Flush"); t.setDaemon(true); return t; });
    private volatile ScheduledFuture<?> flushTask, heartbeatTask;
    private volatile int flushIntervalMs;
    private final ConcurrentMap<String, CaptureWriter> captures = new ConcurrentHashMap<>();
    private final AtomicLong captureDropped = new AtomicLong();
    private static final int CAPTURE_QUEUE = 4096;
    private final ThreadPoolExecutor captureExec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CAPTURE_QUEUE),
            r -> { Thread t = new Thread(r, "Thorium-Capture"); t.setDaemon(true); return t; },
            (r, ex) -> captureDropped.incrementAndGet());

    public Telemetry(EngineConnection conn, SampleBuffer buffer, WorldMirror world, ServerCompat compat, ErrorGate gate,
                     PluginConfig cfg, String pluginVersion, boolean cracked, Logger log) {
        this.conn = conn; this.buffer = buffer; this.world = world; this.compat = compat; this.gate = gate;
        this.pluginVersion = pluginVersion; this.cracked = cracked; this.log = log;
        this.flushIntervalMs = cfg.flushIntervalMs;
    }

    public void start() {
        scheduleFlush();
        heartbeatTask = exec.scheduleAtFixedRate(() -> gate.run("heartbeat", this::heartbeat), 10, 10, TimeUnit.SECONDS);
    }

    public void stop() {
        if (flushTask != null) flushTask.cancel(false);
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        stopCaptures();
        exec.shutdownNow();
        captureExec.shutdownNow();
        world.clear();
        roster.clear(); players.clear(); buffer.clear();
    }

    private void sendPing(Player p, int id) { p.sendPacket(new PingPacket(id)); }

    // SENT goes into the buffer before the ping so its ACK can never be sampled first.

    public void tickTransaction(Player p) {
        if (!roster.containsKey(p.getUuid())) return;
        OutboundCapture o = outbound;
        if (o != null) o.flushMoves(p, tick.get());
        int id = transactions.send(p.getUuid(), TransactionCause.TRANSACTION_CAUSE_TICK, 0);
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_TICK, 0));
        sendPing(p, id);
    }

    public void fence(Player p, long outboundSeq) {
        if (outboundSeq < 0 || !roster.containsKey(p.getUuid())) return;
        int id = transactions.send(p.getUuid(), TransactionCause.TRANSACTION_CAUSE_OUTBOUND, outboundSeq);
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_OUTBOUND, 0, outboundSeq));
        sendPing(p, id);
    }

    public long outbound(Player p, Outbound.Builder o) {
        return buffer.add(p.getUuid(), ref(p), Sample.newBuilder().setClientTimeMs(System.currentTimeMillis()).setTick(tick.get()).setOutbound(o));
    }

    public void transactionAck(Player p, int id) {
        TransactionTracker.Ack a = transactions.ack(p.getUuid(), id);
        if (a == null) return;
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_ACK, a.cause(), a.rttMs(), a.fencesSeq()));
    }

    public void blockChangeSent(Player p, int x, int y, int z) {
        if (world.enabled()) world.invalidate(WorldSampler.sectionOf(WorldSampler.key(p.getInstance()), x, y, z));
    }

    public void teleportSent(Player p, int teleportId, double x, double y, double z) {
        pendingTeleport.put(p.getUuid(), new Teleport(teleportId, x, y, z));
        teleportSent(p, teleportId);
    }

    public int confirmsTeleport(Player p, double x, double y, double z) {
        Teleport t = pendingTeleport.get(p.getUuid());
        if (t == null) return 0;
        if (Math.abs(t.x() - x) > 1e-6 || Math.abs(t.y() - y) > 1e-6 || Math.abs(t.z() - z) > 1e-6) return 0;
        pendingTeleport.remove(p.getUuid());
        return t.id();
    }

    public void teleportSent(Player p, int teleportId) {
        if (!roster.containsKey(p.getUuid())) return;
        sample(p, SampleFactory.transaction(teleportId, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_TELEPORT, 0));
    }

    public void teleportConfirmed(Player p, int teleportId) {
        sample(p, SampleFactory.transaction(teleportId, TransactionPhase.TRANSACTION_PHASE_ACK, TransactionCause.TRANSACTION_CAUSE_TELEPORT, 0));
    }

    private void scheduleFlush() {
        ScheduledFuture<?> old = flushTask;
        if (old != null) old.cancel(false);
        flushTask = exec.scheduleAtFixedRate(() -> gate.run("flush", this::flush), flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean send(UpStream u) {
        if (!captures.isEmpty()) {
            final UpStream frame = u;
            submitCapture(() -> { for (CaptureWriter c : captures.values()) c.write(frame); });
        }
        return conn.send(u);
    }

    private void submitCapture(Runnable r) {
        try { captureExec.execute(r); }
        catch (RejectedExecutionException e) { captureDropped.incrementAndGet(); }
    }

    private void flush() {
        Batch b = buffer.drain(System.currentTimeMillis(), tick.get(), compat.tps(), compat.mspt());
        if (b != null && !send(UpStream.newBuilder().setBatch(b).build())) buffer.clear();
        flushWorld();
    }

    private void flushWorld() {
        if (!connected() || !world.hasWork()) return;
        for (UpStream u : world.drain(System.currentTimeMillis(), tick.get(), 0)) {
            if (send(u)) continue;
            world.reset();
            return;
        }
    }

    private void heartbeat() {
        send(UpStream.newBuilder().setHeartbeat(Heartbeat.newBuilder().setClientTimeMs(System.currentTimeMillis())
                .setOnlinePlayers(roster.size()).setTps(compat.tps()).setMspt(compat.mspt())).build());
    }

    public synchronized void startCapture(String owner, File file) throws IOException {
        stopCapture(owner);
        final CaptureWriter c = new CaptureWriter(file);
        final UpStream hello = UpStream.newBuilder().setHello(buildHello()).build();
        submitCapture(() -> c.write(hello));
        captures.put(owner, c);
        if (world.enabled()) world.reset();
        snapshotAll();
    }

    public void snapshotAll() {
        gate.run("tags", () -> sendBlockTags(true));
        for (Player p : players.values()) p.scheduleNextTick(e -> gate.run("state:snapshot", () -> {
            inventorySnapshot(p);
            stateSnapshot(p);
            OutboundCapture o = outbound;
            if (o != null && roster.containsKey(p.getUuid())) o.restate(p);
        }));
    }

    public static EntityMetadata.Builder flagsOf(Player e) {
        return EntityMetadata.newBuilder().setId(e.getEntityId()).setHasFlags(true)
                .setSneaking(e.isSneaking()).setSprinting(e.isSprinting())
                .setSwimming(e.getPose() == EntityPose.SWIMMING).setGliding(e.isFlyingWithElytra());
    }

    public void stateSnapshot(Player p) {
        if (!p.isOnline() || !roster.containsKey(p.getUuid())) return;
        int id = p.getEntityId();
        fence(p, outbound(p, Outbound.newBuilder().setEntityMetadata(flagsOf(p))));
        GameMode gm = p.getGameMode();
        fence(p, outbound(p, Outbound.newBuilder().setPlayerAbilities(PlayerAbilities.newBuilder().setMayFly(p.isAllowFlying())
                .setFlying(p.isFlying()).setFlySpeed(p.getFlyingSpeed()).setWalkSpeed(p.getFieldViewModifier())
                .setInvulnerable(p.isInvulnerable() || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR))));
        fence(p, outbound(p, Outbound.newBuilder().setHealth(Health.newBuilder()
                .setHealth(p.getHealth()).setFood(p.getFood()).setSaturation(p.getFoodSaturation()))));
        fence(p, outbound(p, Outbound.newBuilder().setTickingState(ac.thorium.mc.proto.TickingState.newBuilder()
                .setTickRate(net.minestom.server.MinecraftServer.TICK_PER_SECOND))));
        for (TimedPotion t : p.getActiveEffects()) {
            fence(p, outbound(p, Outbound.newBuilder().setEntityEffect(EntityEffect.newBuilder().setId(id)
                    .setEffect(t.potion().effect().key().asString()).setAmplifier(t.potion().amplifier()).setDuration(t.potion().duration()))));
        }
    }

    public synchronized CaptureWriter stopCapture(String owner) {
        CaptureWriter c = captures.remove(owner);
        if (c != null) closeWhenDrained(c);
        return c;
    }

    private void closeWhenDrained(final CaptureWriter c) {
        try {
            captureExec.submit(() -> c.close()).get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.close();
        } catch (Throwable t) {
            c.close();
        }
    }

    public synchronized List<CaptureWriter> stopCaptures() {
        List<CaptureWriter> out = new ArrayList<>();
        for (String k : new ArrayList<>(captures.keySet())) {
            CaptureWriter c = captures.remove(k);
            if (c != null) { closeWhenDrained(c); out.add(c); }
        }
        return out;
    }

    public void track(Player p) {
        roster.put(p.getUuid(), Names.ref(p.getUuid(), p.getUsername(), cracked));
        players.put(p.getUuid(), p);
        byEntityId.put(p.getEntityId(), roster.get(p.getUuid()));
        p.scheduleNextTick(e -> gate.run("inventory:snapshot", () -> inventorySnapshot(p)));
        gate.run("tags", () -> sendBlockTags(false));
    }

    private volatile int tagsHash;

    // Tags are server-wide: sent on every connect, and again when a join finds
    // them changed.
    private void sendBlockTags(boolean force) {
        ac.thorium.mc.proto.BlockTags t = ac.thorium.mc.plugin.capture.ServerTags.blocks();
        if (t == null || (!force && t.hashCode() == tagsHash)) return;
        tagsHash = t.hashCode();
        send(UpStream.newBuilder().setBlockTags(t).build());
    }

    public void inventorySnapshot(Player p) {
        if (!p.isOnline() || !roster.containsKey(p.getUuid())) return;
        PlayerInventory inv = p.getInventory();
        WindowItems.Builder b = WindowItems.newBuilder().setWindow(0);
        for (int i = 0; i < 36; i++) snapshotSlot(b, i < 9 ? 36 + i : i, inv.getItemStack(i));
        for (int i = PlayerInventoryUtils.HELMET_SLOT; i <= PlayerInventoryUtils.BOOTS_SLOT; i++) snapshotSlot(b, i - PlayerInventoryUtils.HELMET_SLOT + 5, inv.getItemStack(i));
        snapshotSlot(b, 45, inv.getItemStack(PlayerInventoryUtils.OFFHAND_SLOT));
        fence(p, outbound(p, Outbound.newBuilder().setWindowItems(b)));
        fence(p, outbound(p, Outbound.newBuilder().setHeldSlot(HeldSlotOut.newBuilder().setSlot(p.getHeldSlot()))));
    }

    private final Set<UUID> snapshotQueued = ConcurrentHashMap.newKeySet();

    public void inventoryChanged(Player p) {
        if (!roster.containsKey(p.getUuid()) || !snapshotQueued.add(p.getUuid())) return;
        p.scheduleNextTick(e -> gate.run("inventory:snapshot", () -> {
            snapshotQueued.remove(p.getUuid());
            inventorySnapshot(p);
        }));
    }

    private static void snapshotSlot(WindowItems.Builder b, int protocolSlot, ItemStack it) {
        if (it == null || it.isAir()) return;
        b.addSlots(ItemNames.slotOut(protocolSlot, it));
    }

    public void untrack(Player p) {
        UUID id = p.getUuid();
        roster.remove(id); players.remove(id); buffer.remove(id); transactions.forget(id);
        byEntityId.remove(p.getEntityId()); pendingTeleport.remove(id);
        OutboundCapture o = outbound;
        if (o != null) o.forget(id);
    }

    public PlayerRef ref(Player p) {
        PlayerRef r = roster.get(p.getUuid());
        return r != null ? r : Names.ref(p.getUuid(), p.getUsername(), cracked);
    }

    public PlayerRef refFor(int entityId) { return byEntityId.get(entityId); }

    public long tick() { return tick.get(); }

    public void serverTick() {
        tick.incrementAndGet();
        compat.onTick(System.nanoTime());
        if (!connected()) return;
        for (Player p : players.values()) {
            if (!p.isOnline()) continue;
            buffer.meta(p.getUuid(), MetaBuilder.build(p));
            tickTransaction(p);
        }
    }

    public void sample(Player p, Sample.Builder s) {
        buffer.add(p.getUuid(), ref(p), s.setClientTimeMs(System.currentTimeMillis()).setTick(tick.get()));
    }

    public void event(Player p, PlayerEvent.Builder e) {
        e.setPlayer(ref(p)).setClientTimeMs(System.currentTimeMillis()).setTick(tick.get());
        send(UpStream.newBuilder().setPlayerEvent(e).build());
    }

    public void applyPolicy(IngestPolicy policy) {
        if (policy.getFlushIntervalMs() > 0) {
            int ms = Math.max(25, Math.min(1000, policy.getFlushIntervalMs()));
            if (ms != flushIntervalMs) { flushIntervalMs = ms; scheduleFlush(); log.info("Thorium: flush interval now " + ms + " ms"); }
        }
        buffer.setEnabledCategories(policy.getEnabledCategoriesList());
        buffer.setRelativeTimes(policy.getRelativeSampleTimes());
        world.setPolicy(policy.getWorld());
    }

    public boolean connected() { return conn.state() == ConnectionState.READY; }

    public void onDisconnected() { buffer.clear(); world.reset(); }

    public Hello buildHello() {
        return Hello.newBuilder().setPluginVersion(pluginVersion).setMcVersion(compat.mcVersion()).setSoftware(ServerSoftware.SERVER_SOFTWARE_MINESTOM)
                .setProtocol(2).addAllRoster(roster.values()).build();
    }

    public String statusLine() {
        StringBuilder rec = new StringBuilder();
        for (CaptureWriter c : captures.values()) {
            rec.append(rec.length() == 0 ? ", capturing " : ", ")
               .append(c.file().getName()).append(" (").append(c.frames()).append(" frames)");
        }
        long lost = captureDropped.get();
        if (lost > 0) rec.append(", ").append(lost).append(" capture frames dropped");
        return "queued players " + buffer.pending() + ", dropped " + buffer.dropped() + ", flush " + flushIntervalMs + " ms, " + roster.size() + " players, tick " + tick.get()
                + (world.enabled() ? ", world " + world.heldSections() + " sections, " + world.snapshotMicros() + " us/snapshot over " + world.snapshotCount()
                                        + (world.syncing() ? " (syncing, " + world.pendingColumnCount() + " columns left)" : "") : "")
                + rec;
    }
}
