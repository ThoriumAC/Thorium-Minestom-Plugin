package ac.thorium.mc.plugin.compat;

import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;

public final class ServerCompat {
    private static final long TPS_WINDOW_NANOS = 5_000_000_000L;
    private final long[] tickNanos = new long[256];
    private int tickCount;
    private volatile double tps = 20.0;
    private volatile double mspt;

    public String mcVersion() { return MinecraftServer.VERSION_NAME; }

    public String proxyForwarding() {
        return switch (MinecraftServer.process().auth()) {
            case Auth.Velocity v -> "velocity-modern";
            case Auth.Bungee b -> "bungeecord";
            default -> "none";
        };
    }

    public boolean onlineMode() { return MinecraftServer.process().auth() instanceof Auth.Online; }

    public boolean cracked() { return MinecraftServer.process().auth() instanceof Auth.Offline; }

    public synchronized void onTick(long nowNanos) {
        tickNanos[tickCount % tickNanos.length] = nowNanos;
        tickCount++;
        int n = Math.min(tickCount, tickNanos.length);
        long[] ordered = new long[n];
        for (int i = 0; i < n; i++) ordered[i] = tickNanos[(tickCount - n + i) % tickNanos.length];
        tps = tpsFromTicks(ordered, n, nowNanos);
    }

    public static double tpsFromTicks(long[] ticks, int count, long nowNanos) {
        if (count < 2) return 20.0;
        int first = 0;
        while (first < count - 1 && nowNanos - ticks[first] > TPS_WINDOW_NANOS) first++;
        long span = ticks[count - 1] - ticks[first];
        int n = count - 1 - first;
        if (n < 1 || span <= 0) return 20.0;
        return Math.max(0.0, Math.min(20.0, n * 1_000_000_000.0 / span));
    }

    public double tps() { return tps; }

    public void onTickTime(double ms) { mspt = ms; }

    public double mspt() { return mspt; }

    // Resends the whole column: Minestom has no per-section resend, and the cooldown keeps this rare.
    public void resendSection(Player p, int sx, int sz) {
        Instance in = p.getInstance();
        Chunk c = in == null ? null : in.getChunk(sx, sz);
        if (c != null) c.sendChunk(p);
    }

    public static int protocol(Player p) { return p.getPlayerConnection().getProtocolVersion(); }
}
