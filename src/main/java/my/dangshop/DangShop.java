package my.dangshop;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;

public class DangShop extends JavaPlugin implements Listener, CommandExecutor {

    private static final int SIZE = 54;
    private static final int BACK_SLOT = 49;
    private static final int MAX_ITEMS = 45;

    private static class Holder implements InventoryHolder {
        final String section; // null = menu utama

        Holder(String section) {
            this.section = section;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class ShopItem {
        final Material mat;
        final double buy;
        final double sell;

        ShopItem(Material mat, double buy, double sell) {
            this.mat = mat;
            this.buy = buy;
            this.sell = sell;
        }
    }

    private final Map<String, List<ShopItem>> shops = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Material> icons = new HashMap<>();
    private final Map<String, Integer> slots = new HashMap<>();

    private Object eco;
    private Method getBalance;
    private Method withdraw;
    private Method deposit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadShops();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("dshop") != null) getCommand("dshop").setExecutor(this);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key, String def) {
        return color(getConfig().getString("messages." + key, def));
    }

    // ---------- Muat config ----------
    private void loadShops() {
        shops.clear();
        names.clear();
        icons.clear();
        slots.clear();

        ConfigurationSection secs = getConfig().getConfigurationSection("sections");
        if (secs == null) return;

        for (String id : secs.getKeys(false)) {
            ConfigurationSection s = secs.getConfigurationSection(id);
            if (s == null) continue;

            names.put(id, color(s.getString("name", id)));
            Material icon = Material.matchMaterial(s.getString("icon", "STONE"));
            icons.put(id, icon == null ? Material.STONE : icon);
            slots.put(id, s.getInt("slot", 10));

            List<ShopItem> list = new ArrayList<>();
            for (String line : s.getStringList("items")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 3) {
                    getLogger().warning("Baris tak sah dalam " + id + ": " + line);
                    continue;
                }
                Material m = Material.matchMaterial(parts[0]);
                if (m == null || !m.isItem()) {
                    getLogger().warning("Item tak dikenali dalam " + id + ": " + parts[0]);
                    continue;
                }
                try {
                    list.add(new ShopItem(m, Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
                } catch (NumberFormatException ex) {
                    getLogger().warning("Harga tak sah dalam " + id + ": " + line);
                }
            }
            shops.put(id, list);
        }
    }

    // ---------- Command ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("dangshop.reload")) return true;
            reloadConfig();
            loadShops();
            sender.sendMessage(msg("reloaded", "&aConfig dimuat semula."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Hanya pemain boleh guna command ini.");
            return true;
        }
        if (!p.hasPermission("dangshop.use")) return true;

        if (args.length > 0 && shops.containsKey(args[0].toLowerCase())) {
            openSection(p, args[0].toLowerCase());
            return true;
        }
        openMain(p);
        return true;
    }

    // ---------- GUI ----------
    private void fill(Inventory inv, int from, int to) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = pane.getItemMeta();
        m.setDisplayName(" ");
        pane.setItemMeta(m);
        for (int i = from; i < to; i++) inv.setItem(i, pane);
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(new Holder(null), SIZE,
                color(getConfig().getString("title", "&8DangShop")));
        fill(inv, 0, SIZE);

        for (String id : shops.keySet()) {
            int slot = slots.get(id);
            if (slot < 0 || slot >= SIZE) continue;
            ItemStack it = new ItemStack(icons.get(id));
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(names.get(id));
            m.setLore(List.of(
                    color("&7" + shops.get(id).size() + " item"),
                    color("&eKlik untuk buka")));
            m.addItemFlags(ItemFlag.values());
            it.setItemMeta(m);
            inv.setItem(slot, it);
        }
        p.openInventory(inv);
    }

