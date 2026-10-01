package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EntityMetadata;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.Metadata;

import java.util.Map;

public final class MetadataDecoder {
    private MetadataDecoder() {}

    private static final int IDX_FLAGS = 0, IDX_POSE = 6, IDX_BABY = 16, IDX_PEEK = 17;

    public static EntityMetadata.Builder decode(int id, Map<Integer, Metadata.Entry<?>> data) {
        if (data == null || data.isEmpty()) return null;
        EntityMetadata.Builder b = EntityMetadata.newBuilder().setId(id);
        boolean any = false;
        for (Map.Entry<Integer, Metadata.Entry<?>> d : data.entrySet()) {
            int idx = d.getKey();
            Object v = d.getValue() == null ? null : d.getValue().value();
            if (idx == IDX_FLAGS && v instanceof Number n) {
                int f = n.intValue();
                b.setHasFlags(true).setSneaking((f & 0x02) != 0).setSprinting((f & 0x08) != 0).setSwimming((f & 0x10) != 0)
                 .setInvisible((f & 0x20) != 0).setGliding((f & 0x80) != 0);
            } else if (idx == IDX_POSE && v instanceof EntityPose pose) {
                b.setHasPose(true).setPose(pose.ordinal()).setRiptiding(pose == EntityPose.SPIN_ATTACK);
            } else if (idx == IDX_BABY && v instanceof Integer size) {
                b.setHasSize(true).setSize(size);
            } else if (idx == IDX_BABY && v instanceof Boolean baby) {
                b.setHasBaby(true).setBaby(baby);
            } else if (idx == IDX_PEEK && v instanceof Byte peek) {
                b.setHasPeek(true).setPeek(peek);
            } else continue;
            any = true;
        }
        return any ? b : null;
    }
}
