package ac.thorium.mc.plugin.world;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.proto.SectionPos;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.instance.InstanceBlockUpdateEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.event.instance.InstanceSectionInvalidateEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.server.world.DimensionType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Streams the blocks around each player from the Minestom instance. Sections are keyed by the
 * instance UUID: many instances share one dimension type, so a dimension cannot address a section.
 */
public final class WorldSampler {
    static final int RETARGET_EVERY_TICKS = 10;
    static final int SECTIONS_BELOW = 2;
    static final int SECTIONS_ABOVE = 1;
    static final int SECTIONS_PER_COLUMN = SECTIONS_BELOW + SECTIONS_ABOVE + 1;
    public static final String AIR = "minecraft:air";

    private final WorldMirror mirror;
    private final ErrorGate gate;
    private final Supplier<Collection<Player>> players;
    private final BooleanSupplier connected;
    private final Map<String, String> biomeByColumn = new HashMap<>();
    private Task tickTask;
    private long ticks;

    public WorldSampler(WorldMirror mirror, ErrorGate gate, Supplier<Collection<Player>> players, BooleanSupplier connected) {
        this.mirror = mirror; this.gate = gate; this.players = players; this.connected = connected;
    }

    public static String key(Instance in) { return in == null ? "" : in.getUuid().toString(); }

    public void start() {
        stop();
        tickTask = MinecraftServer.getSchedulerManager().buildTask(() -> gate.run("world:sample", this::tick)).repeat(TaskSchedule.tick(1)).schedule();
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        tickTask = null;
        biomeByColumn.clear();
    }

    public void register(EventNode<Event> node) {
        node.addListener(InstanceBlockUpdateEvent.class, e -> gate.run("world:block", () -> {
            if (!mirror.enabled()) return;
            Point b = e.getBlockPosition();
            mirror.recordChange(sectionOf(key(e.getInstance()), b.blockX(), b.blockY(), b.blockZ()),
                    offsetOf(b.blockX(), b.blockY(), b.blockZ()), e.getBlock().state());
        }));
        node.addListener(InstanceSectionInvalidateEvent.class, e -> gate.run("world:invalidate", () ->
                mirror.invalidate(SectionPos.newBuilder().setDimension(key(e.getInstance())).setX(e.sectionX()).setY(e.sectionY()).setZ(e.sectionZ()).build())));
        node.addListener(InstanceChunkUnloadEvent.class, e -> gate.run("world:unload", () -> {
            String dim = key(e.getInstance());
            DimensionType t = e.getInstance().getCachedDimensionType();
            for (int sy = t.minY() >> 4; sy <= (t.maxY() - 1) >> 4; sy++) {
                mirror.invalidate(SectionPos.newBuilder().setDimension(dim).setX(e.getChunkX()).setY(sy).setZ(e.getChunkZ()).build());
            }
        }));
    }

    private void tick() {
        if (!mirror.enabled() || !connected.getAsBoolean()) return;
        ticks++;
        if (ticks % RETARGET_EVERY_TICKS == 0) mirror.retarget(wantedSections(), occupiedColumns(), ticks);
        for (WorldMirror.ColumnPos c : mirror.nextColumns(mirror.columnsPerTick())) snapshot(c);
    }

    private List<WorldMirror.ColumnPos> occupiedColumns() {
        List<WorldMirror.ColumnPos> out = new ArrayList<>();
        for (Player p : players.get()) {
            if (p.getInstance() == null) continue;
            Point pos = p.getPosition();
            out.add(new WorldMirror.ColumnPos(key(p.getInstance()), pos.blockX() >> 4, pos.blockZ() >> 4));
        }
        return out;
    }

    private Set<SectionPos> wantedSections() {
        Set<SectionPos> out = new LinkedHashSet<>();
        int r = mirror.radiusChunks();
        List<Player> online = new ArrayList<>(players.get());
        for (int ring = 0; ring <= r; ring++) {
            for (Player p : online) {
                Instance in = p.getInstance();
                if (in == null) continue;
                String dim = key(in);
                DimensionType t = in.getCachedDimensionType();
                int minSy = t.minY() >> 4, maxSy = (t.maxY() - 1) >> 4;
                Point pos = p.getPosition();
                int cx = pos.blockX() >> 4, cz = pos.blockZ() >> 4, cy = pos.blockY() >> 4;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        for (int dy = -SECTIONS_BELOW; dy <= SECTIONS_ABOVE; dy++) {
                            int sy = cy + dy;
                            if (sy < minSy || sy > maxSy) continue;
                            out.add(SectionPos.newBuilder().setDimension(dim).setX(cx + dx).setY(sy).setZ(cz + dz).build());
                        }
                    }
                }
            }
        }
        return out;
    }

    private void snapshot(WorldMirror.ColumnPos c) {
        Instance in = instance(c.dimension());
        Chunk chunk = in == null ? null : in.getChunk(c.x(), c.z());
        if (chunk == null) return;
        List<SectionPos> want = mirror.wantedIn(c);
        if (want.isEmpty()) return;
        DimensionType t = in.getCachedDimensionType();
        mirror.bounds(c.dimension(), t.minY(), t.maxY());
        long token = mirror.snapshotToken();
        String columnKey = c.dimension() + ":" + c.x() + "," + c.z();
        long t0 = System.nanoTime();
        List<Palette> copies = new ArrayList<>(want.size());
        String biome;
        chunk.lockReadLock();
        try {
            for (SectionPos p : want) copies.add(chunk.getSectionAt(p.getY() << 4).blockPalette().clone());
            biome = biomeByColumn.computeIfAbsent(columnKey, k -> chunk.getBiome(8, 64, 8).key().asString());
        } finally {
            chunk.unlockReadLock();
        }
        mirror.recordSnapshot(System.nanoTime() - t0);
        for (int i = 0; i < want.size(); i++) mirror.acceptSection(want.get(i), read(copies.get(i)), token, biome);
    }

    public static SectionData read(Palette palette) {
        List<String> states = new ArrayList<>();
        Int2IntOpenHashMap index = new Int2IntOpenHashMap();
        index.defaultReturnValue(-1);
        int[] ids = new int[SectionData.BLOCKS];
        palette.getAll((x, y, z, v) -> {
            int i = index.get(v);
            if (i < 0) { i = states.size(); states.add(state(v)); index.put(v, i); }
            ids[SectionData.offset(x, y, z)] = i;
        });
        return SectionData.of(states, ids);
    }

    private static String state(int stateId) {
        Block b = Block.fromStateId(stateId);
        return b == null ? AIR : b.state();
    }

    private static Instance instance(String key) {
        try { return MinecraftServer.getInstanceManager().getInstance(UUID.fromString(key)); } catch (IllegalArgumentException e) { return null; }
    }

    public static SectionPos sectionOf(String dimension, int x, int y, int z) {
        return SectionPos.newBuilder().setDimension(dimension).setX(x >> 4).setY(y >> 4).setZ(z >> 4).build();
    }

    public static int offsetOf(int x, int y, int z) { return SectionData.offset(x & 15, y & 15, z & 15); }
}
