package com.example.bountycombat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class BountyCombat extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, Long> combatEnd = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Set<UUID> combatLogged = new HashSet<>();
    private final Set<String> blockedCommands = new HashSet<>();

    private Economy economy;
    private int combatSeconds;
    private double minBounty;
    private boolean blockPearls;
    private File bountyFile;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        bountyFile = new File(getDataFolder(), "bounties.yml");
        loadBounties();

        if (eco() == null) {
            getLogger().warning("No Vault economy found yet. Make sure Vault and an economy plugin (EssentialsX) are installed.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("bounty")).setExecutor(this);
        Objects.requireNonNull(getCommand("bountyall")).setExecutor(this);

        // Combat timer / action bar (runs every 0.5s)
        Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 10L, 10L);
    }

    @Override
    public void onDisable() {
        saveBounties();
    }

    private void reloadSettings() {
        reloadConfig();
        combatSeconds = getConfig().getInt("combat-seconds", 20);
        minBounty = getConfig().getDouble("min-bounty", 100);
        blockPearls = getConfig().getBoolean("block-ender-pearls", false);
        blockedCommands.clear();
        for (String s : getConfig().getStringList("blocked-commands")) {
            blockedCommands.add(s.toLowerCase(Locale.ROOT));
        }
    }

    /** Looks up the Vault economy lazily so load order doesn't matter. */
    private @Nullable Economy eco() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    private String money(double amount) {
        Economy e = eco();
        return e != null ? e.format(amount) : String.format("%.2f", amount);
    }

    // ------------------------------------------------------------------ storage

    private void loadBounties() {
        bounties.clear();
        if (!bountyFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(bountyFile);
        for (String key : yml.getKeys(false)) {
            try {
                double amount = yml.getDouble(key);
                if (amount > 0) bounties.put(UUID.fromString(key), amount);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveBounties() {
        YamlConfiguration yml = new YamlConfiguration();
        bounties.forEach((id, amt) -> yml.set(id.toString(), amt));
        try {
            getDataFolder().mkdirs();
            yml.save(bountyFile);
        } catch (IOException e) {
            getLogger().severe("Could not save bounties.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ combat tag

    private boolean inCombat(Player p) {
        Long end = combatEnd.get(p.getUniqueId());
        return end != null && end > System.currentTimeMillis();
    }

    private void tag(Player p, Player other) {
        boolean wasInCombat = inCombat(p);
        combatEnd.put(p.getUniqueId(), System.currentTimeMillis() + combatSeconds * 1000L);
        lastAttacker.put(p.getUniqueId(), other.getUniqueId());
        if (!wasInCombat) {
            p.sendMessage(MM.deserialize("<red><bold>You are in combat!</bold> <gray>Do not log out for <yellow>"
                    + combatSeconds + "s<gray>."));
        }
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = combatEnd.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            long left = e.getValue() - now;
            if (left <= 0) {
                it.remove();
                lastAttacker.remove(e.getKey());
                p.sendActionBar(MM.deserialize("<green>You are no longer in combat."));
                p.sendMessage(MM.deserialize("<green>You are no longer in combat. You can log out safely."));
            } else {
                long secs = (left + 999) / 1000;
                p.sendActionBar(MM.deserialize("<red>Combat: <yellow>" + secs + "s"));
            }
        }
    }

    private @Nullable Player resolveDamager(Object damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolveDamager(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim, attacker);
        tag(attacker, victim);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p) || p.isDead()) {
            combatEnd.remove(p.getUniqueId());
            return;
        }
        // Combat log -> die normally (items drop, exp drops, death message)
        combatLogged.add(p.getUniqueId());
        Bukkit.broadcast(MM.deserialize("<red>" + p.getName() + " logged out during combat and was killed!"));
        p.setHealth(0.0);
        combatLogged.remove(p.getUniqueId());
        combatEnd.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        UUID victimId = victim.getUniqueId();

        Player killer = victim.getKiller();
        if (killer == null && combatLogged.contains(victimId)) {
            UUID att = lastAttacker.get(victimId);
            if (att != null) killer = Bukkit.getPlayer(att);
        }

        if (killer != null && !killer.equals(victim)) {
            Double reward = bounties.get(victimId);
            Economy eco = eco();
            if (reward != null && reward > 0 && eco != null) {
                EconomyResponse r = eco.depositPlayer(killer, reward);
                if (r.transactionSuccess()) {
                    bounties.remove(victimId);
                    saveBounties();
                    Bukkit.broadcast(MM.deserialize("<gold>" + killer.getName() + " claimed the <yellow>"
                            + money(reward) + " <gold>bounty on <red>" + victim.getName() + "<gold>!"));
                } else {
                    getLogger().warning("Could not pay bounty to " + killer.getName() + ": " + r.errorMessage);
                }
            }
        }

        combatEnd.remove(victimId);
        lastAttacker.remove(victimId);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p)) return;

        String msg = e.getMessage();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String label = msg.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (blockedCommands.contains(label)) {
            e.setCancelled(true);
            p.sendMessage(MM.deserialize("<red>You can't use that command while in combat!"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (e.getItem().getType() == Material.CHORUS_FRUIT && inCombat(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(MM.deserialize("<red>You can't eat chorus fruit while in combat!"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p)) return;
        TeleportCause cause = e.getCause();
        if (cause == TeleportCause.CHORUS_FRUIT
                || cause == TeleportCause.COMMAND
                || (blockPearls && cause == TeleportCause.ENDER_PEARL)) {
            e.setCancelled(true);
            p.sendMessage(MM.deserialize("<red>You can't teleport while in combat!"));
        }
    }

    // ------------------------------------------------------------------ bounty helpers

    private @Nullable OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label,
                             @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("bountyall")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            openGui(p, 0);
            return true;
        }

        // /bounty
        if (args.length == 0) {
            sender.sendMessage(MM.deserialize("<yellow>Usage: /bounty <player> | /bounty add <player> <amount>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("add")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (args.length < 3) {
                p.sendMessage(MM.deserialize("<yellow>Usage: /bounty add <player> <amount>"));
                return true;
            }
            Economy eco = eco();
            if (eco == null) {
                p.sendMessage(MM.deserialize("<red>Economy is not available right now."));
                return true;
            }
            OfflinePlayer target = findPlayer(args[1]);
            if (target == null) {
                p.sendMessage(MM.deserialize("<red>That player has never joined."));
                return true;
            }
            if (target.getUniqueId().equals(p.getUniqueId())) {
                p.sendMessage(MM.deserialize("<red>You can't put a bounty on yourself."));
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                p.sendMessage(MM.deserialize("<red>Invalid amount."));
                return true;
            }
            if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < minBounty) {
                p.sendMessage(MM.deserialize("<red>Minimum bounty is <yellow>" + money(minBounty) + "<red>."));
                return true;
            }
            amount = Math.floor(amount * 100) / 100.0;
            if (!eco.has(p, amount)) {
                p.sendMessage(MM.deserialize("<red>You don't have <yellow>" + money(amount) + "<red>."));
                return true;
            }
            EconomyResponse r = eco.withdrawPlayer(p, amount);
            if (!r.transactionSuccess()) {
                p.sendMessage(MM.deserialize("<red>Payment failed: " + r.errorMessage));
                return true;
            }
            double total = bounties.merge(target.getUniqueId(), amount, Double::sum);
            saveBounties();

            String tName = target.getName() == null ? args[1] : target.getName();
            Bukkit.broadcast(MM.deserialize("<gold>" + p.getName() + " added <yellow>" + money(amount)
                    + " <gold>to the bounty on <red>" + tName + "<gold>! Total: <yellow>" + money(total)));
            return true;
        }

        // /bounty <player>
        OfflinePlayer target = findPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(MM.deserialize("<red>That player has never joined."));
            return true;
        }
        double b = bounties.getOrDefault(target.getUniqueId(), 0.0);
        String name = target.getName() == null ? args[0] : target.getName();
        sender.sendMessage(MM.deserialize("<gold>Bounty on <red>" + name + "<gold>: <yellow>" + money(b)));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias,
                                      @NotNull String[] args) {
        if (!cmd.getName().equalsIgnoreCase("bounty")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("add".startsWith(args[0].toLowerCase())) out.add("add");
            Bukkit.getOnlinePlayers().forEach(pl -> {
                if (pl.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(pl.getName());
            });
        } else if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            Bukkit.getOnlinePlayers().forEach(pl -> {
                if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ GUI

    private static final class BountyGui implements InventoryHolder {
        final int page;
        Inventory inv;

        BountyGui(int page) {
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    private void openGui(Player viewer, int page) {
        // Everyone online + anyone with a bounty, sorted by bounty high -> low
        Set<UUID> ids = new LinkedHashSet<>();
        Bukkit.getOnlinePlayers().forEach(p -> ids.add(p.getUniqueId()));
        ids.addAll(bounties.keySet());

        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort((a, b) -> {
            int cmp = Double.compare(bounties.getOrDefault(b, 0.0), bounties.getOrDefault(a, 0.0));
            if (cmp != 0) return cmp;
            String na = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(a).getName(), "");
            String nb = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(b).getName(), "");
            return na.compareToIgnoreCase(nb);
        });

        int perPage = 45;
        int maxPage = Math.max(0, (sorted.size() - 1) / perPage);
        page = Math.max(0, Math.min(page, maxPage));

        BountyGui holder = new BountyGui(page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<dark_red>Bounties <gray>(" + (page + 1) + "/" + (maxPage + 1) + ")"));
        holder.inv = inv;

        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < sorted.size(); i++) {
            UUID id = sorted.get(start + i);
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            double b = bounties.getOrDefault(id, 0.0);
            int rank = start + i + 1;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(op);
            String name = op.getName() == null ? "Unknown" : op.getName();
            meta.displayName(MM.deserialize("<!italic><red>" + name));
            List<Component> lore = new ArrayList<>();
            lore.add(MM.deserialize("<!italic><gray>Rank: <white>#" + rank));
            lore.add(MM.deserialize("<!italic><gold>Bounty: <yellow>" + money(b)));
            lore.add(MM.deserialize(op.isOnline() ? "<!italic><green>Online" : "<!italic><dark_gray>Offline"));
            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }

        inv.setItem(45, navItem(Material.ARROW, "<yellow>Previous page"));
        inv.setItem(53, navItem(Material.ARROW, "<yellow>Next page"));
        inv.setItem(49, navItem(Material.PAPER, "<gray>Page " + (page + 1) + " of " + (maxPage + 1)));

        viewer.openInventory(inv);
    }

    private ItemStack navItem(Material m, String name) {
        ItemStack it = new ItemStack(m);
        var meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BountyGui gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        if (e.getSlot() == 45) openGui(p, gui.page - 1);
        else if (e.getSlot() == 53) openGui(p, gui.page + 1);
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof BountyGui) e.setCancelled(true);
    }
}
