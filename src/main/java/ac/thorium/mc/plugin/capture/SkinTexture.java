package ac.thorium.mc.plugin.capture;


import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Mojang texture hash of the skin a player joined with, read from their game profile. */
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

    public static String of(net.minestom.server.entity.Player p) {
        net.minestom.server.entity.PlayerSkin skin = p.getSkin();
        return skin == null ? "" : hashFromProperty(skin.textures());
    }
}
