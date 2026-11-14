package pl.eventy;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class EventCommand implements CommandExecutor, TabCompleter {

    private final EventsPlugin plugin;
    private final EventManager manager;

    public EventCommand(EventsPlugin plugin, EventManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("eventy.admin")) {
            sender.sendMessage(ChatColor.RED + "Brak uprawnien.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "/event <start <nazwa>|stop|list|status|reload>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Podaj nazwe eventu: " + String.join(", ", EventManager.IDS));
                    return true;
                }
                String id = args[1].toLowerCase();
                if (!EventManager.IDS.contains(id)) {
                    sender.sendMessage(ChatColor.RED + "Nie ma takiego eventu. Dostepne: " + String.join(", ", EventManager.IDS));
                    return true;
                }
                if (manager.start(id)) {
                    sender.sendMessage(ChatColor.GREEN + "Wystartowano event: " + id);
                } else {
                    sender.sendMessage(ChatColor.RED + "Nie udalo sie wystartowac eventu " + id
                            + " (np. bounty wymaga min. 2 graczy online, swinka wymaga poprawnego swiata w configu).");
                }
            }
            case "stop" -> {
                if (manager.getActiveId() == null) {
                    sender.sendMessage(ChatColor.RED + "Zaden event nie trwa.");
                } else {
                    manager.stop();
                    sender.sendMessage(ChatColor.GREEN + "Event zatrzymany.");
                }
            }
            case "list" -> sender.sendMessage(ChatColor.GOLD + "Eventy: " + ChatColor.WHITE + String.join(", ", EventManager.IDS));
            case "status" -> sender.sendMessage(ChatColor.GOLD + manager.status());
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(ChatColor.GREEN + "Przeladowano config.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Nieznana podkomenda.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("eventy.admin")) {
            return out;
        }
        if (args.length == 1) {
            for (String s : List.of("start", "stop", "list", "status", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            for (String id : EventManager.IDS) {
                if (id.startsWith(args[1].toLowerCase())) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
