package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.ItemPatch;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.UseEffects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemNamesTest {
    @BeforeAll
    static void registries() {
        MinecraftServer.init();
    }

    @Test
    void patchIsWhatDiffersFromThePrototype() {
        assertNull(ItemNames.patch(ItemStack.of(Material.ELYTRA)));
        assertNull(ItemNames.patch(ItemStack.of(Material.BREAD)));
        assertEquals(1, ItemNames.patch(ItemStack.of(Material.IRON_CHESTPLATE).with(DataComponents.GLIDER)).getGlider());
        assertEquals(2, ItemNames.patch(ItemStack.of(Material.ELYTRA).without(DataComponents.GLIDER)).getGlider());
        assertEquals(2, ItemNames.patch(ItemStack.of(Material.BREAD).without(DataComponents.CONSUMABLE)).getConsumable());
        ItemPatch u = ItemNames.patch(ItemStack.of(Material.BREAD).with(DataComponents.USE_EFFECTS, new UseEffects(true, false, 1f)));
        assertEquals(1, u.getUseEffects());
        assertEquals(1f, u.getUseSpeed());
        ItemPatch t = ItemNames.patch(ItemStack.of(Material.STICK).with(DataComponents.TOOL, ItemStack.of(Material.IRON_PICKAXE).get(DataComponents.TOOL)));
        assertEquals(1, t.getTool());
        assertTrue(t.getToolRulesList().stream().anyMatch(r -> r.getTag().equals("minecraft:mineable/pickaxe")), t.toString());

        assertNull(ItemNames.patch(ItemStack.of(Material.IRON_SPEAR)));
        ItemPatch spear = ItemNames.patch(ItemStack.of(Material.STICK)
                .with(DataComponents.ATTACK_RANGE, ItemStack.of(Material.IRON_SPEAR).get(DataComponents.ATTACK_RANGE))
                .with(DataComponents.PIERCING_WEAPON, ItemStack.of(Material.IRON_SPEAR).get(DataComponents.PIERCING_WEAPON)));
        assertEquals(1, spear.getAttackRange());
        assertEquals(4.5f, spear.getAttackMaxReach());
        assertEquals(1, spear.getPiercingWeapon());
        assertEquals(2, ItemNames.patch(ItemStack.of(Material.SHIELD).without(DataComponents.BLOCKS_ATTACKS)).getBlocksAttacks());
    }

    @Test
    void serverTagsCarryTheToolTags() {
        ac.thorium.mc.proto.BlockTags tags = ServerTags.blocks();
        assertNotNull(tags);
        assertTrue(tags.getTagsList().stream().anyMatch(t -> t.getName().equals("minecraft:mineable/pickaxe") && t.getBlocksList().contains("minecraft:stone")));
        assertTrue(tags.getTagsList().stream().anyMatch(t -> t.getName().equals("minecraft:incorrect_for_copper_tool")));
    }
}
