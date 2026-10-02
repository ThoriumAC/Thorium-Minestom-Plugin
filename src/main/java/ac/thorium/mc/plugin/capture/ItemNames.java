package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EquipmentSlot;
import ac.thorium.mc.proto.ItemPatch;
import ac.thorium.mc.proto.SlotOut;
import ac.thorium.mc.proto.ToolRule;
import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponentMap;
import net.minestom.server.component.DataComponents;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.AttackRange;
import net.minestom.server.item.component.Consumable;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.component.Tool;
import net.minestom.server.item.component.UseEffects;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;

import java.util.Objects;

public final class ItemNames {
    private ItemNames() {}

    public static String of(ItemStack item) {
        if (item == null || item.isAir()) return "";
        return item.material().key().asString();
    }

    public static EquipmentSlot.Builder slot(net.minestom.server.entity.EquipmentSlot slot, ItemStack it) {
        EquipmentSlot.Builder b = EquipmentSlot.newBuilder().setSlot(slot.protocolId());
        if (it == null || it.isAir()) return b;
        EnchantmentList e = it.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY);
        return b.setItem(of(it)).setCount(it.amount()).setEfficiency(e.level(Enchantment.EFFICIENCY)).setAquaAffinity(e.level(Enchantment.AQUA_AFFINITY) > 0)
                .setDepthStrider(e.level(Enchantment.DEPTH_STRIDER)).setSoulSpeed(e.level(Enchantment.SOUL_SPEED));
    }

    public static SlotOut.Builder slotOut(int slot, ItemStack it) {
        SlotOut.Builder b = SlotOut.newBuilder().setSlot(slot).setEnchantsKnown(true);
        if (it == null || it.isAir()) return b;
        EnchantmentList e = it.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY);
        b.setItem(of(it)).setCount(it.amount()).setEfficiency(e.level(Enchantment.EFFICIENCY)).setAquaAffinity(e.level(Enchantment.AQUA_AFFINITY) > 0)
                .setDepthStrider(e.level(Enchantment.DEPTH_STRIDER)).setSoulSpeed(e.level(Enchantment.SOUL_SPEED));
        ItemPatch patch = patch(it);
        return patch == null ? b : b.setPatch(patch);
    }

    // The stack's component patch as it goes to the client (what differs from
    // the material's prototype), for the components that change movement or
    // mining; null when it touches none of them.
    static ItemPatch patch(ItemStack it) {
        try {
            return patch0(it);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ItemPatch patch0(ItemStack it) {
        DataComponentMap proto = it.material().prototype();
        ItemPatch.Builder b = ItemPatch.newBuilder();
        int s;
        if ((s = state(it, proto, DataComponents.GLIDER)) != 0) b.setGlider(s);
        if ((s = state(it, proto, DataComponents.CONSUMABLE)) != 0) {
            b.setConsumable(s);
            Consumable c = it.get(DataComponents.CONSUMABLE);
            if (c != null) b.setConsumeSeconds(c.consumeSeconds());
        }
        if ((s = state(it, proto, DataComponents.USE_EFFECTS)) != 0) {
            b.setUseEffects(s);
            UseEffects u = it.get(DataComponents.USE_EFFECTS);
            if (u != null) b.setUseSpeed(u.speedMultiplier());
        }
        if ((s = state(it, proto, DataComponents.TOOL)) != 0) {
            b.setTool(s);
            Tool t = it.get(DataComponents.TOOL);
            if (t != null) {
                b.setToolDefaultSpeed(t.defaultMiningSpeed());
                for (Tool.Rule r : t.rules()) {
                    ToolRule.Builder rb = ToolRule.newBuilder();
                    if (r.blocks().key() != null) rb.setTag(r.blocks().key().key().asString());
                    else for (RegistryKey<Block> k : r.blocks()) rb.addBlocks(k.key().asString());
                    if (r.speed() != null) rb.setHasSpeed(true).setSpeed(r.speed());
                    if (r.correctForDrops() != null) rb.setCorrect(r.correctForDrops() ? 1 : 2);
                    b.addToolRules(rb);
                }
            }
        }
        if ((s = state(it, proto, DataComponents.ATTACK_RANGE)) != 0) {
            b.setAttackRange(s);
            AttackRange a = it.get(DataComponents.ATTACK_RANGE);
            if (a != null) b.setAttackMaxReach(a.maxReach()).setAttackMaxCreativeReach(a.maxCreativeReach()).setAttackHitboxMargin(a.hitboxMargin());
        }
        if ((s = state(it, proto, DataComponents.PIERCING_WEAPON)) != 0) b.setPiercingWeapon(s);
        if ((s = state(it, proto, DataComponents.BLOCKS_ATTACKS)) != 0) b.setBlocksAttacks(s);
        ItemPatch out = b.build();
        return out.equals(ItemPatch.getDefaultInstance()) ? null : out;
    }

    // 0 as the prototype has it, 1 set to something else, 2 removed.
    private static int state(ItemStack it, DataComponentMap proto, DataComponent<?> c) {
        Object v = it.get(c);
        if (Objects.equals(v, proto.get(c))) return 0;
        return v == null ? 2 : 1;
    }
}
