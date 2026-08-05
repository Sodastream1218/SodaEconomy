package de.sodaeconomy.commands;

import de.sodaeconomy.SodaEconomy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Gives a clear response for commands declared in plugin.yml but disabled by configuration. */
public final class DisabledFeatureCommand implements CommandExecutor {
    private final String messageKey;

    public DisabledFeatureCommand(String messageKey) {
        this.messageKey = messageKey;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(SodaEconomy.getInstance().getLanguageManager().getFormattedMessage(messageKey));
        return true;
    }
}
