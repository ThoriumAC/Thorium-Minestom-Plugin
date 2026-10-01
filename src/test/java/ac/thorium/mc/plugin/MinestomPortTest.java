package ac.thorium.mc.plugin;

import ac.thorium.mc.plugin.capture.MetadataDecoder;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.world.SectionData;
import ac.thorium.mc.proto.EntityMetadata;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.Metadata;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;
import java.util.Properties;

import static ac.thorium.mc.plugin.capture.OutboundCapture.windowSlot;
import static ac.thorium.mc.plugin.world.WorldSampler.read;
import static org.junit.jupiter.api.Assertions.*;

class MinestomPortTest {
    @Test
    void playerInventorySlotsMapToWindowZero() {
        assertEquals(36, windowSlot(0));
        assertEquals(44, windowSlot(8));
        assertEquals(9, windowSlot(9));
        assertEquals(35, windowSlot(35));
        assertEquals(8, windowSlot(36)); // boots
        assertEquals(5, windowSlot(39)); // helmet
        assertEquals(45, windowSlot(40)); // offhand
    }

    @Test
    void metadataReadsFlagsAndSpinAttackPose() {
        EntityMetadata.Builder b = MetadataDecoder.decode(7, Map.of(0, Metadata.Byte((byte) (0x02 | 0x80)), 6, Metadata.Pose(EntityPose.SPIN_ATTACK)));
        assertNotNull(b);
        assertTrue(b.getSneaking());
        assertTrue(b.getGliding());
        assertFalse(b.getSprinting());
        assertEquals(4, b.getPose());
        assertTrue(b.getRiptiding());
        assertFalse(MetadataDecoder.decode(7, Map.of(6, Metadata.Pose(EntityPose.CROAKING))).getRiptiding());
        assertNull(MetadataDecoder.decode(7, Map.of(3, Metadata.Boolean(true))));
    }

    @Test
    void paletteReadsIntoSectionData() {
        Palette p = Palette.blocks();
        p.set(1, 2, 3, Block.STONE.stateId());
        SectionData d = read(p);
        assertEquals(Block.STONE.state(), d.blockAt(SectionData.offset(1, 2, 3)));
        assertEquals(Block.AIR.state(), d.blockAt(SectionData.offset(0, 0, 0)));
        assertEquals(0, read(Palette.blocks()).bitsPerIndex());
    }

    @Test
    void configParsesPropertiesAndClamps() throws Exception {
        Properties p = new Properties();
        p.load(new StringReader("server-token=org_x\nflush-interval-ms=5\ngateway-url=https://g.example/\nkick-format=a\\nb\n"));
        PluginConfig c = PluginConfig.from(p);
        assertTrue(c.needsServerName());
        assertEquals(25, c.flushIntervalMs);
        assertEquals("https://g.example", c.gatewayUrl);
        assertEquals("a\nb", c.kickFormat);
        assertTrue(c.enforce);
    }

    @Test
    void tpsFromTickTimestamps() {
        long[] ticks = new long[200];
        for (int i = 0; i < 100; i++) ticks[i] = i * 50_000_000L;
        assertEquals(20.0, ServerCompat.tpsFromTicks(ticks, 100, 99 * 50_000_000L), 0.01);
        for (int i = 0; i < 50; i++) ticks[i] = i * 100_000_000L;
        assertEquals(10.0, ServerCompat.tpsFromTicks(ticks, 50, 49 * 100_000_000L), 0.01);
    }
}
