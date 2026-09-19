package de.blockprotect.module.internal;

import de.blockprotect.module.ModuleSnapshot;
import de.blockprotect.module.ModuleStatus;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Admin inventory for the live module lifecycle. */
public final class ModuleAdminGui implements Listener {
    private static final int[] MODULE_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final JavaPlugin plugin;
    private final ModuleManager manager;

    public ModuleAdminGui(JavaPlugin plugin, ModuleManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void open(Player player) {
        if (!player.hasPermission("blockprotect.modules")) {
            player.sendMessage(ChatColor.RED + "Dafür fehlt dir die Berechtigung: blockprotect.modules");
            return;
        }
        GuiHolder holder = new GuiHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("BlockProtect • Live-Module", NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(4, item(Material.COMMAND_BLOCK, "Live-Modulverwaltung", NamedTextColor.GOLD,
                "Core bleibt geladen.", "Links: aktivieren/deaktivieren", "Rechts: geprüftes Update laden"));

        List<ModuleSnapshot> modules = manager.snapshots();
        for (int index = 0; index < Math.min(MODULE_SLOTS.length, modules.size()); index++) {
            inventory.setItem(MODULE_SLOTS[index], moduleItem(modules.get(index)));
        }
        if (modules.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "Keine Module gefunden", NamedTextColor.RED,
                    "Lege Modul-JARs nach plugins/BlockProtect/modules/"));
        }
        inventory.setItem(40, item(Material.EMERALD, "Updates prüfen / laden", NamedTextColor.GREEN,
                "Sucht die konfigurierte Releasequelle.", "Klick: für alle verfügbaren Module"));
        inventory.setItem(45, item(Material.CLOCK, "Status aktualisieren", NamedTextColor.AQUA,
                "Lädt die Modulübersicht neu"));
        inventory.setItem(49, item(Material.BARRIER, "Schließen", NamedTextColor.RED));
        inventory.setItem(53, item(Material.OAK_DOOR, "Zurück", NamedTextColor.YELLOW,
                "Mit /bp gui weiterarbeiten"));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())
                || !player.hasPermission("blockprotect.modules")) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            reopen(player);
            return;
        }
        if (slot == 40) {
            updateAvailable(player);
            return;
        }
        for (int index = 0; index < MODULE_SLOTS.length; index++) {
            if (MODULE_SLOTS[index] != slot) {
                continue;
            }
            List<ModuleSnapshot> modules = manager.snapshots();
            if (index >= modules.size()) {
                return;
            }
            ModuleSnapshot module = modules.get(index);
            if (event.isRightClick()) {
                update(player, module.id());
            } else if (event.isLeftClick()) {
                boolean success = module.enabled()
                        ? manager.disable(module.id())
                        : manager.enable(module.id());
                player.sendMessage((success ? ChatColor.GREEN : ChatColor.RED)
                        + (success ? "Modul " : "Modul konnte nicht geändert werden: ") + module.id()
                        + (success ? (module.enabled() ? " deaktiviert." : " aktiviert.") : ""));
                reopen(player);
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    private void updateAvailable(Player player) {
        player.sendMessage(ChatColor.GRAY + "Releasequelle wird geprüft …");
        manager.checkForUpdatesAsync(notices -> {
            if (notices.isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "Keine geprüften Modul-Updates verfügbar.");
                reopen(player);
                return;
            }
            notices.forEach(notice -> update(player, notice.moduleId()));
        });
    }

    private void update(Player player, String id) {
        player.sendMessage(ChatColor.GRAY + "Update für " + id + " wird geprüft und vorbereitet …");
        manager.updateAsync(id, result -> {
            player.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED)
                    + result.message());
            if (player.isOnline()) {
                reopen(player);
            }
        });
    }

    private void reopen(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                open(player);
            }
        });
    }

    private static ItemStack moduleItem(ModuleSnapshot module) {
        boolean enabled = module.enabled();
        Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        NamedTextColor color = enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY;
        List<String> lore = new ArrayList<>(List.of(
                "Status: " + statusText(module.status()),
                "Version: " + module.version(),
                module.message() == null ? "" : module.message(),
                "Linksklick: " + (enabled ? "deaktivieren" : "aktivieren"),
                "Rechtsklick: Update laden"
        ));
        if (module.availableUpdate() != null) {
            lore.add("Update: v" + module.availableUpdate() + " verfügbar");
        }
        return item(material, module.id() + " · " + module.name(), color, lore.toArray(String[]::new));
    }

    private static String statusText(ModuleStatus status) {
        return switch (status) {
            case ENABLED -> "AKTIV";
            case DISABLED -> "AUS";
            case UPDATING -> "WIRD GEÄNDERT";
            case FAILED -> "FEHLER";
            case INCOMPATIBLE -> "INKOMPATIBEL";
        };
    }

    private static void fill(Inventory inventory) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane.clone());
        }
    }

    private static ItemStack item(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(Arrays.stream(lore)
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class GuiHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private GuiHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
