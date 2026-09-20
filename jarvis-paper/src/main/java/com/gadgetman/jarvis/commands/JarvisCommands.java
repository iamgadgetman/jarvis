package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Bukkit's side of "/jarvis": registration and the hand-off to core's
 * {@link CommandSink}. What the words mean is decided there.
 */
public class JarvisCommands implements CommandExecutor, TabCompleter {

    private final Jarvis plugin;

    public JarvisCommands(Jarvis plugin) {
        this.plugin = plugin;
    }

    private Optional<Owner> asPlayer(CommandSender sender) {
        return sender instanceof Player p ? Optional.of(plugin.owner(p)) : Optional.empty();
    }

    private Audience audience(CommandSender sender) {
        Optional<Owner> player = asPlayer(sender);
        return player.isPresent() ? player.get() : plugin.getPlatform().players().console();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.core().commands().jarvis(audience(sender), asPlayer(sender), Arrays.asList(args), false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return plugin.core().commands().jarvis(audience(sender), asPlayer(sender), Arrays.asList(args), true);
    }
}
