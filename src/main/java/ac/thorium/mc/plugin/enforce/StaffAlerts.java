package ac.thorium.mc.plugin.enforce;

import net.minestom.server.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class StaffAlerts {
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Predicate<Player> staff;

    public StaffAlerts(Predicate<Player> staff) { this.staff = staff; }
    public boolean isStaff(Player p) { return staff.test(p); }

    public boolean toggle(UUID id) { if (muted.remove(id)) return true; muted.add(id); return false; }
    public boolean enabled(UUID id) { return !muted.contains(id); }

    public List<Player> recipients(Collection<Player> online) {
        List<Player> out = new ArrayList<>();
        for (Player p : online) if (isStaff(p) && enabled(p.getUuid())) out.add(p);
        return out;
    }
}