    private void openSection(Player p, String id) {
        List<ShopItem> list = shops.get(id);
        if (list == null) return;

        Inventory inv = Bukkit.createInventory(new Holder(id), SIZE, names.get(id));
        fill(inv, MAX_ITEMS, SIZE);

        for (int i = 0; i < list.size() && i < MAX_ITEMS; i++) {
            inv.setItem(i, display(list.get(i)));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(color("&cKembali"));
        back.setItemMeta(bm);
        inv.setItem(BACK_SLOT, back);

        p.openInventory(inv);
    }

    private ItemStack display(ShopItem si) {
        ItemStack it = new ItemStack(si.mat);
        ItemMeta m = it.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (si.buy > 0) lore.add(color("&aBeli: &f$" + money(si.buy)));
        if (si.sell > 0) lore.add(color("&cJual: &f$" + money(si.sell)));
        lore.add("");
        lore.add(color("&7Klik kiri: beli 1  |  Shift+kiri: beli 64"));
        lore.add(color("&7Klik kanan: jual 1  |  Shift+kanan: jual semua"));
        m.setLore(lore);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getSlot();

        // Menu utama: klik section
        if (h.section == null) {
            for (Map.Entry<String, Integer> s : slots.entrySet()) {
                if (s.getValue() == slot && shops.containsKey(s.getKey())) {
                    String id = s.getKey();
                    Bukkit.getScheduler().runTask(this, () -> openSection(p, id));
                    return;
                }
            }
            return;
        }

        // Dalam section
        if (slot == BACK_SLOT) {
            Bukkit.getScheduler().runTask(this, () -> openMain(p));
            return;
        }
        List<ShopItem> list = shops.get(h.section);
        if (list == null || slot < 0 || slot >= MAX_ITEMS || slot >= list.size()) return;

        ShopItem si = list.get(slot);
        ClickType c = e.getClick();
        if (c.isLeftClick()) {
            buy(p, si, c.isShiftClick() ? 64 : 1);
        } else if (c.isRightClick()) {
            sell(p, si, c.isShiftClick() ? -1 : 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ---------- Beli ----------
    private void buy(Player p, ShopItem si, int amount) {
        if (si.buy <= 0) {
            p.sendMessage(msg("cant-buy", "&cItem ni tak boleh dibeli."));
            return;
        }
        if (!hookEconomy()) {
            p.sendMessage(msg("no-economy", "&cSistem duit (Vault) tak jumpa."));
            return;
        }
        if (balance(p) < si.buy * amount) {
            p.sendMessage(msg("no-money", "&cDuit tak cukup."));
            return;
        }

        int max = si.mat.getMaxStackSize();
        int left = amount;
        int notFit = 0;
        while (left > 0) {
            int n = Math.min(left, max);
            Map<Integer, ItemStack> rest = p.getInventory().addItem(new ItemStack(si.mat, n));
            for (ItemStack r : rest.values()) notFit += r.getAmount();
            left -= n;
        }

        int given = amount - notFit;
        if (given <= 0) {
            p.sendMessage(msg("inv-full", "&cInventori penuh."));
            return;
        }

        double cost = si.buy * given;
        take(p, cost);
        p.sendMessage(msg("bought", "&aBeli &f%amount%x %item% &auntuk &f$%price%")
                .replace("%amount%", String.valueOf(given))
                .replace("%item%", niceName(si.mat))
                .replace("%price%", money(cost)));
    }

    // ---------- Jual (amount -1 = semua) ----------
    private void sell(Player p, ShopItem si, int amount) {
        if (si.sell <= 0) {
            p.sendMessage(msg("cant-sell", "&cItem ni tak boleh dijual."));
            return;
        }
        if (!hookEconomy()) {
            p.sendMessage(msg("no-economy", "&cSistem duit (Vault) tak jumpa."));
            return;
        }

        ItemStack ref = new ItemStack(si.mat);
        ItemStack[] contents = p.getInventory().getStorageContents();

        int have = 0;
        for (ItemStack it : contents) {
            if (it != null && it.isSimilar(ref)) have += it.getAmount();
        }
        if (have <= 0) {
            p.sendMessage(msg("no-items", "&cAwak tiada item tu."));
            return;
        }

        int n = amount < 0 ? have : Math.min(amount, have);
        int remaining = n;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !it.isSimilar(ref)) continue;
            int takeAmt = Math.min(remaining, it.getAmount());
            if (takeAmt >= it.getAmount()) {
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - takeAmt);
            }
            remaining -= takeAmt;
        }
        p.getInventory().setStorageContents(contents);

        double gain = si.sell * n;
        give(p, gain);
        p.sendMessage(msg("sold", "&aJual &f%amount%x %item% &auntuk &f$%price%")
                .replace("%amount%", String.valueOf(n))
                .replace("%item%", niceName(si.mat))
                .replace("%price%", money(gain)));
    }

    // ---------- Duit (Vault) ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hookEconomy() {
        if (eco != null) return true;
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return false;
            eco = rsp.getProvider();
            getBalance = c.getMethod("getBalance", OfflinePlayer.class);
            withdraw = c.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = c.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private double balance(Player p) {
        try {
            return ((Number) getBalance.invoke(eco, p)).doubleValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void take(Player p, double amount) {
        try {
            withdraw.invoke(eco, p, amount);
        } catch (Throwable t) {
            getLogger().warning("Gagal ambil duit: " + t.getMessage());
        }
    }

    private void give(Player p, double amount) {
        try {
            deposit.invoke(eco, p, amount);
        } catch (Throwable t) {
            getLogger().warning("Gagal masukkan duit: " + t.getMessage());
        }
    }

    // ---------- Util ----------
    private String money(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    private String niceName(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
  }
