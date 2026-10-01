package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EntityMove;
import ac.thorium.mc.proto.Vec3;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.function.Consumer;

/** Latest move per entity until the next drain. Offered from the viewer's network thread and the tick, drained on the tick. */
public final class MoveCoalescer {
    private static final int CAP = 256;
    private final int[] ids = new int[CAP];
    private final double[] x = new double[CAP], y = new double[CAP], z = new double[CAP];
    private final float[] yaw = new float[CAP], pitch = new float[CAP];
    private final boolean[] look = new boolean[CAP], ground = new boolean[CAP];
    private final Int2IntOpenHashMap index = new Int2IntOpenHashMap(CAP);
    private int n;

    { index.defaultReturnValue(-1); }

    public synchronized void offer(int id, double px, double py, double pz, boolean hasLook, float pyaw, float ppitch, boolean onGround) {
        int i = index.get(id);
        if (i < 0) { if (n == CAP) return; i = n++; ids[i] = id; look[i] = false; index.put(id, i); }
        x[i] = px; y[i] = py; z[i] = pz; ground[i] = onGround;
        if (hasLook) { look[i] = true; yaw[i] = pyaw; pitch[i] = ppitch; }
    }

    public synchronized void drain(Consumer<EntityMove> out) {
        for (int i = 0; i < n; i++) {
            EntityMove.Builder b = EntityMove.newBuilder().setId(ids[i]).setPosition(Vec3.newBuilder().setX(x[i]).setY(y[i]).setZ(z[i])).setOnGround(ground[i]).setHasLook(look[i]);
            if (look[i]) b.setYaw(yaw[i]).setPitch(pitch[i]);
            out.accept(b.build());
        }
        n = 0;
        index.clear();
    }
}
