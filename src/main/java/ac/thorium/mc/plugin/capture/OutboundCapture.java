package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.telemetry.Names;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.proto.*;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OutboundCapture {
    private static final double DELTA_SCALE = 4096.0;

    private static final ClassValue<String> SITES = new ClassValue<>() {
        @Override protected String computeValue(Class<?> c) { return "out:" + c.getSimpleName(); }
    };

    private final Telemetry telemetry;
    private final EntityTracker entities;
    private final ErrorGate gate;
    private final Map<UUID, MoveCoalescer> moves = new ConcurrentHashMap<>();

    public OutboundCapture(Telemetry telemetry, EntityTracker entities, ErrorGate gate) {
        this.telemetry = telemetry; this.entities = entities; this.gate = gate;
    }

    public void onPacket(PlayerPacketOutEvent event) {
        ServerPacket packet = event.getPacket();
        try { dispatch(packet, event.getPlayer()); }
        catch (Throwable t) { gate.report(SITES.get(packet.getClass()), t); }
    }

    public void flushMoves(Player p, long tick) {
        pollMoves(p);
        flushEntered(p);
        MoveCoalescer c = moves.get(p.getUuid());
        if (c == null) return;
        c.drain(m -> telemetry.outbound(p, Outbound.newBuilder().setEntityMove(m)));
    }

    // Minestom batches entity movement to viewers into raw buffers that skip PlayerPacketOutEvent (unless the server runs
    // with -Dminestom.viewable-packet=false); everything else about an entity is sent per player and captured above.
    // This runs at tick start, after the previous tick's batch was queued, so the positions read here are the ones just
    // sent and the tick fence that follows lands behind them. When move packets do arrive, the coalescer keeps one per tick.
    private void pollMoves(Player p) {
        Instance in = p.getInstance();
        if (in == null) return;
        entities.each(p.getUuid(), k -> {
            if (!k.wanted) return;
            Entity e = in.getEntityById(k.id);
            if (e == null) return;
            Pos pos = e.getPosition();
            if (pos.x() == k.x && pos.y() == k.y && pos.z() == k.z && pos.yaw() == k.yaw && pos.pitch() == k.pitch) return;
            move(p, k.id, pos, true, pos.yaw(), pos.pitch(), e.isOnGround(), false);
        });
    }

    private void flushEntered(Player p) {
        entities.entered(p.getUuid(), k -> {
            telemetry.outbound(p, Outbound.newBuilder().setEntityMove(EntityMove.newBuilder().setId(k.id)
                    .setPosition(Vec3.newBuilder().setX(k.x).setY(k.y).setZ(k.z))
                    .setOnGround(k.onGround).setHasLook(true).setYaw(k.yaw).setPitch(k.pitch)));
            EntityMetadata f = k.flags;
            if (f != null) telemetry.outbound(p, Outbound.newBuilder().setEntityMetadata(f));
        });
    }

    public void restate(Player p) {
        long[] last = {-1};
        entities.restate(p.getUuid(), k -> last[0] = telemetry.outbound(p, Outbound.newBuilder().setEntitySpawn(EntitySpawn.newBuilder()
                .setId(k.id).setType(k.type).setPosition(Vec3.newBuilder().setX(k.x).setY(k.y).setZ(k.z)).setYaw(k.yaw).setPitch(k.pitch))));
        if (last[0] >= 0) telemetry.fence(p, last[0]);
    }

    public void forget(UUID id) { moves.remove(id); entities.forget(id); }

    // The ping joins the send queue behind the packet being written, so the client acks it after applying the packet.
    private void fenced(Player p, Outbound.Builder o) { telemetry.fence(p, telemetry.outbound(p, o)); }

    private void dispatch(ServerPacket packet, Player p) {
        UUID viewer = p.getUuid();
        int self = p.getEntityId();
        switch (packet) {
            case SpawnEntityPacket w -> {
                Point pos = w.position();
                EntityType et = w.type();
                String key = et.key().value();
                EntitySpawn.Builder b = EntitySpawn.newBuilder().setId(w.entityId()).setType(et.key().asString())
                        .setPosition(vec(pos)).setYaw(w.position().yaw()).setPitch(w.position().pitch()).setHeadYaw(w.headRot())
                        .setUuid(Names.bytes(w.uuid())).setVelocity(vec(w.velocity()));
                boolean wanted = EntityTracker.wanted(key, et.shouldSendAttributes() || key.endsWith("minecart"));
                entities.spawn(viewer, w.entityId(), b.getType(), wanted, pos.x(), pos.y(), pos.z(), w.position().yaw(), w.position().pitch());
                fenced(p, Outbound.newBuilder().setEntitySpawn(b));
            }
            case EntityTeleportPacket w -> move(p, w.entityId(), w.position(), true, w.position().yaw(), w.position().pitch(), w.onGround(), false);
            case EntityPositionSyncPacket w -> move(p, w.entityId(), w.position(), true, w.yaw(), w.pitch(), w.onGround(), false);
            case EntityPositionAndRotationPacket w -> move(p, w.entityId(), new net.minestom.server.coordinate.Vec(w.deltaX() / DELTA_SCALE, w.deltaY() / DELTA_SCALE, w.deltaZ() / DELTA_SCALE),
                    true, w.yaw(), w.pitch(), w.onGround(), true);
            case EntityPositionPacket w -> move(p, w.entityId(), new net.minestom.server.coordinate.Vec(w.deltaX() / DELTA_SCALE, w.deltaY() / DELTA_SCALE, w.deltaZ() / DELTA_SCALE),
                    false, 0, 0, w.onGround(), true);
            case EntityVelocityPacket w -> {
                if (w.entityId() != self && !entities.contains(viewer, w.entityId())) return;
                fenced(p, Outbound.newBuilder().setEntityVelocity(EntityVelocityOut.newBuilder().setId(w.entityId()).setVelocity(vec(w.velocity()))));
            }
            case DestroyEntitiesPacket w -> {
                int[] ids = w.entityIds().stream().mapToInt(Integer::intValue).toArray();
                entities.destroy(viewer, ids);
                if (ids.length > 0) fenced(p, Outbound.newBuilder().setEntityDestroy(EntityDestroy.newBuilder().addAllIds(w.entityIds())));
            }
            case EntityMetaDataPacket w -> {
                int id = w.entityId();
                EntityTracker.Known k = id == self ? null : entities.get(viewer, id);
                if (id != self && (k == null || !k.wanted)) return;
                EntityMetadata.Builder b = MetadataDecoder.decode(id, w.entries());
                if (b == null) return;
                if (k != null) k.flags = mergeMetadata(k.flags, b, id);
                if (id == self || entities.contains(viewer, id)) fenced(p, Outbound.newBuilder().setEntityMetadata(b));
            }
            case EntityAttributesPacket w -> {
                if (w.entityId() != self && !entities.contains(viewer, w.entityId())) return;
                EntityAttributes.Builder b = EntityAttributes.newBuilder().setId(w.entityId());
                for (EntityAttributesPacket.Property pr : w.properties()) {
                    Attribute.Builder a = Attribute.newBuilder().setName(pr.attribute().key().asString()).setBase(pr.value());
                    for (net.minestom.server.entity.attribute.AttributeModifier m : pr.modifiers()) {
                        a.addModifiers(AttributeModifier.newBuilder().setAmount(m.amount()).setOp(m.operation().ordinal()).setId(m.id().asString()));
                    }
                    b.addAttributes(a);
                }
                fenced(p, Outbound.newBuilder().setEntityAttributes(b));
            }
            case EntityEffectPacket w -> {
                if (w.entityId() != self) return;
                fenced(p, Outbound.newBuilder().setEntityEffect(EntityEffect.newBuilder().setId(self)
                        .setEffect(w.potion().effect().key().asString()).setAmplifier(w.potion().amplifier()).setDuration(w.potion().duration())));
            }
            case RemoveEntityEffectPacket w -> {
                if (w.entityId() != self) return;
                fenced(p, Outbound.newBuilder().setEntityEffect(EntityEffect.newBuilder().setId(self).setEffect(w.potionEffect().key().asString()).setRemove(true)));
            }
            case EntityEquipmentPacket w -> {
                if (w.entityId() != self && !entities.contains(viewer, w.entityId())) return;
                EntityEquipment.Builder b = EntityEquipment.newBuilder().setId(w.entityId());
                w.equipments().forEach((slot, it) -> b.addSlots(ItemNames.slot(slot, it)));
                fenced(p, Outbound.newBuilder().setEntityEquipment(b));
            }
            case SetPassengersPacket w -> {
                Passengers.Builder b = Passengers.newBuilder().setVehicle(w.vehicleEntityId()).addAllIds(w.passengersId());
                boolean mine = w.vehicleEntityId() == self || w.passengersId().contains(self);
                if (mine) { entities.pin(viewer, w.vehicleEntityId()); for (int id : w.passengersId()) entities.pin(viewer, id); }
                if (mine || entities.contains(viewer, w.vehicleEntityId())) fenced(p, Outbound.newBuilder().setPassengers(b));
            }
            case PlayerPositionAndLookPacket w -> {
                Point pos = w.position();
                telemetry.outbound(p, Outbound.newBuilder().setPlayerPosition(PlayerPosition.newBuilder().setTeleportId(w.teleportId())
                        .setPosition(vec(pos)).setLook(Look.newBuilder().setYaw(w.yaw()).setPitch(w.pitch())).setRelativeFlags(w.flags())));
                telemetry.teleportSent(p, w.teleportId(), pos.x(), pos.y(), pos.z());
            }
            case PlayerAbilitiesPacket w -> fenced(p, Outbound.newBuilder().setPlayerAbilities(PlayerAbilities.newBuilder()
                    .setInvulnerable((w.flags() & 0x01) != 0).setFlying((w.flags() & 0x02) != 0).setMayFly((w.flags() & 0x04) != 0)
                    .setFlySpeed(w.flyingSpeed()).setWalkSpeed(w.walkingSpeed())));
            case ChangeGameStatePacket w -> fenced(p, Outbound.newBuilder().setGameState(GameState.newBuilder().setReason(w.reason().ordinal()).setValue(w.value())));
            case SetTickStatePacket w -> fenced(p, Outbound.newBuilder().setTickingState(TickingState.newBuilder().setTickRate(w.tickRate()).setFrozen(w.isFrozen())));
            case RespawnPacket w -> fenced(p, Outbound.newBuilder().setRespawn(RespawnOut.newBuilder()
                    .setDimension(dimensionName(w.playerSpawnInfo().dimensionType()))
                    .setGamemode(w.playerSpawnInfo().gameMode().ordinal()).setKeepAll((w.copyData() & 0x03) == 0x03)
                    .setKeepAttributes((w.copyData() & 0x01) != 0)));
            case SetSlotPacket w -> fenced(p, Outbound.newBuilder().setSlot(ItemNames.slotOut(w.slot(), w.itemStack()).setWindow(w.windowId()).setStateId(w.stateId())));
            case SetPlayerInventorySlotPacket w -> fenced(p, Outbound.newBuilder().setSlot(ItemNames.slotOut(windowSlot(w.slot()), w.itemStack()).setWindow(0)));
            case WindowItemsPacket w -> {
                WindowItems.Builder b = WindowItems.newBuilder().setWindow(w.windowId()).setStateId(w.stateId());
                List<ItemStack> items = w.items();
                for (int i = 0; i < items.size(); i++) {
                    ItemStack it = items.get(i);
                    if (it != null && !it.isAir()) b.addSlots(ItemNames.slotOut(i, it));
                }
                fenced(p, Outbound.newBuilder().setWindowItems(b));
            }
            case OpenWindowPacket w -> fenced(p, Outbound.newBuilder().setOpenWindow(OpenWindow.newBuilder().setWindow(w.windowId()).setType(String.valueOf(w.windowType()))));
            case OpenHorseWindowPacket w -> fenced(p, Outbound.newBuilder().setOpenWindow(OpenWindow.newBuilder().setWindow(w.windowId()).setType("minecraft:horse")));
            case CloseWindowPacket w -> fenced(p, Outbound.newBuilder().setCloseWindow(CloseWindowOut.newBuilder().setWindow(w.windowId())));
            case HeldItemChangePacket w -> fenced(p, Outbound.newBuilder().setHeldSlot(HeldSlotOut.newBuilder().setSlot(w.slot())));
            case SetCooldownPacket w -> fenced(p, Outbound.newBuilder().setCooldown(Cooldown.newBuilder().setItem(w.cooldownGroup()).setTicks(w.cooldownTicks())));
            case ExplosionPacket w -> {
                ExplosionOut.Builder b = ExplosionOut.newBuilder().setPosition(vec(w.center())).setStrength(w.radius());
                Point k = w.playerKnockback();
                if (k != null && (k.x() != 0 || k.y() != 0 || k.z() != 0)) b.setMotion(vec(k));
                fenced(p, Outbound.newBuilder().setExplosion(b));
            }
            case UpdateHealthPacket w -> fenced(p, Outbound.newBuilder().setHealth(Health.newBuilder().setHealth(w.health()).setFood(w.food()).setSaturation(w.foodSaturation())));
            case KeepAlivePacket w -> telemetry.outbound(p, Outbound.newBuilder().setKeepAlive(KeepAliveOut.newBuilder().setId(w.id())));
            case BlockChangePacket w -> {
                Point bp = w.blockPosition();
                fenced(p, Outbound.newBuilder().setBlockChange(BlockChangeOut.newBuilder()
                        .setX(bp.blockX()).setY(bp.blockY()).setZ(bp.blockZ()).setState(state(w.blockStateId()))));
                telemetry.blockChangeSent(p, bp.blockX(), bp.blockY(), bp.blockZ());
            }
            case MultiBlockChangePacket w -> {
                long sp = w.chunkSectionPosition();
                int sx = (int) (sp >> 42), sy = (int) (sp << 44 >> 44), sz = (int) (sp << 22 >> 42);
                telemetry.blockChangeSent(p, sx << 4, sy << 4, sz << 4);
                MultiBlockChangeOut.Builder m = MultiBlockChangeOut.newBuilder();
                for (long e : w.blocks()) {
                    int x = (sx << 4) + (int) ((e >>> 8) & 0xF), y = (sy << 4) + (int) (e & 0xF), z = (sz << 4) + (int) ((e >>> 4) & 0xF);
                    if (!entities.nearViewer(viewer, x, y, z, 16)) continue;
                    m.addBlocks(BlockChangeOut.newBuilder().setX(x).setY(y).setZ(z).setState(state((int) (e >>> 12))));
                }
                if (m.getBlocksCount() > 0) fenced(p, Outbound.newBuilder().setMultiBlockChange(m));
            }
            case InitializeWorldBorderPacket w -> fenced(p, Outbound.newBuilder().setWorldBorder(WorldBorder.newBuilder()
                    .setCx(w.x()).setCz(w.z()).setSize(w.oldDiameter()).setNewSize(w.newDiameter()).setLerpMs(w.speed())));
            case WorldBorderCenterPacket w -> fenced(p, Outbound.newBuilder().setWorldBorder(WorldBorder.newBuilder().setCx(w.x()).setCz(w.z())));
            case WorldBorderSizePacket w -> fenced(p, Outbound.newBuilder().setWorldBorder(WorldBorder.newBuilder().setSize(w.diameter()).setNewSize(w.diameter())));
            case WorldBorderLerpSizePacket w -> fenced(p, Outbound.newBuilder().setWorldBorder(WorldBorder.newBuilder()
                    .setSize(w.oldDiameter()).setNewSize(w.newDiameter()).setLerpMs(w.speed())));
            default -> { }
        }
    }

    private void move(Player p, int id, Point to, boolean look, float yaw, float pitch, boolean ground, boolean relative) {
        UUID viewer = p.getUuid();
        EntityTracker.Known k = entities.get(viewer, id);
        if (k == null) return;
        double x = relative ? k.x + to.x() : to.x(), y = relative ? k.y + to.y() : to.y(), z = relative ? k.z + to.z() : to.z();
        k.moveTo(x, y, z); k.onGround = ground;
        if (look) { k.yaw = yaw; k.pitch = pitch; }
        if (!entities.contains(viewer, id)) return;
        moves.computeIfAbsent(viewer, u -> new MoveCoalescer()).offer(id, x, y, z, look, yaw, pitch, ground);
    }

    /** Player-inventory index (0-8 hotbar, 9-35 main, 36-39 boots..helmet, 40 offhand) to its window-0 slot. */
    public static int windowSlot(int slot) {
        if (slot < 9) return 36 + slot;
        if (slot < 36) return slot;
        if (slot < 40) return 8 - (slot - 36);
        return 45;
    }

    private static String state(int stateId) {
        Block b = Block.fromStateId(stateId);
        return b == null ? "minecraft:air" : b.state();
    }

    private static String dimensionName(int id) {
        RegistryKey<DimensionType> k = MinecraftServer.getDimensionTypeRegistry().getKey(id);
        return k == null ? "" : k.key().value();
    }

    private static Vec3 vec(Point v) { return Vec3.newBuilder().setX(v.x()).setY(v.y()).setZ(v.z()).build(); }

    /** Keeps every part {@code b} carries on top of what was already cached, so a part received while the entity is out of range is not lost. */
    private static EntityMetadata mergeMetadata(EntityMetadata prev, EntityMetadata.Builder b, int id) {
        EntityMetadata.Builder m = prev != null ? prev.toBuilder() : EntityMetadata.newBuilder().setId(id);
        if (b.getHasFlags()) m.setHasFlags(true).setSneaking(b.getSneaking()).setSprinting(b.getSprinting())
                .setSwimming(b.getSwimming()).setInvisible(b.getInvisible()).setGliding(b.getGliding());
        if (b.getHasPose()) m.setHasPose(true).setPose(b.getPose()).setRiptiding(b.getRiptiding());
        if (b.getHasSize()) m.setHasSize(true).setSize(b.getSize());
        if (b.getHasBaby()) m.setHasBaby(true).setBaby(b.getBaby());
        if (b.getHasPeek()) m.setHasPeek(true).setPeek(b.getPeek());
        return m.build();
    }
}
