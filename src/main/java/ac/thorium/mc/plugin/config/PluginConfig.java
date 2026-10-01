package ac.thorium.mc.plugin.config;

import java.util.Properties;

public final class PluginConfig {
    public final String gatewayUrl;
    public final String serverToken;
    public final String serverName;
    public final int flushIntervalMs;
    public final boolean enforce;
    public final boolean mitigate;
    public final String banCommand;
    public final String unbanCommand;
    public final boolean sendIp;
    public final String alertFormat;
    public final String warnFormat;
    public final String kickFormat;
    public final String devSessionToken;
    public final int entityRadius;

    private PluginConfig(Properties c) {
        String url = str(c, "gateway-url", "https://gateway.thorium.ac");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.gatewayUrl = url;
        this.serverToken = str(c, "server-token", "");
        this.serverName = str(c, "server-name", "");
        this.flushIntervalMs = Math.max(25, Math.min(1000, num(c, "flush-interval-ms", 75)));
        this.enforce = bool(c, "enforce", true);
        this.mitigate = bool(c, "mitigate", true);
        this.banCommand = str(c, "ban-command", "");
        this.unbanCommand = str(c, "unban-command", "");
        this.sendIp = bool(c, "send-ip", false);
        this.alertFormat = c.getProperty("alert-format", "&8[&cThorium&8] &f%player% &7failed &e%check% &7(VL %vl%, %confidence%%)");
        this.warnFormat = c.getProperty("warn-format", "&c[Thorium] &fSuspicious activity detected (%check%). This is a warning.");
        this.kickFormat = c.getProperty("kick-format", "&cKicked by Thorium anti-cheat\n&7%reason%");
        this.devSessionToken = str(c, "dev.session-token", "");
        this.entityRadius = Math.max(4, Math.min(32, num(c, "entity-radius", 12)));
    }

    public static PluginConfig from(Properties c) { return new PluginConfig(c); }

    private static String str(Properties c, String k, String def) { return c.getProperty(k, def).trim(); }

    private static boolean bool(Properties c, String k, boolean def) { return Boolean.parseBoolean(str(c, k, String.valueOf(def))); }

    private static int num(Properties c, String k, int def) {
        try { return Integer.parseInt(str(c, k, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    public boolean isConfigured() { return !serverToken.isEmpty() || !devSessionToken.isEmpty(); }

    public boolean usesNetworkToken() { return serverToken.startsWith("org_"); }

    public boolean needsServerName() { return usesNetworkToken() && serverName.isEmpty(); }
    public boolean useBanCommand() { return !banCommand.isEmpty(); }
    public boolean useUnbanCommand() { return !unbanCommand.isEmpty(); }
}
