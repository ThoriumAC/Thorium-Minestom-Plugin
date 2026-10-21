package ac.thorium.mc.plugin.capture;

import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkinTexture {
    private static final Pattern SKIN = Pattern.compile("\"SKIN\"\\s*:\\s*\\{(?:[^{}]|\\{[^{}]*\\})*?\"url\"\\s*:\\s*\"https?://textures\\.minecraft\\.net/texture/([0-9a-f]{32,64})\"");

    private SkinTexture() {}

    public static String hashFromProperty(String base64Value) {
        if (base64Value == null) return "";
        try {
            Matcher m = SKIN.matcher(new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public static String of(Player p) {
        PlayerSkin skin = p.getSkin();
        return skin == null ? "" : hashFromProperty(skin.textures());
    }
}
