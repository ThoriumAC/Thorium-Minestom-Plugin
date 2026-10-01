package ac.thorium.mc.plugin.world;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.proto.UpStream;
import ac.thorium.mc.proto.WorldPolicy;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class WorldSamplerSyncTest {
    @Test
    void fullSyncCompletesWhenColumnsInRadiusAreUnloaded(Env env) {
        var in = env.createFlatInstance();
        in.enableAutoChunkLoad(false);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) in.loadChunk(x, z).join();
        Player p = env.createPlayer(in, new Pos(0.5, 72, 0.5));
        WorldMirror m = new WorldMirror();
        m.setPolicy(WorldPolicy.newBuilder().setEnabled(true).setRadiusChunks(2).setColumnsPerTick(2).build());
        WorldSampler s = new WorldSampler(m, new ErrorGate(Logger.getLogger("test"), 0, System::currentTimeMillis), () -> List.of(p), () -> true);
        s.start();
        boolean done = false;
        for (int t = 0; t < 200 && !done; t++) {
            env.tick();
            if (!m.hasWork()) continue;
            for (UpStream u : m.drain(0, t, 0)) done |= u.hasWorldChunk() && u.getWorldChunk().getFullSyncDone();
        }
        s.stop();
        assertTrue(done);
    }
}
