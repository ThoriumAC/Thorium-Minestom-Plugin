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
import ac.thorium.mc.plugin.config.ConfigFile;
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
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Thorium for Minestom. Call {@link #start(Path)} after {@code MinecraftServer.init()}. */
public final class ThoriumMinestom {
    public static final String VERSION = "1.1.0";
    public static final String REPORT = "thorium.report", ALERTS = "thorium.alerts", ADMIN = "thorium.admin";
    public static final Path CONFIG_FILE = Path.of("config", "thorium.properties");

    private final Path dataDir;
    private final Logger log = Logger.getLogger("Thorium");
    private final ErrorGate gate = new ErrorGate(log, 60_000, System::currentTimeMillis);
    private final ServerCompat compat = new ServerCompat();
    private final StaffAlerts alerts = new StaffAlerts(p -> hasPermission(p, ALERTS));
    private final EntityTracker entityTracker;
    private volatile BiPredicate<Player, String> permissions = ThoriumMinestom::defaultPermission;
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
        MinecraftServer.getCommandManager().register(new ReportCommand(s -> t.hasPermission(s, REPORT), () -> t.settings, () -> t.connection, () -> t.telemetry));
        t.enable();
        return t;
    }

    public ThoriumMinestom permissions(BiPredicate<Player, String> permissions) { this.permissions = permissions; return this; }

    public boolean hasPermission(CommandSender s, String node) { return s instanceof ConsoleSender || s instanceof Player p && permissions.test(p, node); }

    static boolean defaultPermission(Player p, String node) {
        return switch (node) {
            case REPORT -> true;
            case ALERTS -> p.getPermissionLevel() >= 2;
            case ADMIN -> p.getPermissionLevel() >= 4;
            default -> false;
        };
    }

    private PluginConfig loadConfig() throws IOException {
        ConfigFile.migrate(dataDir.resolve("thorium.properties"), CONFIG_FILE, log);
        if (Files.notExists(CONFIG_FILE)) {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (InputStream in = ThoriumMinestom.class.getResourceAsStream("/thorium.properties")) { Files.copy(in, CONFIG_FILE); }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) { p.load(r); }
        return PluginConfig.from(p);
    }

    private void enable() {
        if (!cfg.isConfigured()) {
            log.severe("Thorium: not configured. Run /thorium setup <code> with a code from the dashboard, or fill in " + CONFIG_FILE + " and run /thorium reconnect.");
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
        SampleBuffer buffer = new SampleBuffer(2048);
        settings = new NetworkSettings();
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
        ActivityEvents a = activity;
        ac.thorium.mc.api.ThoriumStats.bind(a::custom);
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
        ac.thorium.mc.api.ThoriumStats.bind(null);
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
            Level lvl = (to == ConnectionState.HELD || to == ConnectionState.STOPPED) ? Level.INFO : Level.FINE;
            log.log(lvl, "Thorium: connection " + from + " -> " + to);
        }
        @Override public void onDisconnected() { Telemetry t = telemetry; if (t != null) t.onDisconnected(); }
    }
}
