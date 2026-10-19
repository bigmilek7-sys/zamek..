package pl.zamek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZamekPlugin extends JavaPlugin implements Listener {

    // ====== USTAWIENIA - zmien tutaj, jesli maja byc inne ======
    private static final int INNER_W = 6;                    // szerokosc/dlugosc w srodku
    private static final int INNER_H = 5;                    // wysokosc w srodku
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;  // cooldown: 3 minuty
    private static final int DISTANCE = 2;                   // ile blokow przed graczem zaczyna sie zamek
    private static final int MAX_FOUNDATION = 10;            // jak gleboko dobudowac fundament na nierownym terenie
    private static final Material WALL = Material.BLACKSTONE;
    // ============================================================

    private static final int OUTER_W = INNER_W + 2;          // razem ze scianami (8)
    private static final int OUTER_H = INNER_H + 2;          // razem z podloga i sufitem (7)

    private NamespacedKey key;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "zamek");
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ---------- komenda /zamek [gracz] ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie ma takiego gracza online.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Uzycie: /zamek <gracz>", NamedTextColor.RED));
            return true;
        }
        target.getInventory().addItem(createItem()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Dano przedmiot Zamek graczowi " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.DEEPSLATE_BRICKS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Zamek", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Kliknij prawym przyciskiem,", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("aby postawic zamek.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ---------- klikniecie przedmiotem (w blok albo w powietrze) ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("zamek.use")) {
            p.sendMessage(Component.text("Nie masz uprawnien.", NamedTextColor.RED));
            return;
        }

        // cooldown (dziala na wszystkich, takze na OP)
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            long left = (COOLDOWN_MS - (now - last) + 999) / 1000;
            p.sendMessage(Component.text("Zamek mozesz postawic za " + (left / 60) + "m " + (left % 60) + "s.", NamedTextColor.RED));
            return;
        }

        // szukamy ziemi pod graczem (dziala tez gdy gracz leci)
        World w = p.getWorld();
        Block ground = findGround(p);
        if (ground == null) {
            p.sendMessage(Component.text("Nie ma ziemi pod toba, nie da sie postawic zamku.", NamedTextColor.RED));
            return;
        }

        int floorY = ground.getY() + 1;
        if (floorY + OUTER_H >= w.getMaxHeight()) {
            p.sendMessage(Component.text("Za wysoko, zamek sie tu nie zmiesci.", NamedTextColor.RED));
            return;
        }

        build(p, ground.getX(), floorY, ground.getZ());
        cooldowns.put(p.getUniqueId(), now);

        if (p.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        p.sendMessage(Component.text("Postawiles zamek!", NamedTextColor.GRAY));
    }

    /** Pierwszy twardy blok pod graczem (pomija powietrze, wode, trawe, liscie itp.). */
    private Block findGround(Player p) {
        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int z = p.getLocation().getBlockZ();
        for (int y = p.getLocation().getBlockY(); y >= w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.isPassable() || b.isLiquid()) continue;
            if (Tag.LEAVES.isTagged(b.getType())) continue;
            return b;
        }
        return null;
    }

    /** Kierunek "do przodu" gracza jako {dx, dz} (tylko N/E/S/W). */
    private int[] forward(Player p) {
        int dir = Math.round(p.getLocation().getYaw() / 90f) & 3;
        switch (dir) {
            case 0:  return new int[]{0, 1};   // poludnie
            case 1:  return new int[]{-1, 0};  // zachod
            case 2:  return new int[]{0, -1};  // polnoc
            default: return new int[]{1, 0};   // wschod
        }
    }

    // ---------- budowa ----------
    private void build(Player p, int px, int floorY, int pz) {
        World w = p.getWorld();
        int[] f = forward(p);
        int fx = f[0], fz = f[1];   // do przodu
        int rx = -fz, rz = fx;      // w prawo
        int half = OUTER_W / 2 - 1; // wysrodkowanie na boki

        for (int lx = 0; lx < OUTER_W; lx++) {
            for (int lz = 0; lz < OUTER_W; lz++) {
                int wx = px + rx * (lx - half) + fx * (lz + DISTANCE);
                int wz = pz + rz * (lx - half) + fz * (lz + DISTANCE);

                // zamek
                for (int y = 0; y < OUTER_H; y++) {
                    boolean shell = y == 0 || y == OUTER_H - 1
                            || lx == 0 || lx == OUTER_W - 1
                            || lz == 0 || lz == OUTER_W - 1;
                    w.getBlockAt(wx, floorY + y, wz).setType(shell ? WALL : Material.AIR, false);
                }

                // fundament pod zamkiem, zeby nie wisial nad dziurami
                for (int d = 1; d <= MAX_FOUNDATION; d++) {
                    Block b = w.getBlockAt(wx, floorY - d, wz);
                    if (!b.isPassable() && !b.isLiquid()) break;
                    b.setType(WALL, false);
                }
            }
        }
    }
}
