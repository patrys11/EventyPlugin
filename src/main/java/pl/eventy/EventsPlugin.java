package pl.eventy;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class EventsPlugin extends JavaPlugin {

    private EconomyHook economy;
    private EventManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            economy = EconomyHook.create(this);
        }
        if (economy == null) {
            getLogger().warning("Nie znaleziono Vault/ekonomii. Nagrody pieniezne nie beda wyplacane.");
        }

        manager = new EventManager(this);
        getServer().getPluginManager().registerEvents(manager, this);

        PluginCommand cmd = getCommand("event");
        if (cmd != null) {
            EventCommand handler = new EventCommand(this, manager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        manager.startScheduler();
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public void reload() {
        reloadConfig();
        manager.startScheduler();
    }

    /** Wyplaca nagrode graczowi. Zwraca false, jesli nie ma ekonomii. */
    public boolean pay(OfflinePlayer player, double amount) {
        if (economy == null) {
            return false;
        }
        return economy.deposit(player, amount);
    }
}
