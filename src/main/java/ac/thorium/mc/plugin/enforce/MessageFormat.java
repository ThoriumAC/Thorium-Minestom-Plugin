package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.proto.Verdict;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;

public final class MessageFormat {
    private MessageFormat() {}

    public static String actionName(Verdict v) {
        String n = v.getAction().name();
        return n.startsWith("ACTION_") ? n.substring(7) : n;
    }

    public static Component format(String template, Verdict v) {
        String s = template == null ? "" : template;
        s = s.replace("%player%", v.getPlayer().getUsername())
             .replace("%check%", v.getAlertType())
             .replace("%vl%", String.valueOf(Math.round(v.getVl())))
             .replace("%confidence%", String.valueOf(Math.round(v.getConfidence() * 100)))
             .replace("%reason%", v.getReason())
             .replace("%action%", actionName(v).toLowerCase(Locale.ROOT));
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public static String consoleLine(Verdict v, Outcome o) {
        return String.format(Locale.ROOT, "[Thorium] %s %s %s conf=%.2f vl=%.1f shadow=%s -> %s",
                actionName(v), v.getAlertType(), v.getPlayer().getUsername(), v.getConfidence(), v.getVl(), v.getShadow(), o);
    }
}
