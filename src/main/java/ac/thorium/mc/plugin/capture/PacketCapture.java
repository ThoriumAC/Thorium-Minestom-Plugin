package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.proto.BlockAction;
import ac.thorium.mc.proto.CombatInteract;
import ac.thorium.mc.proto.EntityActionKind;
import ac.thorium.mc.proto.TextKind;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.*;
import net.minestom.server.network.packet.client.play.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketCapture {
    private static final int MAX_PAYLOAD = 32767;

    private static final class Flags { volatile boolean hasPos; volatile double x, y, z; volatile float yaw, pitch; }

    private static final ClassValue<String> SITES = new ClassValue<>() {
        @Override protected String computeValue(Class<?> c) { return "capture:" + c.getSimpleName(); }
    };

    private final Telemetry telemetry;
    private final EntityTracker entities;
    private final ErrorGate gate;
    private final Map<UUID, Flags> flags = new ConcurrentHashMap<>();

    public PacketCapture(Telemetry telemetry, ErrorGate gate, EntityTracker entities) {
        this.telemetry = telemetry; this.gate = gate; this.entities = entities;
    }

    public void forget(UUID uuid) { flags.remove(uuid); }

    private Flags flags(Player p) { return flags.computeIfAbsent(p.getUuid(), k -> new Flags()); }

    public void onPacket(PlayerPacketEvent event) {
        ClientPacket packet = event.getPacket();
        try { dispatch(packet, event.getPlayer(), event.isCancelled()); }
        catch (Throwable t) { gate.report(SITES.get(packet.getClass()), t); }
    }

    private void dispatch(ClientPacket packet, Player p, boolean cancelled) {
        Flags f = flags(p);
        switch (packet) {
            case ClientPlayerPositionPacket w -> onFlying(p, w.position(), true, 0, 0, false, w.onGround(), w.horizontalCollision(), cancelled);
            case ClientPlayerPositionAndRotationPacket w -> onFlying(p, w.position(), true, w.position().yaw(), w.position().pitch(), true,
                    (w.flags() & ClientPlayerPositionPacket.FLAG_ON_GROUND) != 0, (w.flags() & ClientPlayerPositionPacket.FLAG_HORIZONTAL_COLLISION) != 0, cancelled);
            case ClientPlayerRotationPacket w -> onFlying(p, null, false, w.yaw(), w.pitch(), true,
                    (w.flags() & ClientPlayerPositionPacket.FLAG_ON_GROUND) != 0, false, cancelled);
            case ClientPlayerPositionStatusPacket w -> onFlying(p, null, false, 0, 0, false, w.onGround(), w.horizontalCollision(), cancelled);
            case ClientVehicleMovePacket w -> {
                Pos v = w.position();
                f.x = v.x(); f.y = v.y(); f.z = v.z(); f.yaw = v.yaw(); f.pitch = v.pitch(); f.hasPos = true;
                entities.viewerAt(p.getUuid(), f.x, f.y, f.z);
                telemetry.sample(p, SampleFactory.vehicleMove(v.x(), v.y(), v.z(), v.yaw(), v.pitch(), true, w.onGround()));
            }
            case ClientSteerBoatPacket w -> telemetry.sample(p, SampleFactory.paddle(w.leftPaddleTurning(), w.rightPaddleTurning()));
            case ClientInputPacket w -> telemetry.sample(p, SampleFactory.input(w.forward(), w.backward(), w.left(), w.right(), w.jump(), w.shift(), w.sprint()));
            case ClientTickEndPacket w -> telemetry.sample(p, SampleFactory.tickEnd());
            case ClientEntityActionPacket w -> telemetry.sample(p, SampleFactory.entityAction(entityActionKind(w.action()), w.horseJumpBoost()));
            case ClientPlayerAbilitiesPacket w -> telemetry.sample(p, SampleFactory.abilities((w.flags() & 0x02) != 0));
            case ClientKeepAlivePacket w -> telemetry.sample(p, SampleFactory.keepAlive(w.id()));
            case ClientUseItemPacket w -> telemetry.sample(p, SampleFactory.useItem(w.hand().ordinal(), w.sequence(), true, w.yaw(), w.pitch()));
            case ClientHeldItemChangePacket w -> telemetry.sample(p, SampleFactory.heldSlot(w.slot()));
            case ClientInteractEntityPacket w -> telemetry.sample(p, switch (w.type()) {
                case ClientInteractEntityPacket.Attack a -> SampleFactory.combat(CombatInteract.COMBAT_INTERACT_ATTACK, w.targetId(), telemetry.refFor(w.targetId()),
                        f.x, f.y, f.z, f.yaw, f.pitch, 0, 0, 0, 0, false, w.sneaking());
                case ClientInteractEntityPacket.InteractAt a -> SampleFactory.combat(CombatInteract.COMBAT_INTERACT_INTERACT_AT, w.targetId(), telemetry.refFor(w.targetId()),
                        f.x, f.y, f.z, f.yaw, f.pitch, a.targetX(), a.targetY(), a.targetZ(), a.hand().ordinal(), true, w.sneaking());
                case ClientInteractEntityPacket.Interact a -> SampleFactory.combat(CombatInteract.COMBAT_INTERACT_INTERACT, w.targetId(), telemetry.refFor(w.targetId()),
                        f.x, f.y, f.z, f.yaw, f.pitch, 0, 0, 0, a.hand().ordinal(), false, w.sneaking());
                default -> SampleFactory.other(packetId(packet), packet.getClass().getSimpleName());
            });
            case ClientAnimationPacket w -> telemetry.sample(p, SampleFactory.swing(w.hand().ordinal()));
            case ClientPlayerActionPacket w -> {
                Point bp = w.blockPosition();
                telemetry.sample(p, SampleFactory.block(diggingAction(w.status()), bp.blockX(), bp.blockY(), bp.blockZ(), w.blockFace().ordinal(),
                        f.yaw, f.pitch, f.x, f.y, f.z, w.sequence(), 0, 0, 0, 0, false, false));
            }
            case ClientPlayerBlockPlacementPacket w -> {
                Point bp = w.blockPosition();
                telemetry.sample(p, SampleFactory.block(BlockAction.BLOCK_ACTION_PLACE, bp.blockX(), bp.blockY(), bp.blockZ(), w.blockFace().ordinal(), f.yaw, f.pitch, f.x, f.y, f.z,
                        w.sequence(), w.hand().ordinal(), w.cursorPositionX(), w.cursorPositionY(), w.cursorPositionZ(), w.insideBlock(), w.hitWorldBorder()));
            }
            case ClientClickWindowPacket w -> telemetry.sample(p, SampleFactory.inventory(w.windowId(), w.slot(), w.button(), SampleFactory.windowAction(w.clickType().name()),
                    w.clickType().ordinal(), w.stateId(), w.changedSlots().size(), !ItemStack.Hash.AIR.equals(w.clickedItem())));
            case ClientCloseWindowPacket w -> telemetry.sample(p, SampleFactory.inventory(w.windowId(), -1, 0, "CLOSE", 0, 0, 0, false));
            case ClientCreativeInventoryActionPacket w -> telemetry.sample(p, SampleFactory.inventory(0, w.slot(), 0, "CREATIVE_SET", 0, 0, 1, false));
            case ClientStatusPacket w -> telemetry.sample(p, SampleFactory.clientStatus(w.action().ordinal()));
            case ClientSettingsPacket w -> telemetry.sample(p, SampleFactory.settings(w.settings().viewDistance(), w.settings().mainHand().ordinal(), w.settings().displayedSkinParts()));
            case ClientSpectatePacket w -> telemetry.sample(p, SampleFactory.spectate(w.target()));
            case ClientChatMessagePacket w -> telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_CHAT, textLen(w.message()), 0, false));
            case ClientCommandChatPacket w -> telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_COMMAND, textLen(w.message()), 0, false));
            case ClientSignedCommandChatPacket w -> telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_COMMAND, textLen(w.message()), 0, true));
            case ClientTabCompletePacket w -> telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_TAB_COMPLETE, textLen(w.text()), 0, false));
            case ClientEditBookPacket w -> {
                int max = 0;
                for (String pg : w.pages()) max = Math.max(max, textLen(pg));
                telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_EDIT_BOOK, max, w.pages().size(), false));
            }
            case ClientNameItemPacket w -> telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_NAME_ITEM, textLen(w.itemName()), 0, false));
            case ClientPluginMessagePacket w -> onPluginMessage(w, p);
            case ClientPongPacket w -> telemetry.transactionAck(p, w.id());
            case ClientTeleportConfirmPacket w -> telemetry.teleportConfirmed(p, w.teleportId());
            default -> telemetry.sample(p, SampleFactory.other(packetId(packet), packet.getClass().getSimpleName()));
        }
    }

    private void onFlying(Player p, Point pos, boolean hasPos, float yaw, float pitch, boolean rot, boolean onGround, boolean collision, boolean cancelled) {
        Flags f = flags(p);
        if (hasPos) { f.x = pos.x(); f.y = pos.y(); f.z = pos.z(); f.hasPos = true; entities.viewerAt(p.getUuid(), f.x, f.y, f.z); }
        if (rot) { f.yaw = yaw; f.pitch = pitch; }
        if (!hasPos && rot) { telemetry.sample(p, SampleFactory.rotation(yaw, pitch, onGround)); return; }
        double x = hasPos ? pos.x() : 0, y = hasPos ? pos.y() : 0, z = hasPos ? pos.z() : 0;
        int teleportId = hasPos ? telemetry.confirmsTeleport(p, x, y, z) : 0;
        telemetry.sample(p, SampleFactory.movement(x, y, z, yaw, pitch, hasPos, rot, onGround, collision, teleportId, cancelled));
    }

    private static int packetId(ClientPacket packet) {
        try { return PacketVanilla.CLIENT_PACKET_PARSER.play().packetInfo(packet.getClass()).id(); } catch (Throwable t) { return -1; }
    }

    private static int textLen(String s) { return s == null ? 0 : s.length(); }

    private static BlockAction diggingAction(ClientPlayerActionPacket.Status s) {
        return switch (s) {
            case STARTED_DIGGING -> BlockAction.BLOCK_ACTION_DIG_START;
            case CANCELLED_DIGGING -> BlockAction.BLOCK_ACTION_DIG_ABORT;
            case FINISHED_DIGGING -> BlockAction.BLOCK_ACTION_DIG_FINISH;
            case DROP_ITEM -> BlockAction.BLOCK_ACTION_DROP_ITEM;
            case DROP_ITEM_STACK -> BlockAction.BLOCK_ACTION_DROP_STACK;
            case UPDATE_ITEM_STATE -> BlockAction.BLOCK_ACTION_RELEASE_USE_ITEM;
            case SWAP_ITEM_HAND -> BlockAction.BLOCK_ACTION_SWAP_HANDS;
            default -> BlockAction.BLOCK_ACTION_UNSPECIFIED;
        };
    }

    private static EntityActionKind entityActionKind(ClientEntityActionPacket.Action a) {
        return switch (a) {
            case LEAVE_BED -> EntityActionKind.ENTITY_ACTION_LEAVE_BED;
            case START_SPRINTING -> EntityActionKind.ENTITY_ACTION_START_SPRINT;
            case STOP_SPRINTING -> EntityActionKind.ENTITY_ACTION_STOP_SPRINT;
            case START_JUMP_HORSE -> EntityActionKind.ENTITY_ACTION_START_JUMP_HORSE;
            case STOP_JUMP_HORSE -> EntityActionKind.ENTITY_ACTION_STOP_JUMP_HORSE;
            case OPEN_HORSE_INVENTORY -> EntityActionKind.ENTITY_ACTION_OPEN_HORSE_INVENTORY;
            case START_FLYING_ELYTRA -> EntityActionKind.ENTITY_ACTION_START_ELYTRA;
        };
    }

    private void onPluginMessage(ClientPluginMessagePacket w, Player p) {
        String ch = w.channel();
        int protocol = ServerCompat.protocol(p);
        if (SampleFactory.isBrandChannel(ch)) telemetry.sample(p, SampleFactory.client(SampleFactory.decodeBrand(w.data()), null, protocol));
        else if (SampleFactory.isRegisterChannel(ch)) telemetry.sample(p, SampleFactory.client("", SampleFactory.decodeRegister(w.data()), protocol));
        else {
            byte[] data = w.data();
            if (data != null && data.length > MAX_PAYLOAD) telemetry.sample(p, SampleFactory.payload(data.length));
        }
    }
}
