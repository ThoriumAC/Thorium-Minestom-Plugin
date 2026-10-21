package ac.thorium.mc.plugin.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThoriumCommandTest {
    @Test
    void subcommandsFollowPermissions() {
        assertEquals(List.of("capture", "compat"), ThoriumCommand.complete("c", true, false));
        assertEquals(List.of("alerts"), ThoriumCommand.complete("", false, true));
        assertTrue(ThoriumCommand.complete("se", true, false).contains("setup"));
        assertFalse(ThoriumCommand.complete("se", false, true).contains("setup"));
    }
}
