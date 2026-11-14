package pl.eventy;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class EventManager implements Listener {

    public static final List<String> IDS =
            List.of("golden_hour", "double_hearts", "haste", "piggy_bank", "bounty");

    private final EventsPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey pigKey;

    // stan aktywnego eventu (jednoczesnie trwa maksymalnie jeden)
    private String activeId;
    private long startMillis;
    private long endMillis;
    private int tickSeconds;
    private BossBar bar;
    private BukkitTask ticker;
    private BukkitTask scheduler;

    // golden hour
    private final Map<String, Boolean> previousKeepInventory = new HashMap<>();

    // efekty (double_hearts, haste)
    private PotionEffectType effectType;
    private int effectAmplifier;

    // swinka
    private UUID pigId;
    private Location pigLocation;

    // bounty
    private UUID bountyTarget;

    public EventManager(EventsPlugin plugin) {
        this.plugin = plugin;
        this.pigKey = new NamespacedKey(plugin, "piggy_bank");
    }

    // ------------------------------------------------------------------
    // Teksty
    // ------------------------------------------------------------------

    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private String raw(String key, String... kv) {
        String s = plugin.getConfig().getString("messages." + key, "&c[brak wiadomosci: " + key + "]");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return s;
    }

    private String[] kv(Map<String, String> map) {
        String[] a = new String[map.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            a[i++] = e.getKey();
            a[i++] = e.getValue();
        }
        return a;
    }

    private void broadcast(String key, String... kv) {
        Bukkit.getServer().sendMessage(c(raw("prefix") + raw(key, kv)));
    }

    private String eventName(String id) {
        return plugin.getConfig().getString("names." + id, id);
    }

    private String fmt(double amount) {
        return String.valueOf((long) amount);
    }

    private String formatTime(long seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    public String getActiveId() {
        return activeId;
    }

    public String status() {
        if (activeId == null) {
            return "Zaden event nie trwa.";
        }
        long left = Math.max(0, (endMillis - System.currentTimeMillis()) / 1000);
        return "Trwa: " + activeId + ", zostalo " + formatTime(left);
    }

    public boolean start(String id) {
        if (!IDS.contains(id)) {
            return false;
        }
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("events." + id);
        if (s == null) {
            return false;
        }
        if (activeId != null) {
            stop();
        }

        long minutes = s.getLong("duration-minutes", 30);
        activeId = id;
        startMillis = System.currentTimeMillis();
        endMillis = startMillis + minutes * 60_000L;
        tickSeconds = 0;

        Map<String, String> ph = new HashMap<>();
        ph.put("minutes", String.valueOf(minutes));

        boolean ok = switch (id) {
            case "golden_hour" -> startGoldenHour();
            case "double_hearts" -> startEffect(PotionEffectType.HEALTH_BOOST, s.getInt("amplifier", 4));
            case "haste" -> startEffect(PotionEffectType.HASTE, s.getInt("amplifier", 1));
            case "piggy_bank" -> startPig(s, ph);
            case "bounty" -> startBounty(s, ph);
            default -> false;
        };

        if (!ok) {
            activeId = null;
            effectType = null;
            return false;
        }

        createBar(id);
        broadcast(id + "_start", kv(ph));
        Bukkit.getServer().showTitle(Title.title(c(eventName(id)), c(raw("subtitle"))));

        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        return true;
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (bar != null) {
            final BossBar old = bar;
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(old));
            bar = null;
        }
        if (activeId == null) {
            return;
        }

        switch (activeId) {
            case "golden_hour" -> restoreKeepInventory();
            case "double_hearts", "haste" -> clearEffect();
            case "piggy_bank" -> removePig();
            case "bounty" -> clearGlow();
            default -> { }
        }

        effectType = null;
        pigId = null;
        pigLocation = null;
        bountyTarget = null;
        activeId = null;
    }

    public void shutdown() {
        stop();
        if (scheduler != null) {
            scheduler.cancel();
            scheduler = null;
        }
    }

    private void expire() {
        String id = activeId;
        if (id == null) {
            return;
        }
        if (id.equals("bounty") && bountyTarget != null) {
            Player t = Bukkit.getPlayer(bountyTarget);
            broadcast("bounty_end", "player", t != null ? t.getName() : "cel");
        } else {
            broadcast(id + "_end");
        }
        stop();
    }

    private void createBar(String id) {
        BossBar.Color color = switch (id) {
            case "golden_hour", "haste" -> BossBar.Color.YELLOW;
            case "double_hearts", "bounty" -> BossBar.Color.RED;
            case "piggy_bank" -> BossBar.Color.PINK;
            default -> BossBar.Color.WHITE;
        };
        bar = BossBar.bossBar(c(eventName(id)), 1.0f, color, BossBar.Overlay.PROGRESS);
        final BossBar b = bar;
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(b));
    }

    private void tick() {
        if (activeId == null) {
            return;
        }
        tickSeconds++;
        long now = System.currentTimeMillis();
        long left = endMillis - now;
        if (left <= 0) {
            expire();
            return;
        }

        if (bar != null) {
            float progress = (float) left / (float) (endMillis - startMillis);
            bar.progress(Math.max(0f, Math.min(1f, progress)));
            bar.name(c(eventName(activeId) + " &7- &f" + formatTime(left / 1000)));
        }

        ConfigurationSection s = plugin.getConfig().getConfigurationSection("events." + activeId);
        if (s == null) {
            return;
        }
        if (activeId.equals("piggy_bank") && s.getBoolean("announce-coords", true) && tickSeconds % 120 == 0) {
            announcePig();
        }
        if (activeId.equals("bounty")) {
            int every = s.getInt("reveal-coords-seconds", 0);
            if (every > 0 && tickSeconds % every == 0) {
                announceBountyCoords();
            }
        }
    }

    // ------------------------------------------------------------------
    // Losowe odpalanie
    // ------------------------------------------------------------------

    public void startScheduler() {
        if (scheduler != null) {
            scheduler.cancel();
        }
        long minutes = Math.max(1, plugin.getConfig().getLong("scheduler.check-interval-minutes", 30));
        long period = minutes * 60L * 20L;
        scheduler = Bukkit.getScheduler().runTaskTimer(plugin, this::schedulerTick, period, period);
    }

    private void schedulerTick() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("scheduler.enabled", true) || activeId != null) {
            return;
        }
        if (Bukkit.getOnlinePlayers().size() < cfg.getInt("scheduler.min-players", 1)) {
            return;
        }
        if (random.nextInt(100) >= cfg.getInt("scheduler.chance-percent", 40)) {
            return;
        }
        List<String> pool = new ArrayList<>();
        for (String id : IDS) {
            int weight = cfg.getInt("scheduler.weights." + id, 0);
            for (int i = 0; i < weight; i++) {
                pool.add(id);
            }
        }
        Collections.shuffle(pool, random);
        for (String id : new LinkedHashSet<>(pool)) {
            if (start(id)) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Zlota godzina
    // ------------------------------------------------------------------

    private boolean startGoldenHour() {
        previousKeepInventory.clear();
        for (World w : Bukkit.getWorlds()) {
            previousKeepInventory.put(w.getName(), w.getGameRuleValue(GameRule.KEEP_INVENTORY));
            w.setGameRule(GameRule.KEEP_INVENTORY, true);
        }
        return true;
    }

    private void restoreKeepInventory() {
        for (World w : Bukkit.getWorlds()) {
            Boolean prev = previousKeepInventory.get(w.getName());
            w.setGameRule(GameRule.KEEP_INVENTORY, prev != null ? prev : Boolean.FALSE);
        }
        previousKeepInventory.clear();
    }

    // ------------------------------------------------------------------
    // Efekty (podwojne serca, haste)
    // ------------------------------------------------------------------

    private boolean startEffect(PotionEffectType type, int amplifier) {
        effectType = type;
        effectAmplifier = amplifier;
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyEffect(p);
        }
        return true;
    }

    private void applyEffect(Player p) {
        if (effectType == null) {
            return;
        }
        int ticks = (int) Math.max(20, (endMillis - System.currentTimeMillis()) / 50);
        p.addPotionEffect(new PotionEffect(effectType, ticks, effectAmplifier, false, true, true));
        if (effectType.equals(PotionEffectType.HEALTH_BOOST)) {
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                p.setHealth(max.getValue());
            }
        }
    }

    private void clearEffect() {
        if (effectType == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(effectType);
        }
    }

    // ------------------------------------------------------------------
    // Swinka skarbonka
    // ------------------------------------------------------------------

    private boolean startPig(ConfigurationSection s, Map<String, String> ph) {
        World w = Bukkit.getWorld(s.getString("world", "world"));
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        if (w == null) {
            return false;
        }

        int radius = Math.max(10, s.getInt("radius", 1000));
        Location found = null;
        for (int i = 0; i < 20 && found == null; i++) {
            int x = random.nextInt(radius * 2 + 1) - radius;
            int z = random.nextInt(radius * 2 + 1) - radius;
            org.bukkit.block.Block top = w.getHighestBlockAt(x, z);
            if (top.isLiquid() || top.getY() <= w.getMinHeight()) {
                continue;
            }
            found = top.getLocation().add(0.5, 1, 0.5);
        }
        if (found == null) {
            return false;
        }

        final Location spawnLoc = found;
        final double health = Math.max(1, Math.min(1024, s.getDouble("health", 100)));
        final Component name = c(eventName("piggy_pig"));

        Pig pig = w.spawn(spawnLoc, Pig.class, p -> {
            p.customName(name);
            p.setCustomNameVisible(true);
            p.setPersistent(true);
            p.setRemoveWhenFarAway(false);
            p.setGlowing(true);
            p.setAI(false);
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(health);
            }
            p.setHealth(health);
            p.getPersistentDataContainer().set(pigKey, PersistentDataType.BYTE, (byte) 1);
        });

        pigId = pig.getUniqueId();
        pigLocation = spawnLoc;

        ph.put("reward", fmt(s.getDouble("reward", 30000)));
        putCoords(ph, spawnLoc);
        return true;
    }

    private void removePig() {
        if (pigId != null) {
            Entity e = Bukkit.getEntity(pigId);
            if (e != null) {
                e.remove();
            }
        }
    }

    private void announcePig() {
        if (pigLocation == null) {
            return;
        }
        Map<String, String> ph = new HashMap<>();
        putCoords(ph, pigLocation);
        broadcast("piggy_bank_coords", kv(ph));
    }

    private void putCoords(Map<String, String> ph, Location loc) {
        ph.put("x", String.valueOf(loc.getBlockX()));
        ph.put("y", String.valueOf(loc.getBlockY()));
        ph.put("z", String.valueOf(loc.getBlockZ()));
        ph.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "?");
    }

    @EventHandler
    public void onPigDamage(EntityDamageEvent e) {
        if (pigId == null || !e.getEntity().getUniqueId().equals(pigId)) {
            return;
        }
        // swinke mozna zabic tylko "bezposrednio" (gracz, strzala), nie upadek/ogien/lawa
        if (!(e instanceof EntityDamageByEntityEvent)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPigDeath(EntityDeathEvent e) {
        if (pigId == null || !e.getEntity().getUniqueId().equals(pigId)) {
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);

        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            double reward = plugin.getConfig().getDouble("events.piggy_bank.reward", 30000);
            if (!plugin.pay(killer, reward)) {
                killer.sendMessage(c(raw("prefix") + raw("no-economy")));
            }
            broadcast("piggy_bank_win", "player", killer.getName(), "reward", fmt(reward));
        } else {
            broadcast("piggy_bank_lost");
        }
        stop();
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        // sprzatanie "osieroconych" swinek po restarcie/crashu
        for (Entity en : e.getEntities()) {
            if (en instanceof Pig
                    && en.getPersistentDataContainer().has(pigKey, PersistentDataType.BYTE)
                    && !en.getUniqueId().equals(pigId)) {
                en.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    // Bounty
    // ------------------------------------------------------------------

    private boolean startBounty(ConfigurationSection s, Map<String, String> ph) {
        if (Bukkit.getOnlinePlayers().size() < 2) {
            return false;
        }
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        Player target = candidates.get(random.nextInt(candidates.size()));
        bountyTarget = target.getUniqueId();
        giveGlow(target);
        target.sendMessage(c(raw("prefix") + raw("bounty_you")));

        ph.put("player", target.getName());
        ph.put("reward", fmt(s.getDouble("reward", 5000)));
        return true;
    }

    private void giveGlow(Player p) {
        int ticks = (int) Math.max(20, (endMillis - System.currentTimeMillis()) / 50);
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, false));
    }

    private void clearGlow() {
        if (bountyTarget == null) {
            return;
        }
        Player t = Bukkit.getPlayer(bountyTarget);
        if (t != null) {
            t.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    private void announceBountyCoords() {
        if (bountyTarget == null) {
            return;
        }
        Player t = Bukkit.getPlayer(bountyTarget);
        if (t == null) {
            return;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("player", t.getName());
        putCoords(ph, t.getLocation());
        broadcast("bounty_coords", kv(ph));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (bountyTarget == null || !e.getEntity().getUniqueId().equals(bountyTarget)) {
            return;
        }
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            double reward = plugin.getConfig().getDouble("events.bounty.reward", 5000);
            if (!plugin.pay(killer, reward)) {
                killer.sendMessage(c(raw("prefix") + raw("no-economy")));
            }
            broadcast("bounty_win", "killer", killer.getName(), "player", victim.getName(), "reward", fmt(reward));
            stop();
        }
        // smierc z innej przyczyny: bounty trwa dalej, swiecenie wraca po respawnie
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (bountyTarget != null && e.getPlayer().getUniqueId().equals(bountyTarget)) {
            broadcast("bounty_quit");
            stop();
        }
    }

    // ------------------------------------------------------------------
    // Gracze wchodzacy / respawnujacy sie w trakcie eventu
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (activeId == null) {
            return;
        }
        Player p = e.getPlayer();
        if (bar != null) {
            p.showBossBar(bar);
        }
        applyEffect(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (activeId == null) {
            return;
        }
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeId == null || !p.isOnline()) {
                return;
            }
            applyEffect(p);
            if (bountyTarget != null && p.getUniqueId().equals(bountyTarget)) {
                giveGlow(p);
            }
        }, 2L);
    }
}
