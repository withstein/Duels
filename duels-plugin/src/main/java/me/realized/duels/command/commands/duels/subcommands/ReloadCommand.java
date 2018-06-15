package me.realized.duels.command.commands.duels.subcommands;

import java.util.List;
import java.util.stream.Collectors;
import me.realized.duels.DuelsPlugin;
import me.realized.duels.command.BaseCommand;
import me.realized.duels.util.Loadable;
import me.realized.duels.util.Reloadable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

public class ReloadCommand extends BaseCommand {

    public ReloadCommand(final DuelsPlugin plugin) {
        super(plugin, "reload", null, null, null, 1, false);
    }

    @Override
    protected void execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length > getLength()) {
            final Loadable target = plugin.find(args[1]);

            if (target == null || !(target instanceof Reloadable)) {
                sender.sendMessage("Invalid module. Available: " + plugin.getReloadables());
                return;
            }

            final String name = target.getClass().getSimpleName();

            if (plugin.reload(target)) {
                sender.sendMessage("[" + plugin.getDescription().getFullName() + "] Successfully reloaded " + name + ".");
            } else {
                sender.sendMessage("An error occured while reloading " + name + "! Please check the console for more information.");
            }

            return;
        }

        if (plugin.reload()) {
            sender.sendMessage("[" + plugin.getDescription().getFullName() + "] Reload complete.");
        } else {
            sender.sendMessage("An error occured while reloading the plugin! The plugin will be disabled, please check the console for more information.");
        }
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length == 2) {
            return plugin.getReloadables().stream()
                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }

        return null;
    }
}