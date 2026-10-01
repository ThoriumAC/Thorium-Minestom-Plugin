package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EquipmentSlot;
import ac.thorium.mc.proto.SlotOut;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;

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
        return b.setItem(of(it)).setCount(it.amount()).setEfficiency(e.level(Enchantment.EFFICIENCY)).setAquaAffinity(e.level(Enchantment.AQUA_AFFINITY) > 0)
                .setDepthStrider(e.level(Enchantment.DEPTH_STRIDER)).setSoulSpeed(e.level(Enchantment.SOUL_SPEED));
    }
}
