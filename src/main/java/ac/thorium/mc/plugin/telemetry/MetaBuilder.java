package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.world.WorldSampler;
import ac.thorium.mc.proto.ServerMeta;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

public final class MetaBuilder {
    private MetaBuilder() {}

    public static ServerMeta build(Player p) {
        Instance in = p.getInstance();
        return ServerMeta.newBuilder()
                .setGamemode(p.getGameMode().ordinal())
                .setDimension(in == null ? "" : dimension(in))
                .setWorld(WorldSampler.key(in))
                .setEntityId(p.getEntityId())
                .setProtocolVersion(ServerCompat.protocol(p))
                .setPingMs(p.getLatency())
                .setDead(p.isDead())
                .setSleeping(p.getPose() == EntityPose.SLEEPING)
                .build();
    }

    public static String dimension(Instance in) { return in.getDimensionType().key().value(); }
}
