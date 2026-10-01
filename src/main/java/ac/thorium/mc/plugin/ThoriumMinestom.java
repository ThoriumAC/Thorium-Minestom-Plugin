package ac.thorium.mc.plugin;

import ac.thorium.mc.plugin.capture.ActivityEvents;
import ac.thorium.mc.plugin.capture.EntityTracker;
import ac.thorium.mc.plugin.capture.OutboundCapture;
import ac.thorium.mc.plugin.capture.PacketCapture;
import ac.thorium.mc.plugin.capture.PlayerEvents;
import ac.thorium.mc.plugin.command.ReportCommand;
import ac.thorium.mc.plugin.command.ThoriumCommand;
import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.NetworkSettings;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.enforce.Enforcer;
import ac.thorium.mc.plugin.enforce.Mitigator;
import ac.thorium.mc.plugin.enforce.StaffAlerts;
import ac.thorium.mc.plugin.telemetry.SampleBuffer;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.*;
import ac.thorium.mc.plugin.world.WorldMirror;
import ac.thorium.mc.plugin.world.WorldSampler;
import ac.thorium.mc.proto.*;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Thorium for Minestom. Call {@link #start(Path)} after {@code MinecraftServer.init()}. */
public final class ThoriumMinestom {
    public static final String VERSION = "1.0.1";

    private final Path dataDir;
    private final Logger log = Logger.getLogger("Thorium");
    private final ErrorGate gate = new ErrorGate(log, 60_000, System::currentTimeMillis);
    private final ServerCompat compat = new ServerCompat();
    private final StaffAlerts alerts = new StaffAlerts();
    private final EntityTracker entityTracker;
    private volatile Predicate<Player> admin = p -> p.getPermissionLevel() >= 4;
    private PluginConfig cfg;

    private EventNode<Event> node;
    private NetworkSettings settings;
    private Enforcer enforcer;
    private Telemetry telemetry;
    private EngineConnection connection;
    private Mitigator mitigator;
    private Task tickTask;
    private ActivityEvents activity;
    private WorldMirror world;
    private WorldSampler worldSampler;

    private ThoriumMinestom(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        this.cfg = loadConfig();
        this.entityTracker = new EntityTracker(cfg.entityRadius);
    }

    public static ThoriumMinestom start(Path dataDir) throws IOException {
        ThoriumMinestom t = new ThoriumMinestom(dataDir);
        MinecraftServer.getCommandManager().register(new ThoriumCommand(t));
        MinecraftServer.getCommandManager().register(new ReportCommand(() -> t.settings, () -> t.connection, () -> t.telemetry));
        t.enable();
        return t;
    }

    /** Who receives alerts and may use /thorium alerts. Default: permission level 2+. */
    public ThoriumMinestom staff(Predicate<Player> staff) { alerts.staff(staff); return this; }

    /** Who may use the admin subcommands. The console always may. Default: permission level 4. */
    public ThoriumMinestom admin(Predicate<Player> admin) { this.admin = admin; return this; }

    public boolean isAdmin(CommandSender s) { return s instanceof ConsoleSender || s instanceof Player p && admin.test(p); }

    private PluginConfig loadConfig() throws IOException {
        Path file = dataDir.resolve("thorium.properties");
        if (Files.notExists(file)) {
            Files.createDirectories(dataDir);
            try (InputStream in = ThoriumMinestom.class.getResourceAsStream("/thorium.properties")) { Files.copy(in, file); }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        return PluginConfig.from(p);
    }

    private void enable() {
        if (!cfg.isConfigured()) {
            log.severe("Thorium: server-token is empty in " + dataDir.resolve("thorium.properties") + " - idle until configured (/thorium reconnect after editing).");
            return;
        }
        if (cfg.needsServerName()) {
            log.severe("Thorium: server-token is a network token, so server-name must say which server this is. Set server-name, then /thorium reconnect.");
            return;
        }
        if (!gatewayUsable()) return;
        startPipeline();
        log.info("Thorium " + VERSION + " enabled on Minestom " + compat.mcVersion());
        for (String line : compatLines()) log.info(line.replaceAll("§.", "").trim());
    }

    public List<String> compatLines() {
        return ThoriumCommand.compatLines(VERSION, compat.mcVersion(), System.getProperty("java.version", "?"), compat.onlineMode(),
                compat.proxyForwarding(), compat.cracked(), world != null && world.enabled(), world == null ? 0 : world.heldSections());
    }

    private boolean gatewayUsable() {
        boolean dev = !cfg.devSessionToken.isEmpty();
        String msg = SessionAuth.insecureGatewayMessage(cfg.gatewayUrl, dev);
        if (msg != null) log.severe(msg);
        return SessionAuth.gatewayAllowed(cfg.gatewayUrl, dev);
    }

    private void startPipeline() {
        SampleBuffer buffer = new SampleBuffer(400);
        settings = new NetworkSettings(cfg.sendIp);
        enforcer = new Enforcer(gate, alerts, cfg, settings, log);
        world = new WorldMirror();
        ConnectionConfig ccfg = new ConnectionConfig(cfg.gatewayUrl, cfg.devSessionToken, VERSION);
        TokenSource tokens = new SessionAuth(cfg.gatewayUrl, cfg.serverToken, cfg.serverName, VERSION, 10_000);
        connection = new EngineConnection(ccfg, tokens, () -> telemetry.buildHello(), new Handler(), log);
        telemetry = new Telemetry(connection, buffer, world, compat, gate, cfg, VERSION, compat.cracked(), log);

        PacketCapture capture = new PacketCapture(telemetry, gate, entityTracker);
        OutboundCapture outbound = new OutboundCapture(telemetry, entityTracker, gate);
        telemetry.setOutbound(outbound);
        mitigator = new Mitigator(compat, gate, cfg, telemetry::tick);
        activity = new ActivityEvents(settings, () -> connection, telemetry, gate);
        worldSampler = new WorldSampler(world, gate, () -> MinecraftServer.getConnectionManager().getOnlinePlayers(), this::engineReady);

        // Last in the global handler, so cancellations by the server's own listeners are already settled.
        node = EventNode.all("thorium").setPriority(Integer.MAX_VALUE);
        node.addListener(PlayerPacketEvent.class, mitigator::onPacket);
        node.addListener(PlayerPacketEvent.class, capture::onPacket);
        node.addListener(PlayerPacketOutEvent.class, outbound::onPacket);
        node.addListener(PlayerDisconnectEvent.class, e -> mitigator.forget(e.getPlayer().getUuid()));
        node.addListener(ServerTickMonitorEvent.class, e -> compat.onTickTime(e.getTickMonitor().getTickTime()));
        new PlayerEvents(telemetry, capture, gate, settings).register(node);
        activity.register(node);
        worldSampler.register(node);
        MinecraftServer.getGlobalEventHandler().addChild(node);

        activity.start();
        worldSampler.start();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) telemetry.track(p);
        telemetry.start();
        tickTask = MinecraftServer.getSchedulerManager().buildTask(() -> gate.run("tick", telemetry::serverTick)).repeat(TaskSchedule.tick(1)).schedule();
        connection.start();
    }

    private boolean engineReady() {
        EngineConnection c = connection;
        return c != null && c.state() == ConnectionState.READY;
    }

    private void stopPipeline() {
        if (tickTask != null) tickTask.cancel();
        if (connection != null) connection.stop();
        if (worldSampler != null) worldSampler.stop();
        if (telemetry != null) telemetry.stop();
        if (activity != null) activity.stop();
        if (node != null) MinecraftServer.getGlobalEventHandler().removeChild(node);
        node = null; activity = null; connection = null; telemetry = null; mitigator = null; tickTask = null;
        worldSampler = null; world = null;
    }

    public void reconnect() {
        try { cfg = loadConfig(); } catch (IOException e) { log.log(Level.SEVERE, "Thorium: could not read config", e); return; }
        stopPipeline();
        enable();
    }

    public void stop() { stopPipeline(); }

    public EngineConnection connection() { return connection; }
    public Telemetry telemetry() { return telemetry; }
    public StaffAlerts staffAlerts() { return alerts; }
    public ServerCompat compat() { return compat; }
    public PluginConfig config() { return cfg; }
    public Path dataDir() { return dataDir; }

    private final class Handler implements DownstreamHandler {
        @Override public void onHelloAck(HelloAck ack) {
            Telemetry t = telemetry;
            if (t != null && ack.hasPolicy()) t.applyPolicy(ack.getPolicy());
            if (t != null) t.snapshotAll();
            if (ack.hasConfig()) settings.apply(ack.getConfig());
        }
        @Override public void onConfig(NetworkConfig c) { settings.apply(c); }
        @Override public void onVerdict(Verdict v) { enforcer.accept(v); }
        @Override public void onMitigate(Mitigate m) { Mitigator mi = mitigator; if (mi != null) mi.accept(m); }
        @Override public void onPolicy(IngestPolicy p) { Telemetry t = telemetry; if (t != null) t.applyPolicy(p); }
        @Override public void onPluginUpdate(PluginUpdate u) { log.warning("Thorium: update available: " + u.getVersion() + " " + u.getDownloadUrl()); }
        @Override public void onGatewayControl(int opcode, byte[] payload) {
            if (opcode == FrameDiscriminator.GATEWAY_MESSAGE) log.info("Thorium gateway: " + new String(payload, StandardCharsets.UTF_8));
            else if (opcode == FrameDiscriminator.GATEWAY_MOD_UPDATE) log.warning("Thorium gateway: update notice");
        }
        @Override public void onStateChange(ConnectionState from, ConnectionState to) {
            Level lvl = (to == ConnectionState.READY || to == ConnectionState.HELD || to == ConnectionState.STOPPED) ? Level.INFO : Level.FINE;
            log.log(lvl, "Thorium: connection " + from + " -> " + to);
        }
        @Override public void onDisconnected() { Telemetry t = telemetry; if (t != null) t.onDisconnected(); }
    }
}
