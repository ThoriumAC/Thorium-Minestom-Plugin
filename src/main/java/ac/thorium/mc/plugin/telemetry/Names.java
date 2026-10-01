package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.PlayerRef;
import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class Names {
    private Names() {}

    public static ByteString bytes(UUID u) {
        return ByteString.copyFrom(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
    }

    public static PlayerRef ref(UUID u, String name, boolean cracked) {
        return PlayerRef.newBuilder().setUuid(bytes(u)).setUsername(name == null ? "" : name).setCracked(cracked).build();
    }

    public static UUID uuid(ByteString b) {
        if (b == null || b.size() != 16) return null;
        ByteBuffer bb = b.asReadOnlyByteBuffer();
        return new UUID(bb.getLong(), bb.getLong());
    }
}
