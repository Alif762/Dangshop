package my.dangshop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;

public class SellMenu implements Listener, CommandExecutor {

    private static final int SIZE = 54;
    private static final int SELL_SLOT = 53; // bottom-right

    private static class SellHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final JavaPlugin plugin;
    private Object eco;
    private Method deposit;

    public SellMenu(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("sells") != null) plugin.getCommand("sells").setExecutor(this);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ---------- Prices: read from the DangShop sections (sell column) ----------
    private Map<Material, Double> prices() {
        Map<Material, Double> map = new HashMap<>();
        ConfigurationSection secs = plugin.getConfig().getConfigurationSection("sections");
        if (secs == null) return map;
        for (String id : secs.getKeys(false)) {
            for (String line : secs.getStringList(id + ".items")) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3) continue;
                Material m = Material.matchMaterial(p[0]);
                if (m == null) continue;
                try {
                    double sell = Double.parseDouble(p[2]);
                    if (sell > 0) map.merge(m, sell, Math::max);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return map;
    }

    // ---------- Command ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!p.hasPermission("dangshop.sell")) return true;

        Inventory inv = Bukkit.createInventory(new SellHolder(), SIZE, color("&8Sell"));
        inv.setItem(SELL_SLOT, sellButton());
        p.openInventory(inv);
        return true;
    }

    private ItemStack sellButton() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&a&lSELL"));
        meta.setLore(List.of(
                color("&7Put items in this menu,"),
                color("&7then click here to sell.")));
        item.setItemMeta(meta);
        return item;
    }

    // ---------- Events ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (e.getRawSlot() == SELL_SLOT) {
            e.setCancelled(true);
            sell(p, top);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SellHolder)) return;
        if (e.getRawSlots().contains(SELL_SLOT)) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        // Give back anything that wasn't sold
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack r : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), r);
            }
        }
        inv.clear();
    }

    // ---------- Sell ----------
    private void sell(Player p, Inventory inv) {
        if (!hookEconomy()) {
            p.sendMessage(color("&cEconomy system (Vault) not found."));
            return;
        }

        Map<Material, Double> prices = prices();
        double total = 0;
        int count = 0;
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            if (it.hasItemMeta()) continue; // custom, named or enchanted items are not sold
            Double price = prices.get(it.getType());
            if (price == null) continue;
            total += price * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }

        if (count == 0) {
            p.sendMessage(color("&cThere's nothing sellable in the menu."));
            return;
        }

        try {
            deposit.invoke(eco, p, total);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to deposit money: " + t.getMessage());
        }

        p.sendMessage(color("&aSold &f" + count + " &aitems for &f$"
                + String.format(Locale.US, "%,.2f", total)));
        p.playSound(p.getLocation(), "entity.experience_orb.pickup", 1f, 1f);
    }

    // ---------- Money (Vault) ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hookEconomy() {
        if (eco != null) return true;
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return false;
            eco = rsp.getProvider();
            deposit = c.getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
