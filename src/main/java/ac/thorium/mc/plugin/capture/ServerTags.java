package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.BlockTag;
import ac.thorium.mc.proto.BlockTags;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.registry.RegistryTag;

// The block tags Minestom sends every client, as the server has them.
public final class ServerTags {
    private ServerTags() {}

    public static BlockTags blocks() {
        try {
            BlockTags.Builder b = BlockTags.newBuilder();
            for (RegistryTag<Block> tag : Block.staticRegistry().tags()) {
                if (tag.key() == null) continue;
                BlockTag.Builder t = BlockTag.newBuilder().setName(tag.key().key().asString());
                for (RegistryKey<Block> k : tag) t.addBlocks(k.key().asString());
                b.addTags(t);
            }
            return b.build();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
