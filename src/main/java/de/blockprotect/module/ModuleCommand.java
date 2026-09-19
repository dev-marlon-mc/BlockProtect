package de.blockprotect.module;

import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface ModuleCommand {
    boolean execute(CommandSender sender, String[] args);
}
