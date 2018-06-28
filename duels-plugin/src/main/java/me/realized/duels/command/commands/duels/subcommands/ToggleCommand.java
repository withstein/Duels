package me.realized.duels.command.commands.duels.subcommands;

import java.util.List;
import me.realized.duels.DuelsPlugin;
import me.realized.duels.arena.Arena;
import me.realized.duels.command.BaseCommand;
import org.apache.commons.lang.StringUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

public class ToggleCommand extends BaseCommand {

    public ToggleCommand(final DuelsPlugin plugin) {
        super(plugin, "toggle", "toggle [name]", "Enables or disables an arena.", 2, false);
    }

    @Override
    protected void execute(final CommandSender sender, final String label, final String[] args) {
        final String name = StringUtils.join(args, " ", 1, args.length).replace("-", " ");
        final Arena arena = arenaManager.get(name);

        if (arena == null) {
            lang.sendMessage(sender, "ERROR.arena.not-found", "name", name);
            return;
        }

        arena.setDisabled(sender, !arena.isDisabled());
        lang.sendMessage(sender, "COMMAND.duels.toggle", "name", name, "state", arena.isDisabled() ? "&cdisabled" : "&aenabled");
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length == 2) {
            return handleTabCompletion(sender, args[1], "arena", arenaManager.getArenas(), Arena::getName);
        }

        return null;
    }
}
