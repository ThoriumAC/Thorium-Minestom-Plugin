package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.telemetry.SampleBuffer;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.ConnectionConfig;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.plugin.world.WorldMirror;
import ac.thorium.mc.proto.Batch;
import ac.thorium.mc.proto.EntityMove;
import ac.thorium.mc.proto.Hello;
import ac.thorium.mc.proto.Sample;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@EnvTest
class EntityMovePollTest {
    @Test
    void entityMovesAreReadFromTheEntity(Env env) {
        Logger log = Logger.getLogger("test");
        ErrorGate gate = new ErrorGate(log, 60_000, System::currentTimeMillis);
        SampleBuffer buffer = new SampleBuffer(400);
        // Never started: only Telemetry's constructor needs it.
        EngineConnection conn = new EngineConnection(new ConnectionConfig("https://127.0.0.1:1", "", "t"), () -> "", Hello::getDefaultInstance, null, log);
        Telemetry telemetry = new Telemetry(conn, buffer, new WorldMirror(), new ServerCompat(), gate, PluginConfig.from(new Properties()), "t", false, log);
        EntityTracker tracker = new EntityTracker(12);
        OutboundCapture out = new OutboundCapture(telemetry, tracker, gate);

        Instance instance = env.createFlatInstance();
        Player viewer = env.createPlayer(instance, new Pos(0, 42, 0));
        Entity zombie = new Entity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(2, 42, 0)).join();
        tracker.viewerAt(viewer.getUuid(), 0, 42, 0);
        tracker.spawn(viewer.getUuid(), zombie.getEntityId(), "minecraft:zombie", true, 2, 42, 0, 0, 0);

        out.flushMoves(viewer, 0);
        assertEquals(List.of(2.0), xs(buffer), "entering range restates the entity once");

        zombie.teleport(new Pos(3, 42, 0)).join();
        out.flushMoves(viewer, 1);
        assertEquals(List.of(3.0), xs(buffer), "a move shows up without any packet event");

        out.flushMoves(viewer, 2);
        assertEquals(List.of(), xs(buffer), "a stationary entity sends nothing");
    }

    private static List<Double> xs(SampleBuffer buffer) {
        List<Double> out = new ArrayList<>();
        Batch b = buffer.drain(0, 0, 20, 0);
        if (b == null) return out;
        b.getPlayersList().forEach(pb -> {
            for (Sample s : pb.getSamplesList()) {
                if (s.hasOutbound() && s.getOutbound().hasEntityMove()) {
                    EntityMove m = s.getOutbound().getEntityMove();
                    out.add(m.getPosition().getX());
                }
            }
        });
        return out;
    }
}
