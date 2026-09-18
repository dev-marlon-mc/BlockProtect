package de.blockprotect.command;

import de.blockprotect.audit.AuditUtil;
import de.blockprotect.audit.InspectionState;
import de.blockprotect.config.ConfigService;
import de.blockprotect.storage.AuditQuery;
import de.blockprotect.storage.AuditRecord;
import de.blockprotect.storage.AuditStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockProtectCommand implements CommandExecutor, TabCompleter, Listener {
    private static final DateTimeFormatter DETAIL_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int INSPECT_PAGE_SIZE = 5;
    private static final int ROLLBACK_PREVIEW_SIZE = 4;
    private static final int GUI_PAGE_SIZE = 28;
    private static final int[] GUI_RESULT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final Set<String> ROLLBACK_ACTIONS = Set.of(
            "BLOCK_BREAK", "BLOCK_PLACE", "BLOCK_FLOW", "BLOCK_BURN", "BLOCK_FADE",
            "BLOCK_FORM", "BLOCK_GROW", "BLOCK_SPREAD", "EXPLOSION_BREAK",
            "BUCKET_EMPTY", "BUCKET_FILL", "ENTITY_CHANGE_BLOCK", "PISTON_EXTEND",
            "PISTON_RETRACT", "CONTAINER_ITEM_CHANGE", "CONTAINER_SNAPSHOT"
    );

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final AuditStore store;
    private final InspectionState inspectionState;
    private final ConcurrentHashMap<UUID, LookupFilters> inspectFilters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AuditView> auditViews = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AuditQuery> auditQueries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingRollback> pendingRollbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AppliedRollback> lastRollbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingRestore> pendingRestores = new ConcurrentHashMap<>();

    public BlockProtectCommand(JavaPlugin plugin, ConfigService config, AuditStore store,
                               InspectionState inspectionState) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.inspectionState = inspectionState;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "inspect" -> inspect(sender, args);
            case "lookup", "l" -> lookup(sender, args);
            case "gui", "menu" -> gui(sender);
            case "rollback", "rb" -> rollback(sender, args);
            case "restore", "redo" -> restore(sender, args);
            case "module" -> module(sender, args);
            case "config", "settings" -> config(sender, args);
            case "flush" -> flush(sender);
            case "purge" -> purge(sender, args);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unbekannter Unterbefehl. Nutze /" + label + " help.");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], "help", "status", "inspect", "lookup", "rollback", "restore",
                    "gui", "module", "config", "flush", "purge");
        }
        if (args[0].equalsIgnoreCase("inspect") && args.length == 2) {
            return partial(args[1], "help", "next", "prev", "page", "filter");
        }
        if (args[0].equalsIgnoreCase("inspect") && args.length == 3 && args[1].equalsIgnoreCase("page")) {
            return partial(args[2], "1", "2", "3");
        }
        if (args[0].equalsIgnoreCase("inspect") && args.length == 3 && args[1].equalsIgnoreCase("filter")) {
            return partial(args[2], "clear", "player", "action", "source", "target");
        }
        if (args[0].equalsIgnoreCase("inspect") && args.length >= 4 && args[1].equalsIgnoreCase("filter")) {
            return partial(args[args.length - 1], "player", "action", "source", "target", "clear");
        }
        if (args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("l")) {
            return completeLookup(args);
        }
        if ((args[0].equalsIgnoreCase("rollback") || args[0].equalsIgnoreCase("rb")) && args.length == 2) {
            return partial(args[1], "help", "confirm", "cancel", "undo", "--radius", "--time", "--since",
                    "--until", "--player", "--action", "--limit");
        }
        if ((args[0].equalsIgnoreCase("restore") || args[0].equalsIgnoreCase("redo")) && args.length == 2) {
            return partial(args[1], "confirm", "cancel");
        }
        if (args[0].equalsIgnoreCase("module") && args.length == 2) {
            return partial(args[1], "help", "list", "set");
        }
        if (args[0].equalsIgnoreCase("module") && args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return partial(args[2], "blocks", "containers", "inventories", "entities", "interactions",
                    "environment", "commands", "chat", "sessions");
        }
        if (args[0].equalsIgnoreCase("module") && args.length == 4 && args[1].equalsIgnoreCase("set")) {
            return partial(args[3], "on", "off");
        }
        if (args[0].equalsIgnoreCase("config") || args[0].equalsIgnoreCase("settings")) {
            if (args.length == 2) {
                return partial(args[1], "help", "get", "set", "list");
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("set"))) {
                return config.keys(args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("purge") && args.length == 3) {
            return partial(args[2], "confirm");
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInspectionInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!inspectionState.isActive(player.getUniqueId())
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        // A block item causes a BlockPlaceEvent for the adjacent air block. That
        // event is handled below so the exact would-be placement target can be
        // inspected without actually changing the world.
        if (event.getItem() != null && event.getItem().getType().isBlock()) {
            return;
        }
        event.setCancelled(true);
        inspectLocation(player, event.getClickedBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInspectionPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!inspectionState.isActive(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        inspectLocation(player, event.getBlockPlaced().getLocation());
    }

    private void inspectLocation(Player player, Location location) {
        LookupFilters filters = inspectFilters.getOrDefault(player.getUniqueId(), LookupFilters.empty());
        int limit = Math.min(config.getInt("lookup.max-limit", 500), 500);
        AuditQuery query = exactQuery(location, filters, limit);
        player.sendMessage(Component.text("BlockProtect Inspect – Abfrage läuft …", NamedTextColor.GRAY));
        store.query(query).whenComplete((records, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                player.sendMessage(Component.text("Inspect fehlgeschlagen: " + rootMessage(throwable), NamedTextColor.RED));
                return;
            }
            inspectFilters.putIfAbsent(player.getUniqueId(), filters);
            AuditView view = new AuditView("inspect", null, location.clone(), filters, records, 0);
            auditViews.put(player.getUniqueId(), view);
            auditQueries.put(player.getUniqueId(), query);
            openAuditGui(player, view, 0);
        }));
    }

    private boolean status(CommandSender sender) {
        if (!has(sender, "blockprotect.lookup")) {
            return true;
        }
        boolean tracking = config.getBoolean("tracking.enabled", true);
        long dropped = store.droppedCount();
        sender.sendMessage(ChatColor.GOLD + "╭─ BlockProtect · Status");
        sender.sendMessage(ChatColor.GRAY + "│ Aufzeichnung: "
                + (tracking ? ChatColor.GREEN + "AKTIV" : ChatColor.RED + "AUS"));
        sender.sendMessage(ChatColor.GRAY + "│ Warteschlange: " + ChatColor.WHITE + store.queuedCount()
                + ChatColor.GRAY + " Ereignisse warten auf Speicherung");
        sender.sendMessage((dropped == 0 ? ChatColor.GRAY : ChatColor.RED) + "│ Verworfen: "
                + dropped + (dropped == 0 ? "" : " (Queue war voll!)"));
        sender.sendMessage(ChatColor.GRAY + "│ Datenbank: " + ChatColor.WHITE
                + config.getString("storage.database", "blockprotect.db"));
        sender.sendMessage(ChatColor.GRAY + "│ Module: " + ChatColor.WHITE + moduleSummary());
        sender.sendMessage(ChatColor.GOLD + "╰─ /bp gui öffnet das übersichtliche Menü.");
        return true;
    }

    private boolean gui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Die GUI ist nur ingame verfügbar.");
            return true;
        }
        if (!has(sender, "blockprotect.lookup")) {
            return true;
        }
        openMainGui(player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner().equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        switch (holder.screen()) {
            case MAIN -> handleMainClick(player, slot);
            case STATUS -> handleStatusClick(player, slot);
            case RESULTS -> handleResultsClick(player, holder, slot);
            case DETAIL -> handleDetailClick(player, holder, slot);
            case FILTER -> handleFilterClick(player, holder, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    private void openGuiNextTick(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 20 -> {
                if (has(player, "blockprotect.lookup")) {
                    startDefaultGuiLookup(player);
                }
            }
            case 22 -> {
                if (!has(player, "blockprotect.inspect")) {
                    return;
                }
                boolean active = inspectionState.toggle(player.getUniqueId());
                if (!active) {
                    auditViews.remove(player.getUniqueId());
                    auditQueries.remove(player.getUniqueId());
                    inspectFilters.remove(player.getUniqueId());
                }
                player.closeInventory();
                player.sendMessage(active
                        ? ChatColor.GREEN + "Inspect ist aktiv. Rechtsklick oder simuliertes Platzieren öffnet jetzt die Ereignis-GUI."
                        : ChatColor.YELLOW + "Inspect ist deaktiviert.");
            }
            case 24 -> {
                if (has(player, "blockprotect.lookup")) {
                    openStatusGui(player);
                }
            }
            case 30 -> {
                if (has(player, "blockprotect.rollback")) {
                    player.closeInventory();
                    player.performCommand("bp rollback");
                }
            }
            case 32 -> {
                if (has(player, "blockprotect.rollback")) {
                    player.closeInventory();
                    player.performCommand("bp restore");
                }
            }
            case 49 -> player.closeInventory();
            default -> {
                // Decorative slots intentionally do nothing.
            }
        }
    }

    private void handleStatusClick(Player player, int slot) {
        if (slot == 49) {
            openMainGui(player);
        }
    }

    private void handleResultsClick(Player player, GuiHolder holder, int slot) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null || !view.mode().equals(holder.mode())) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "Diese Abfrage ist nicht mehr verfügbar. Bitte erneut starten.");
            return;
        }
        if (slot == 45) {
            openGuiNextTick(player, () -> openAuditGui(player, view, holder.page() - 1));
            return;
        }
        if (slot == 47) {
            openGuiNextTick(player, () -> openFilterGui(player, holder));
            return;
        }
        if (slot == 49) {
            openGuiNextTick(player, () -> openMainGui(player));
            return;
        }
        if (slot == 51) {
            if (holder.mode().equals("inspect") && view.location() != null) {
                inspectLocation(player, view.location());
            } else {
                AuditQuery query = auditQueries.get(player.getUniqueId());
                if (query != null) {
                    runGuiQuery(player, view.mode(), view.context(), view.location(), view.filter(), query);
                }
            }
            return;
        }
        if (slot == 53) {
            openGuiNextTick(player, () -> openAuditGui(player, view, holder.page() + 1));
            return;
        }
        for (int index = 0; index < GUI_RESULT_SLOTS.length; index++) {
            if (GUI_RESULT_SLOTS[index] == slot) {
                int recordIndex = holder.page() * GUI_PAGE_SIZE + index;
                if (recordIndex < view.records().size()) {
                    openDetailGui(player, view.records().get(recordIndex), holder.page());
                }
                return;
            }
        }
    }

    private void handleDetailClick(Player player, GuiHolder holder, int slot) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (slot == 45 && view != null) {
            openGuiNextTick(player, () -> openAuditGui(player, view, holder.page()));
        } else if (slot == 47 && view != null) {
            int index = view.records().indexOf(holder.record());
            if (index > 0) {
                AuditRecord previous = view.records().get(index - 1);
                openGuiNextTick(player, () -> openDetailGui(player, previous, holder.page()));
            }
        } else if (slot == 51 && view != null) {
            int index = view.records().indexOf(holder.record());
            if (index >= 0 && index + 1 < view.records().size()) {
                AuditRecord next = view.records().get(index + 1);
                openGuiNextTick(player, () -> openDetailGui(player, next, holder.page()));
            }
        } else if (slot == 49) {
            openGuiNextTick(player, () -> openMainGui(player));
        } else if (slot == 53) {
            player.closeInventory();
        }
    }

    private void handleFilterClick(Player player, GuiHolder holder, int slot) {
        AuditView view = auditViews.get(player.getUniqueId());
        AuditQuery base = auditQueries.get(player.getUniqueId());
        if (view == null || base == null) {
            openMainGui(player);
            return;
        }
        if (slot == 49) {
            openGuiNextTick(player, () -> openMainGui(player));
            return;
        }
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        if (slot == 8) {
            LookupFilters filters = LookupFilters.empty();
            if (view.mode().equals("inspect")) {
                inspectFilters.put(player.getUniqueId(), filters);
            }
            AuditQuery query = withFilters(base, filters);
            runGuiQuery(player, view.mode(), view.context(), view.location(), filters, query);
            return;
        }
        if (slot == 45) {
            openGuiNextTick(player, () -> openAuditGui(player, view, holder.page()));
            return;
        }
        String action = null;
        String source = null;
        boolean selected = false;
        switch (slot) {
            case 19 -> {
                // Alle Ereignisse.
                selected = true;
            }
            case 20 -> action = "BLOCK_BREAK";
            case 21 -> action = "BLOCK_PLACE";
            case 22 -> action = "ENTITY_DEATH";
            case 23 -> action = "ENTITY_DAMAGE";
            case 24 -> action = "ENTITY_SPAWN";
            case 25 -> action = "ENTITY_EXPLODE";
            case 28 -> action = "CONTAINER_ITEM_CHANGE";
            case 29 -> action = "CONTAINER_OPEN";
            case 30 -> action = "EXPLOSION_BREAK";
            case 31 -> action = "BLOCK_FLOW";
            case 32 -> action = "CHAT";
            case 33 -> action = "COMMAND";
            case 34 -> action = "INVENTORY_CLICK";
            case 37 -> source = "blocks";
            case 38 -> source = "entities";
            case 39 -> source = "containers";
            case 40 -> source = "inventories";
            case 41 -> source = "environment";
            case 42 -> source = "interactions";
            case 43 -> source = "sessions";
            default -> selected = false;
        }
        selected = selected || action != null || source != null;
        if (!selected) {
            return;
        }
        LookupFilters filters = new LookupFilters(view.filter().actorName(), action, source,
                view.filter().target(), view.filter().sinceTimestamp(),
                view.filter().untilTimestamp(), view.filter().timeDescription());
        if (view.mode().equals("inspect")) {
            inspectFilters.put(player.getUniqueId(), filters);
        }
        AuditQuery query = withFilters(base, filters);
        runGuiQuery(player, view.mode(), view.context(), view.location(), filters, query);
    }

    private void startDefaultGuiLookup(Player player) {
        int radius = config.getInt("lookup.default-radius", 10);
        int limit = Math.max(1, Math.min(config.getInt("lookup.max-limit", 500),
                config.getInt("lookup.default-limit", 50)));
        Location location = player.getLocation();
        AuditQuery query = new AuditQuery(
                location.getWorld().getName(),
                location.getBlockX() - radius, location.getBlockX() + radius,
                location.getBlockY() - radius, location.getBlockY() + radius,
                location.getBlockZ() - radius, location.getBlockZ() + radius,
                null, null, null, null, limit
        );
        runGuiQuery(player, "lookup", "Radius=" + radius + " um deine Position", null,
                LookupFilters.empty(), query);
    }

    private void runGuiQuery(Player player, String mode, String context, Location location,
                             LookupFilters filters, AuditQuery query) {
        player.closeInventory();
        player.sendMessage(Component.text("BlockProtect "
                + (mode.equals("inspect") ? "Inspect" : "Lookup")
                + " wird geladen …", NamedTextColor.GRAY));
        store.query(query).whenComplete((records, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                player.sendMessage(Component.text("Abfrage fehlgeschlagen: " + rootMessage(throwable), NamedTextColor.RED));
                return;
            }
            AuditView view = new AuditView(mode, context, location == null ? null : location.clone(),
                    filters, records, 0);
            auditViews.put(player.getUniqueId(), view);
            auditQueries.put(player.getUniqueId(), query);
            openAuditGui(player, view, 0);
        }));
    }

    private void openMainGui(Player player) {
        GuiHolder holder = new GuiHolder(player.getUniqueId(), GuiScreen.MAIN, null, 0, null);
        Inventory inventory = createGui(holder, "BlockProtect • Start");
        fillGui(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(4, guiItem(Material.COMPASS, "Was möchtest du prüfen?", NamedTextColor.GOLD,
                "BlockProtect speichert Ereignisse", "von Spielern, Entities und der Welt.",
                "Klicke eine Funktion an."));
        inventory.setItem(20, guiItem(Material.COMPASS, "1. Umgebung durchsuchen", NamedTextColor.AQUA,
                "Zeigt die letzten Ereignisse in deiner Nähe.",
                "Radius: " + config.getInt("lookup.default-radius", 10) + " Blöcke",
                "Klick zum Starten"));
        inventory.setItem(22, guiItem(Material.SPYGLASS, "2. Einen Block prüfen", NamedTextColor.LIGHT_PURPLE,
                inspectionState.isActive(player.getUniqueId()) ? "Status: AKTIV" : "Status: AUS",
                "Aktivieren, dann rechts auf einen Block klicken.",
                "Die eigentliche Weltänderung wird dabei verhindert."));
        inventory.setItem(24, guiItem(Material.BOOK, "3. Systemstatus", NamedTextColor.GREEN,
                "Ist die Aufzeichnung aktiv?", "Wie viele Ereignisse warten?",
                "Klick für Details"));
        inventory.setItem(30, guiItem(Material.REPEATER, "Rollback (Vorsicht)", NamedTextColor.RED,
                "Zeigt wichtige Änderungen an.", "Nur bestätigen, wenn du sicher bist."));
        inventory.setItem(32, guiItem(Material.CLOCK, "Letzten Rollback zurücknehmen", NamedTextColor.YELLOW,
                "Stellt den letzten Rollback wieder her."));
        inventory.setItem(49, guiItem(Material.BARRIER, "Schließen", NamedTextColor.RED,
                "GUI schließen"));
        player.openInventory(inventory);
    }

    private void openStatusGui(Player player) {
        GuiHolder holder = new GuiHolder(player.getUniqueId(), GuiScreen.STATUS, null, 0, null);
        Inventory inventory = createGui(holder, "BlockProtect • Status");
        fillGui(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(4, guiItem(Material.BOOK, "Systemstatus", NamedTextColor.GOLD,
                "Live-Übersicht des Plugins"));
        inventory.setItem(20, guiItem(
                config.getBoolean("tracking.enabled", true) ? Material.LIME_DYE : Material.RED_DYE,
                "Tracking", config.getBoolean("tracking.enabled", true) ? NamedTextColor.GREEN : NamedTextColor.RED,
                config.getBoolean("tracking.enabled", true) ? "Aktiv" : "Deaktiviert"));
        inventory.setItem(22, guiItem(Material.HOPPER, "Audit-Queue", NamedTextColor.AQUA,
                "Wartend: " + store.queuedCount(), "Verworfen: " + store.droppedCount()));
        inventory.setItem(24, guiItem(Material.CHEST, "Datenbank", NamedTextColor.AQUA,
                config.getString("storage.database", "blockprotect.db"), "SQLite / WAL"));
        inventory.setItem(30, guiItem(Material.REDSTONE, "Module", NamedTextColor.YELLOW,
                wrapText(moduleSummary(), 38)));
        inventory.setItem(32, guiItem(Material.PAPER, "Einstellungen", NamedTextColor.GRAY,
                "Änderungen sind live möglich", "/bp config set …"));
        inventory.setItem(49, guiItem(Material.ARROW, "Zurück", NamedTextColor.AQUA,
                "Zum Hauptmenü"));
        player.openInventory(inventory);
    }

    private Inventory createGui(GuiHolder holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(title, NamedTextColor.DARK_AQUA));
        holder.setInventory(inventory);
        return inventory;
    }

    private static void fillGui(Inventory inventory, Material material) {
        ItemStack pane = guiItem(material, " ", NamedTextColor.GRAY);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane.clone());
        }
    }

    private static ItemStack guiItem(Material material, String name, NamedTextColor color, String... lore) {
        return guiItem(material, name, color, Arrays.asList(lore));
    }

    private static ItemStack guiItem(Material material, String name, NamedTextColor color, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
                .flatMap(line -> wrapText(line, 42).stream())
                .map(line -> Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private void openAuditGui(Player player, AuditView view, int requestedPage) {
        int pages = Math.max(1, (view.records().size() + GUI_PAGE_SIZE - 1) / GUI_PAGE_SIZE);
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        AuditView current = new AuditView(view.mode(), view.context(), view.location(), view.filter(),
                view.records(), page);
        auditViews.put(player.getUniqueId(), current);

        GuiHolder holder = new GuiHolder(player.getUniqueId(), GuiScreen.RESULTS, current.mode(), page, null);
        Inventory inventory = createGui(holder, "BlockProtect • "
                + (current.mode().equals("inspect") ? "Inspect" : "Lookup"));
        fillGui(inventory, Material.BLACK_STAINED_GLASS_PANE);

        String context = current.location() == null ? current.context() : locationText(current.location());
        String modeLabel = current.mode().equals("inspect") ? "Block-Prüfung" : "Umgebungs-Suche";
        inventory.setItem(4, guiItem(current.mode().equals("inspect") ? Material.SPYGLASS : Material.COMPASS,
                modeLabel,
                NamedTextColor.GOLD,
                context == null ? "" : context,
                current.records().size() + " Ereignisse insgesamt",
                current.filter().isEmpty() ? "Filter: alle Ereignisse" : "Filter: " + current.filter().describe(),
                "Neueste Ereignisse stehen zuerst."));

        if (current.records().isEmpty()) {
            inventory.setItem(22, guiItem(Material.BARRIER, "Keine Ereignisse", NamedTextColor.GRAY,
                    current.mode().equals("inspect")
                            ? "An diesem Block wurde nichts gefunden."
                            : "Im gewählten Bereich wurde nichts gefunden."));
        } else {
            int start = page * GUI_PAGE_SIZE;
            int end = Math.min(current.records().size(), start + GUI_PAGE_SIZE);
            for (int index = start; index < end; index++) {
                inventory.setItem(GUI_RESULT_SLOTS[index - start],
                        guiRecordItem(current.records().get(index), index + 1));
            }
        }

        inventory.setItem(45, page > 0
                ? guiItem(Material.ARROW, "Vorherige Seite", NamedTextColor.AQUA)
                : guiItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
        inventory.setItem(47, guiItem(Material.HOPPER, "Filter", NamedTextColor.YELLOW,
                current.filter().isEmpty() ? "Alle Ereignisse anzeigen" : current.filter().describe(),
                "Klick: Ereignistyp auswählen"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "Menü", NamedTextColor.AQUA,
                "Zurück zur Übersicht"));
        inventory.setItem(51, guiItem(Material.CLOCK, "Aktualisieren", NamedTextColor.GREEN,
                "Suche erneut ausführen"));
        inventory.setItem(53, page + 1 < pages
                ? guiItem(Material.ARROW, "Nächste Seite", NamedTextColor.AQUA,
                "Seite " + (page + 2) + " öffnen")
                : guiItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
        inventory.setItem(4, addPageLore(inventory.getItem(4), page, pages));
        player.openInventory(inventory);
    }

    private ItemStack guiRecordItem(AuditRecord record, int number) {
        List<String> lore = new ArrayList<>(List.of(
                "Zeit: " + detailTime(record.timestamp()),
                "Wer / Ursache: " + actorLabel(record),
                "Ziel: " + targetLabel(record),
                "Ort: " + locationText(record)
        ));
        String item = itemValue(record);
        if (item != null) {
            lore.add("Item: " + displayItemState(item));
        }
        String drops = detailValue(record.details(), "drops");
        if (record.action().equals("ENTITY_DEATH") && drops != null && !drops.isBlank() && !drops.equals("-")) {
            lore.add("Drops: " + displayItemList(drops));
        }
        lore.add("Klick: vollständige Details");
        return guiItem(materialForAction(record.action()), "#" + number + " · " + actionLabel(record),
                actionColor(record.action()), lore);
    }

    private static ItemStack addPageLore(ItemStack original, int page, int pages) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = meta.lore();
        List<net.kyori.adventure.text.Component> updated = new ArrayList<>();
        if (lore != null) {
            updated.addAll(lore);
        }
        updated.add(Component.text("Seite " + (page + 1) + " von " + pages, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(updated);
        copy.setItemMeta(meta);
        return copy;
    }

    private void openDetailGui(Player player, AuditRecord record, int page) {
        GuiHolder holder = new GuiHolder(player.getUniqueId(), GuiScreen.DETAIL, null, page, record);
        Inventory inventory = createGui(holder, "BlockProtect • Ereignisdetails");
        fillGui(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(4, guiItem(materialForAction(record.action()), "Ereignisdetails: " + actionLabel(record),
                actionColor(record.action()), "Alle Informationen zu diesem Eintrag"));
        inventory.setItem(10, guiItem(Material.CLOCK, "Wann", NamedTextColor.AQUA,
                detailTime(record.timestamp())));
        inventory.setItem(12, guiItem(Material.PLAYER_HEAD, "Wer", NamedTextColor.WHITE,
                actorLabel(record), record.actorUuid() == null ? "Quelle: Umgebung/Server" : "Spieleraktion"));
        inventory.setItem(14, guiItem(Material.REDSTONE, "Was", actionColor(record.action()),
                actionLabel(record), "Aktion: " + valueOrQuestion(record.action())));
        inventory.setItem(16, guiItem(Material.CHEST, "Ziel", NamedTextColor.YELLOW,
                targetLabel(record), "Typ: " + valueOrQuestion(record.target())));
        inventory.setItem(28, guiItem(Material.MAP, "Ort", NamedTextColor.GREEN,
                locationText(record)));
        inventory.setItem(30, guiItem(Material.PAPER, "Warum", NamedTextColor.LIGHT_PURPLE,
                wrapText(reasonText(record), 38)));
        inventory.setItem(32, guiItem(Material.CHEST, "Änderung", NamedTextColor.GOLD,
                wrapText(changeText(record), 38)));
        inventory.setItem(34, guiItem(Material.BOOK, "Kontext", NamedTextColor.GRAY,
                wrapText(rawContext(record), 38)));

        AuditView view = auditViews.get(player.getUniqueId());
        int index = view == null ? -1 : view.records().indexOf(record);
        inventory.setItem(45, guiItem(Material.ARROW, "Zurück zu den Ergebnissen", NamedTextColor.AQUA,
                "Seite " + (page + 1)));
        inventory.setItem(47, index > 0
                ? guiItem(Material.ARROW, "Vorheriges Ereignis", NamedTextColor.AQUA)
                : guiItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "Menü", NamedTextColor.AQUA));
        inventory.setItem(51, view != null && index + 1 < view.records().size()
                ? guiItem(Material.ARROW, "Nächstes Ereignis", NamedTextColor.AQUA)
                : guiItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
        inventory.setItem(53, guiItem(Material.BARRIER, "Schließen", NamedTextColor.RED));
        player.openInventory(inventory);
    }

    private void openFilterGui(Player player) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null) {
            openMainGui(player);
            return;
        }
        GuiHolder source = new GuiHolder(player.getUniqueId(), GuiScreen.RESULTS, view.mode(), view.page(), null);
        openFilterGui(player, source);
    }

    private void openFilterGui(Player player, GuiHolder source) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null) {
            openMainGui(player);
            return;
        }
        GuiHolder holder = new GuiHolder(player.getUniqueId(), GuiScreen.FILTER, view.mode(), source.page(), null);
        Inventory inventory = createGui(holder, "BlockProtect • Filter auswählen");
        fillGui(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(4, guiItem(Material.BOOK, "Aktive Filter", NamedTextColor.GOLD,
                "Klicke eine Ereignisart oder eine Quelle an.",
                "Aktuell: " + view.filter().describe(),
                "Spieler/Ziel/Zeit: /bp lookup help"));
        inventory.setItem(8, guiItem(Material.BARRIER, "Alle Filter zurücksetzen", NamedTextColor.RED,
                "Entfernt auch Spieler-, Ziel- und Zeitfilter.",
                "Klick: wirklich alle Filter löschen"));
        inventory.setItem(13, guiItem(Material.WRITABLE_BOOK, "So funktioniert es", NamedTextColor.WHITE,
                "Oben: einzelne Ereignisarten auswählen.",
                "Unten: komplette Quellen auswählen.",
                "Die Suche startet nach einem Klick automatisch."));
        // Sieben Einträge pro Reihe: Spalten 2 bis 8 sind im 9er-Inventar exakt mittig.
        inventory.setItem(19, filterItem(Material.NETHER_STAR, "Alle Ereignisse", null, null, view));
        inventory.setItem(20, filterItem(Material.IRON_PICKAXE, "Block-Abbau", "BLOCK_BREAK", null, view));
        inventory.setItem(21, filterItem(Material.GRASS_BLOCK, "Block-Platzierung", "BLOCK_PLACE", null, view));
        inventory.setItem(22, filterItem(Material.SKELETON_SKULL, "Entity-Tode", "ENTITY_DEATH", null, view));
        inventory.setItem(23, filterItem(Material.IRON_SWORD, "Entity-Schaden", "ENTITY_DAMAGE", null, view));
        inventory.setItem(24, filterItem(Material.ZOMBIE_SPAWN_EGG, "Entity-Spawns", "ENTITY_SPAWN", null, view));
        inventory.setItem(25, filterItem(Material.TNT, "Entity-Explosionen", "ENTITY_EXPLODE", null, view));
        inventory.setItem(28, filterItem(Material.CHEST, "Container-Items", "CONTAINER_ITEM_CHANGE", null, view));
        inventory.setItem(29, filterItem(Material.HOPPER, "Container öffnen", "CONTAINER_OPEN", null, view));
        inventory.setItem(30, filterItem(Material.TNT, "Block-Explosionen", "EXPLOSION_BREAK", null, view));
        inventory.setItem(31, filterItem(Material.WATER_BUCKET, "Wasser/Lava-Fluss", "BLOCK_FLOW", null, view));
        inventory.setItem(32, filterItem(Material.PAPER, "Chat", "CHAT", null, view));
        inventory.setItem(33, filterItem(Material.COMMAND_BLOCK, "Befehle", "COMMAND", null, view));
        inventory.setItem(34, filterItem(Material.CHEST_MINECART, "Inventar-Klicks", "INVENTORY_CLICK", null, view));
        inventory.setItem(37, filterItem(Material.GRASS_BLOCK, "Quelle: Blöcke", null, "blocks", view));
        inventory.setItem(38, filterItem(Material.ZOMBIE_HEAD, "Quelle: Entities", null, "entities", view));
        inventory.setItem(39, filterItem(Material.CHEST, "Quelle: Container", null, "containers", view));
        inventory.setItem(40, filterItem(Material.CHEST_MINECART, "Quelle: Inventare", null, "inventories", view));
        inventory.setItem(41, filterItem(Material.DIRT, "Quelle: Welt", null, "environment", view));
        inventory.setItem(42, filterItem(Material.LEVER, "Quelle: Interaktionen", null, "interactions", view));
        inventory.setItem(43, filterItem(Material.PLAYER_HEAD, "Quelle: Spieler-Sessions", null, "sessions", view));
        inventory.setItem(45, guiItem(Material.ARROW, "Zurück zu den Ergebnissen", NamedTextColor.AQUA,
                "Ohne Änderung zurück zur letzten Ergebnisliste"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "Hauptmenü", NamedTextColor.AQUA,
                "Zur BlockProtect-Übersicht"));
        inventory.setItem(53, guiItem(Material.BARRIER, "Schließen", NamedTextColor.RED));
        player.openInventory(inventory);
    }

    private static ItemStack filterItem(Material material, String name, String action,
                                        String source, AuditView view) {
        boolean active = Objects.equals(view.filter().action(), action)
                && Objects.equals(view.filter().source(), source);
        ItemStack item = guiItem(material, (active ? "✓ " : "") + name,
                active ? NamedTextColor.GREEN : NamedTextColor.AQUA,
                active ? "Aktiv" : "Klick zum Anwenden",
                action == null && source == null
                        ? "Ereignis-/Quellenfilter entfernen (andere Filter bleiben)"
                        : "Filter anwenden");
        if (active) {
            ItemMeta meta = item.getItemMeta();
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static AuditQuery withFilters(AuditQuery base, LookupFilters filters) {
        return new AuditQuery(base.world(), base.minX(), base.maxX(), base.minY(), base.maxY(),
                base.minZ(), base.maxZ(), filters.actorName(), filters.action(), filters.source(),
                filters.target(), filters.sinceTimestamp(), filters.untilTimestamp(), base.limit());
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Unterbefehl ist nur ingame verfügbar.");
            return true;
        }
        if (!has(sender, "blockprotect.inspect")) {
            return true;
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("help") || args[1].equalsIgnoreCase("?"))) {
            inspectHelp(player);
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("filter")) {
            return inspectFilter(player, args);
        }
        if (args.length > 1) {
            return inspectPage(player, args);
        }
        if (!inspectionState.toggle(player.getUniqueId())) {
            auditViews.remove(player.getUniqueId());
            auditQueries.remove(player.getUniqueId());
            inspectFilters.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "BlockProtect Inspect ist deaktiviert.");
        } else {
            player.sendMessage(ChatColor.GREEN + "BlockProtect Inspect ist aktiv. Rechtsklick auf einen Block oder platziere einen Block, um die Luftposition zu prüfen. Die Platzierung wird verhindert.");
        }
        return true;
    }

    private void inspectHelp(Player player) {
        player.sendMessage(Component.text("╭─ Inspect · Block prüfen", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ /bp inspect", NamedTextColor.AQUA)
                .append(Component.text("  – Inspect an-/ausschalten", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("│ Danach rechtsklickst du einen Block.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("│ Die Aktion wird dabei nicht ausgeführt.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("│ Filter: /bp inspect filter player Steve", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("│ Löschen: /bp inspect filter clear", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("╰─ Seiten: /bp inspect next · /bp inspect prev", NamedTextColor.DARK_GRAY));
    }

    private boolean inspectPage(Player player, String[] args) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null || !view.mode().equals("inspect")) {
            player.sendMessage(ChatColor.GRAY + "Klicke zuerst einen Block im Inspect-Modus an.");
            return true;
        }
        int page = view.page();
        if (args[1].equalsIgnoreCase("next")) {
            page++;
        } else if (args[1].equalsIgnoreCase("prev")) {
            page--;
        } else if (args[1].equalsIgnoreCase("page") && args.length > 2) {
            Integer requested = integer(args[2]);
            if (requested == null) {
                player.sendMessage(ChatColor.RED + "Die Seitennummer muss eine Zahl sein.");
                return true;
            }
            page = requested - 1;
        } else {
            player.sendMessage(ChatColor.YELLOW + "Verwendung: /bp inspect [next|prev|page <nummer>]");
            return true;
        }
        showInspectPage(player, page);
        return true;
    }

    private boolean lookupPage(Player player, String[] args) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null || !view.mode().equals("lookup")) {
            player.sendMessage(ChatColor.GRAY + "Führe zuerst einen Lookup aus.");
            return true;
        }
        int page = view.page();
        if (args[1].equalsIgnoreCase("next")) {
            page++;
        } else if (args[1].equalsIgnoreCase("prev")) {
            page--;
        } else if (args[1].equalsIgnoreCase("page") && args.length > 2) {
            Integer requested = integer(args[2]);
            if (requested == null) {
                player.sendMessage(ChatColor.RED + "Die Seitennummer muss eine Zahl sein.");
                return true;
            }
            page = requested - 1;
        } else {
            player.sendMessage(ChatColor.YELLOW + "Verwendung: /bp lookup [next|prev|page <nummer>]");
            return true;
        }
        showLookupPage(player, page);
        return true;
    }

    private boolean inspectFilter(Player player, String[] args) {
        if (args.length < 3 || args[2].equalsIgnoreCase("clear")) {
            inspectFilters.remove(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "Inspect-Filter gelöscht.");
            AuditView view = auditViews.get(player.getUniqueId());
            if (view != null && view.mode().equals("inspect")) {
                inspectLocation(player, view.location());
            }
            return true;
        }

        LookupFilters current = inspectFilters.getOrDefault(player.getUniqueId(), LookupFilters.empty());
        try {
            LookupFilters filter = parseFilters(args, 2, current.actorName(), current.action(),
                    current.source(), current.target());
            inspectFilters.put(player.getUniqueId(), filter);
            player.sendMessage(ChatColor.GREEN + "Inspect-Filter: " + filter.describe());
            AuditView view = auditViews.get(player.getUniqueId());
            if (view != null && view.mode().equals("inspect")) {
                inspectLocation(player, view.location());
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
        }
        return true;
    }

    private void showInspectPage(Player player, int requestedPage) {
        showAuditPage(player, requestedPage, "inspect");
    }

    private void showLookupPage(Player player, int requestedPage) {
        showAuditPage(player, requestedPage, "lookup");
    }

    private void showAuditPage(Player player, int requestedPage, String mode) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null || !view.mode().equals(mode)) {
            return;
        }
        int pages = Math.max(1, (view.records().size() + INSPECT_PAGE_SIZE - 1) / INSPECT_PAGE_SIZE);
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        auditViews.put(player.getUniqueId(), new AuditView(
                view.mode(), view.context(), view.location(), view.filter(), view.records(), page));

        String context = view.location() == null ? view.context() : locationText(view.location());
        String title = mode.equals("inspect") ? "BlockProtect Inspect" : "BlockProtect Lookup";
        String command = mode.equals("inspect") ? "/bp inspect " : "/bp lookup ";
        player.sendMessage(Component.text("╭─ " + title + " ─────────────────", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ Bereich: " + valueOrQuestion(context), NamedTextColor.GRAY));
        player.sendMessage(Component.text("│ Ergebnis: " + view.records().size()
                + " Einträge  ·  neueste zuerst", NamedTextColor.GRAY));
        if (!view.filter().isEmpty()) {
            player.sendMessage(Component.text("│ Filter: " + view.filter().describe(), NamedTextColor.YELLOW));
        }

        if (view.records().isEmpty()) {
            player.sendMessage(Component.text("│ Keine Ereignisse gefunden. Prüfe Radius oder Filter.", NamedTextColor.GRAY));
        } else {
            int start = page * INSPECT_PAGE_SIZE;
            int end = Math.min(view.records().size(), start + INSPECT_PAGE_SIZE);
            for (int index = start; index < end; index++) {
                sendChatAuditEntry(player, view.records().get(index), index + 1);
            }
        }

        Component navigation = Component.text("╰─ Seite " + (page + 1) + "/" + pages + "  ", NamedTextColor.GRAY);
        if (page > 0) {
            navigation = navigation.append(Component.text("◀ zurück", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(command + "prev")));
        }
        if (page > 0 && page + 1 < pages) {
            navigation = navigation.append(Component.text("  ", NamedTextColor.GRAY));
        }
        if (page + 1 < pages) {
            navigation = navigation.append(Component.text("weiter ▶", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(command + "next")));
        }
        player.sendMessage(navigation);
        if (mode.equals("lookup")) {
            player.sendMessage(Component.text("│ Aktionen: ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Filter öffnen", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/bp lookup filter")))
                    .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Aktualisieren", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/bp lookup refresh")))
                    .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Lookup-Hilfe", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/bp lookup help"))));
        }
    }

    private static String locationText(Location location) {
        return location.getWorld().getName() + " @ "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private void sendChatAuditEntry(Player player, AuditRecord record, int number) {
        player.sendMessage(Component.text(" "));
        Component heading = Component.text("╭─ #" + number + " · " + detailTime(record.timestamp()) + " · ",
                        NamedTextColor.DARK_GRAY)
                .append(Component.text(actionLabel(record), actionColor(record.action())))
                .append(Component.text(" · " + targetLabel(record), NamedTextColor.YELLOW))
                .hoverEvent(HoverEvent.showText(Component.text(rawContext(record), NamedTextColor.GRAY)));
        player.sendMessage(heading);
        player.sendMessage(labeledLine("Wer", actorLabel(record), NamedTextColor.WHITE)
                .append(Component.text("   ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Was: ", NamedTextColor.GRAY))
                .append(Component.text(actionLabel(record), actionColor(record.action()))));
        player.sendMessage(labeledLine("Ziel", targetLabel(record), NamedTextColor.YELLOW)
                .append(Component.text("   ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Ort: ", NamedTextColor.GRAY))
                .append(Component.text(locationText(record), NamedTextColor.WHITE)));
        player.sendMessage(labeledLine("Warum", reasonText(record), NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(labeledLine("Änderung", changeText(record), NamedTextColor.GOLD));
    }

    private static Component labeledLine(String label, String value, NamedTextColor valueColor) {
        return Component.text("│ " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(valueOrQuestion(value), valueColor));
    }

    private static String actorLabel(AuditRecord record) {
        if (record.actorName() != null && !record.actorName().isBlank()) {
            return record.actorName();
        }
        String causing = readableEntityReference(detailValue(record.details(), "causing-entity"));
        if (causing != null) {
            return causing;
        }
        String source = readableEntityReference(detailValue(record.details(), "source"));
        if (source != null && record.action().startsWith("ENTITY_")) {
            return source;
        }
        return "Umgebung / Server";
    }

    private static String targetLabel(AuditRecord record) {
        if (record.action().equals("CONTAINER_ITEM_CHANGE")
                || record.action().equals("CONTAINER_SNAPSHOT")) {
            return containerChangeTarget(record);
        }
        return shortTarget(record.target());
    }

    private static String locationText(AuditRecord record) {
        if (record.world() == null || record.x() == null || record.y() == null || record.z() == null) {
            return "unbekannter Ort";
        }
        return record.world() + " @ " + record.x() + "," + record.y() + "," + record.z();
    }

    private static String detailTime(long timestamp) {
        return DETAIL_TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String actionLabel(AuditRecord record) {
        return switch (record.action()) {
            case "BLOCK_BREAK" -> "Block abgebaut";
            case "BLOCK_PLACE" -> "Block platziert";
            case "CONTAINER_OPEN" -> "Container geöffnet";
            case "CONTAINER_ITEM_CHANGE" -> "Container: " + containerChangeAction(record).toLowerCase(Locale.ROOT);
            case "CONTAINER_SNAPSHOT" -> "Containerinhalt gesichert";
            case "INVENTORY_CLICK" -> "Inventar benutzt";
            case "INVENTORY_DRAG" -> "Items verschoben";
            case "INVENTORY_MOVE" -> "Items automatisch verschoben";
            case "INVENTORY_PICKUP" -> "Item in Container gelegt";
            case "FURNACE_EXTRACT" -> "Ofen-Ausgabe genommen";
            case "CRAFT" -> "Item hergestellt";
            case "BREW" -> "Trank gebraut";
            case "DISPENSE" -> "Spender ausgelöst";
            case "BLOCK_FLOW" -> "Wasser/Lava geflossen";
            case "BLOCK_BURN" -> "Block durch Feuer verändert";
            case "BLOCK_FADE" -> "Block natürlich verblasst";
            case "BLOCK_FORM" -> "Block gebildet";
            case "BLOCK_GROW" -> "Wachstum";
            case "BLOCK_SPREAD" -> "Blockausbreitung";
            case "BLOCK_IGNITE" -> "Block entzündet";
            case "TNT_PRIME" -> "TNT gezündet";
            case "BLOCK_FERTILIZE" -> "Block gedüngt";
            case "LEAVES_DECAY" -> "Blätter verfallen";
            case "MOISTURE_CHANGE" -> "Feuchtigkeit geändert";
            case "CAULDRON_CHANGE" -> "Kessel geändert";
            case "SPONGE_ABSORB" -> "Schwamm saugt Wasser";
            case "FLUID_LEVEL_CHANGE" -> "Flüssigkeitsstand geändert";
            case "BLOCK_COOK" -> "Ofen verarbeitet Item";
            case "CRAFTER_CRAFT" -> "Crafter stellt Item her";
            case "ENTITY_BLOCK_FORM" -> "Entity bildet Block";
            case "EXPLOSION_BREAK" -> "Block durch Explosion zerstört";
            case "BUCKET_EMPTY" -> "Eimer entleert";
            case "BUCKET_FILL" -> "Eimer gefüllt";
            case "ENTITY_CHANGE_BLOCK" -> "Entity hat Block verändert";
            case "PISTON_EXTEND" -> "Kolben ausgefahren";
            case "PISTON_RETRACT" -> "Kolben eingefahren";
            case "ITEM_PICKUP" -> "Item aufgehoben";
            case "ITEM_DROP" -> "Item abgelegt";
            case "ENTITY_DAMAGE" -> "Entity beschädigt";
            case "ENTITY_DEATH" -> "Entity gestorben";
            case "ENTITY_SPAWN" -> "Entity erzeugt";
            case "ENTITY_REMOVE" -> "Entity entfernt";
            case "ENTITY_EXPLODE" -> "Entity-Explosion";
            case "ENTITY_TRANSFORM" -> "Entity verwandelt";
            case "ENTITY_TARGET" -> "Entity nimmt Ziel";
            case "ENTITY_BREED" -> "Entity-Nachwuchs";
            case "ENTITY_LOVE_MODE" -> "Paarungsmodus";
            case "ENTITY_MOUNT" -> "Entity aufgestiegen";
            case "ENTITY_DISMOUNT" -> "Entity abgestiegen";
            case "ENTITY_TELEPORT" -> "Entity teleportiert";
            case "ENTITY_ENTER_BLOCK" -> "Entity im Block";
            case "ENTITY_PLACE" -> "Entity platziert";
            case "ENTITY_DROP" -> "Entity droppt Item";
            case "ENTITY_COMBUST" -> "Entity brennt";
            case "ENTITY_POTION_EFFECT" -> "Effekt geändert";
            case "ENTITY_HEAL" -> "Entity geheilt";
            case "ENTITY_RESURRECT" -> "Entity wiederbelebt";
            case "ENTITY_SHOOT" -> "Entity schießt";
            case "ENTITY_SPELL" -> "Entity-Zauber";
            case "AREA_EFFECT_APPLY" -> "Flächeneffekt";
            case "ENTITY_UNLEASH" -> "Leine gelöst";
            case "ENTITY_BLOCK_INTERACT" -> "Entity nutzt Block";
            case "ITEM_DESPAWN" -> "Item verschwunden";
            case "ITEM_MERGE" -> "Items zusammengelegt";
            case "PROJECTILE_LAUNCH" -> "Projektil gestartet";
            case "PROJECTILE_HIT" -> "Projektil getroffen";
            case "ENTITY_TAME" -> "Entity gezähmt";
            case "FISHING" -> "Angeln";
            case "HANGING_PLACE" -> "Bild/Gegenstand aufgehängt";
            case "HANGING_BREAK" -> "Bild/Gegenstand abgenommen";
            case "LEASH", "UNLEASH" -> "Leine verändert";
            case "ARMOR_STAND_MANIPULATE" -> "Rüstungsständer verändert";
            case "BLOCK_DROP" -> "Block-Drop erzeugt";
            case "BLOCK_INTERACT" -> "Block benutzt";
            case "ENTITY_INTERACT" -> "Entity benutzt";
            case "ITEM_CONSUME" -> "Item verbraucht";
            case "SIGN_CHANGE" -> "Schild beschrieben";
            case "COMMAND" -> "Befehl ausgeführt";
            case "CHAT" -> "Chatnachricht";
            case "PLAYER_JOIN" -> "Spieler beigetreten";
            case "PLAYER_QUIT" -> "Spieler gegangen";
            case "PLAYER_KICK" -> "Spieler gekickt";
            default -> shortAction(record.action());
        };
    }

    private static String reasonText(AuditRecord record) {
        String action = record.action();
        return switch (action) {
            case "BLOCK_BREAK" -> withDetail("Spieleraktion: Block abgebaut", record, "tool", "Werkzeug");
            case "BLOCK_PLACE" -> withDetail("Spieleraktion: Block platziert", record, "hand", "Hand");
            case "CONTAINER_OPEN" -> "Spieler hat den Container geöffnet";
            case "CONTAINER_ITEM_CHANGE" -> "Slot " + valueOrQuestion(detailValue(record.details(), "slot"))
                    + " wurde beim Schließen des Containers verändert";
            case "CONTAINER_SNAPSHOT" -> "Inhalt vor dem Container-Abbau gesichert";
            case "BLOCK_FLOW" -> withDetail("Flüssigkeit ist in diesen Block geflossen", record, "from", "Quelle");
            case "BLOCK_BURN" -> "Feuer hat den Block verändert";
            case "BLOCK_FADE" -> "Natürlicher Welt-/Blockwechsel";
            case "BLOCK_FORM" -> "Der Block wurde durch ein Welt-Event gebildet";
            case "BLOCK_GROW" -> "Pflanzen- oder Weltwachstum";
            case "BLOCK_SPREAD" -> "Ausbreitung von einem Nachbarblock";
            case "EXPLOSION_BREAK" -> withDetail("Explosion hat den Block zerstört", record, "source", "Quelle");
            case "BUCKET_EMPTY" -> "Flüssigkeit wurde mit einem Eimer platziert";
            case "BUCKET_FILL" -> "Flüssigkeit wurde mit einem Eimer aufgenommen";
            case "ENTITY_CHANGE_BLOCK" -> withDetail("Entity hat den Block verändert", record, "entity", "Entity");
            case "PISTON_EXTEND", "PISTON_RETRACT" -> withDetail("Kolbenbewegung", record, "direction", "Richtung");
            case "ENTITY_DEATH" -> entityDeathReason(record);
            case "ENTITY_DAMAGE" -> entityDamageReason(record);
            case "ENTITY_SPAWN" -> withDetail("Entity wurde erzeugt", record, "reason", "Grund");
            case "ENTITY_REMOVE" -> withDetail("Entity wurde entfernt", record, "cause", "Grund");
            case "ENTITY_EXPLODE" -> withDetail("Entity hat eine Explosion ausgelöst", record, "blocks", "Blöcke");
            case "ENTITY_TRANSFORM" -> withDetail("Entity wurde umgewandelt", record, "reason", "Grund");
            case "ENTITY_TARGET" -> withDetail("Entity hat ein Ziel gewählt", record, "target", "Ziel");
            case "ENTITY_TELEPORT" -> "Entity wurde teleportiert";
            case "ENTITY_COMBUST" -> withDetail("Entity wurde entzündet", record, "duration-seconds", "Dauer (s)");
            case "ENTITY_POTION_EFFECT" -> withDetail("Potion-Effekt wurde geändert", record, "effect", "Effekt");
            case "ENTITY_HEAL" -> withDetail("Entity hat Leben zurückbekommen", record, "amount", "Menge");
            case "ENTITY_RESURRECT" -> "Entity wurde vor dem Tod bewahrt";
            case "ITEM_DESPAWN" -> withDetail("Item wurde entfernt", record, "reason", "Grund");
            default -> rawContext(record);
        };
    }

    private static String entityDeathReason(AuditRecord record) {
        String cause = readableKey(detailValue(record.details(), "cause"));
        String killer = readableEntityReference(detailValue(record.details(), "causing-entity"));
        String result = "Entity ist gestorben";
        if (cause != null) {
            result += " · Ursache: " + cause;
        }
        if (killer != null) {
            result += " · Verursacher: " + killer;
        }
        return result;
    }

    private static String entityDamageReason(AuditRecord record) {
        String cause = readableKey(detailValue(record.details(), "damage-type"));
        String source = readableEntityReference(detailValue(record.details(), "causing-entity"));
        String result = "Entity wurde beschädigt";
        if (cause != null) {
            result += " · Art: " + cause;
        }
        if (source != null) {
            result += " · Verursacher: " + source;
        }
        String damage = detailValue(record.details(), "damage");
        if (damage != null && !damage.equalsIgnoreCase("null")) {
            result += " · Schaden: " + damage;
        }
        return result;
    }

    private static String withDetail(String base, AuditRecord record, String key, String label) {
        String value = detailValue(record.details(), key);
        if (value == null || value.isBlank()) {
            return base;
        }
        if (isItemDetailKey(key)) {
            value = displayItemState(value);
        } else if (key.equals("cause") || key.equals("reason") || key.equals("damage-type")) {
            value = valueOrQuestion(readableKey(value));
        } else if (key.equals("entity") || key.equals("source") || key.equals("causing-entity")
                || key.equals("direct-entity")) {
            value = valueOrQuestion(readableEntityReference(value));
        }
        return base + " · " + label + ": " + value;
    }

    private static String changeText(AuditRecord record) {
        if (record.action().equals("CONTAINER_ITEM_CHANGE")
                || record.action().equals("CONTAINER_SNAPSHOT")) {
            return containerChangeTarget(record);
        }
        if (record.action().equals("ENTITY_DEATH")) {
            String result = "Drops: " + valueOrQuestion(detailValue(record.details(), "drop-count"))
                    + " · XP: " + record.amount();
            String drops = detailValue(record.details(), "drops");
            if (drops != null && !drops.isBlank() && !drops.equals("-")) {
                result += " · Items: " + displayItemList(drops);
            }
            return result;
        }
        if (record.action().equals("ENTITY_DAMAGE")) {
            return "Schaden: " + valueOrQuestion(detailValue(record.details(), "damage"));
        }
        if (isItemEvent(record.action())) {
            String item = itemValue(record);
            if (item != null) {
                return "Item: " + displayItemState(item);
            }
        }
        if (record.action().equals("ENTITY_SPAWN")) {
            return "Neu erzeugt: " + shortTarget(record.target());
        }
        if (record.source().equalsIgnoreCase("entities")
                && record.beforeState() == null && record.afterState() == null) {
            return "Entity-Ereignis: " + shortTarget(record.target());
        }
        String before = compactBlockState(record.beforeState());
        String after = compactBlockState(record.afterState());
        if (record.beforeState() == null && record.afterState() == null) {
            return "Keine Zustandsdaten gespeichert";
        }
        if (record.afterState() == null) {
            return before + " → entfernt";
        }
        if (record.beforeState() == null) {
            return "neu: " + after;
        }
        return before + " → " + after;
    }

    private static String compactBlockState(String state) {
        if (state == null || state.isBlank()) {
            return "leer";
        }
        int separator = state.indexOf(' ');
        return shortTarget(separator < 0 ? state : state.substring(0, separator));
    }

    private static String rawContext(AuditRecord record) {
        if (record.action().equals("CONTAINER_SNAPSHOT")) {
            return "Slot " + valueOrQuestion(detailValue(record.details(), "slot"))
                    + " · " + displayItemState(record.beforeState())
                    + " · vollständiger Itemdatensatz gespeichert";
        }
        if (record.action().equals("CONTAINER_ITEM_CHANGE")) {
            return "Slot " + valueOrQuestion(detailValue(record.details(), "slot"))
                    + " · vorher: " + displayItemState(record.beforeState())
                    + " · nachher: " + displayItemState(record.afterState());
        }
        String details = record.details();
        if (details == null || details.isBlank()) {
            return "Keine zusätzlichen Kontextdaten gespeichert.";
        }
        return Arrays.stream(details.split(";"))
                .map(BlockProtectCommand::readableDetail)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private static String readableDetail(String part) {
        int separator = part.indexOf('=');
        if (separator <= 0) {
            return part;
        }
        String key = part.substring(0, separator).trim();
        String value = part.substring(separator + 1).trim();
        if (key.equalsIgnoreCase("stack") || key.equalsIgnoreCase("before-stack")
                || key.equalsIgnoreCase("after-stack") || key.endsWith("-uuid")
                || key.equalsIgnoreCase("uuid")) {
            return null;
        }
        if (value.isBlank() || value.equalsIgnoreCase("null")) {
            return null;
        }
        String label = switch (key.toLowerCase(Locale.ROOT)) {
            case "cause", "reason" -> "Grund";
            case "damage-type" -> "Schadensart";
            case "damage" -> "Schaden";
            case "causing-entity", "source", "entity-source" -> "Verursacher";
            case "direct-entity" -> "Direkte Quelle";
            case "target" -> "Ziel";
            case "from" -> "Von";
            case "to" -> "Nach";
            case "source-location" -> "Quelle";
            case "drop-count" -> "Drops";
            case "duration-seconds" -> "Dauer (s)";
            case "blocks" -> "Blöcke";
            case "item" -> "Item";
            default -> key;
        };
        if (key.equalsIgnoreCase("drops")) {
            value = displayItemList(value);
        } else if (isItemDetailKey(key)) {
            value = displayItemState(value);
        } else if (key.equalsIgnoreCase("entity") || key.equalsIgnoreCase("causing-entity") || key.equalsIgnoreCase("direct-entity")
                || key.equalsIgnoreCase("source") || key.equalsIgnoreCase("entity-source")
                || key.equalsIgnoreCase("target")) {
            value = valueOrQuestion(readableEntityReference(value));
        } else if (key.equalsIgnoreCase("cause") || key.equalsIgnoreCase("reason")
                || key.equalsIgnoreCase("damage-type")) {
            value = valueOrQuestion(readableKey(value));
        }
        return label + ": " + value;
    }

    private static String readableEntityReference(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null") || value.equals("-")) {
            return null;
        }
        int uuidStart = value.indexOf(" [");
        String name = uuidStart > 0 ? value.substring(0, uuidStart) : value;
        return readableKey(name);
    }

    private static String readableKey(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("null")) {
            return null;
        }
        String result = value.trim();
        if (result.startsWith("minecraft:")) {
            result = result.substring("minecraft:".length());
        }
        result = result.replace('_', ' ');
        return result.isEmpty() ? null : Character.toUpperCase(result.charAt(0)) + result.substring(1);
    }

    private static Material materialForAction(String action) {
        if (action == null) {
            return Material.PAPER;
        }
        return switch (action) {
            case "BLOCK_BREAK", "EXPLOSION_BREAK", "BLOCK_BURN" -> Material.REDSTONE;
            case "BLOCK_PLACE", "BLOCK_FORM", "BLOCK_GROW", "BLOCK_SPREAD" -> Material.GRASS_BLOCK;
            case "BLOCK_IGNITE", "TNT_PRIME" -> Material.FLINT_AND_STEEL;
            case "BLOCK_FERTILIZE", "MOISTURE_CHANGE", "LEAVES_DECAY" -> Material.WHEAT;
            case "CAULDRON_CHANGE", "FLUID_LEVEL_CHANGE" -> Material.CAULDRON;
            case "SPONGE_ABSORB" -> Material.SPONGE;
            case "BLOCK_COOK", "CRAFTER_CRAFT" -> Material.FURNACE;
            case "CONTAINER_OPEN", "CONTAINER_ITEM_CHANGE", "CONTAINER_SNAPSHOT" -> Material.CHEST;
            case "BLOCK_FLOW", "BUCKET_EMPTY", "BUCKET_FILL" -> Material.WATER_BUCKET;
            case "PISTON_EXTEND", "PISTON_RETRACT" -> Material.PISTON;
            case "ENTITY_CHANGE_BLOCK" -> Material.ZOMBIE_HEAD;
            case "ENTITY_DEATH", "ENTITY_DAMAGE", "ENTITY_REMOVE", "ENTITY_COMBUST" -> Material.SKELETON_SKULL;
            case "ENTITY_SPAWN", "ENTITY_BREED", "ENTITY_TRANSFORM" -> Material.ZOMBIE_SPAWN_EGG;
            case "ENTITY_EXPLODE" -> Material.TNT;
            case "ENTITY_TELEPORT" -> Material.ENDER_PEARL;
            case "PROJECTILE_LAUNCH", "PROJECTILE_HIT", "ENTITY_SHOOT" -> Material.BOW;
            case "ENTITY_POTION_EFFECT", "AREA_EFFECT_APPLY" -> Material.POTION;
            default -> Material.PAPER;
        };
    }

    private static List<String> wrapText(String value, int width) {
        if (value == null || value.isBlank()) {
            return List.of("-");
        }
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\\n")) {
            String remaining = paragraph;
            while (remaining.length() > width) {
                int split = remaining.lastIndexOf(' ', width);
                if (split <= 0) {
                    split = width;
                }
                lines.add(remaining.substring(0, split));
                remaining = remaining.substring(split).trim();
            }
            lines.add(remaining);
        }
        return lines;
    }

    private static String inspectDetails(AuditRecord record) {
        if (record.action().equals("CONTAINER_SNAPSHOT")) {
            return "Containerinhalt vor Abbau | Slot " + valueOrQuestion(detailValue(record.details(), "slot"))
                    + " | Inhalt: " + displayItemState(record.beforeState());
        }
        if (record.action().equals("CONTAINER_ITEM_CHANGE")) {
            return "Slot " + valueOrQuestion(detailValue(record.details(), "slot"))
                    + " | vorher: " + displayItemState(record.beforeState())
                    + " | nachher: " + displayItemState(record.afterState());
        }
        StringBuilder details = new StringBuilder();
        if (record.details() != null && !record.details().isBlank()) {
            details.append(record.details());
        }
        if (record.beforeState() != null) {
            details.append(details.isEmpty() ? "" : " | ").append("vorher: ").append(record.beforeState());
        }
        if (record.afterState() != null) {
            details.append(details.isEmpty() ? "" : " | ").append("nachher: ").append(record.afterState());
        }
        return details.toString();
    }

    private static String shortAction(String action) {
        if (action == null) {
            return "EVENT";
        }
        return switch (action) {
            case "BLOCK_BREAK" -> "ABBAU";
            case "BLOCK_PLACE" -> "PLATZIEREN";
            case "CONTAINER_OPEN" -> "ÖFFNEN";
            case "CONTAINER_ITEM_CHANGE" -> "ITEMS";
            case "CONTAINER_SNAPSHOT" -> "INHALT";
            case "INVENTORY_CLICK", "INVENTORY_DRAG" -> "INVENTAR";
            case "INVENTORY_MOVE", "INVENTORY_PICKUP" -> "HOPPER";
            case "FURNACE_EXTRACT" -> "OFEN";
            case "CRAFT" -> "CRAFTING";
            case "BREW" -> "BRAUEN";
            case "DISPENSE" -> "SPENDER";
            case "ITEM_PICKUP" -> "PICKUP";
            case "ITEM_DROP" -> "DROP";
            case "BLOCK_FLOW" -> "FLUSS";
            case "BLOCK_IGNITE" -> "FEUER";
            case "TNT_PRIME" -> "TNT";
            case "BLOCK_FERTILIZE" -> "DÜNGER";
            case "LEAVES_DECAY" -> "BLÄTTER";
            case "MOISTURE_CHANGE" -> "FEUCHTE";
            case "CAULDRON_CHANGE" -> "KESSEL";
            case "SPONGE_ABSORB" -> "SCHWAMM";
            case "FLUID_LEVEL_CHANGE" -> "FLÜSSIGKEIT";
            case "BLOCK_COOK" -> "OFEN";
            case "CRAFTER_CRAFT" -> "CRAFTER";
            case "BUCKET_EMPTY", "BUCKET_FILL" -> "EIMER";
            case "ENTITY_DAMAGE" -> "SCHADEN";
            case "ENTITY_DEATH" -> "TOD";
            case "ENTITY_SPAWN" -> "SPAWN";
            case "ENTITY_REMOVE" -> "ENTFERNT";
            case "ENTITY_EXPLODE" -> "EXPLOSION";
            case "ENTITY_TRANSFORM" -> "WANDEL";
            case "ENTITY_TARGET" -> "ZIEL";
            case "ENTITY_TELEPORT" -> "TELEPORT";
            case "ENTITY_POTION_EFFECT" -> "EFFEKT";
            case "ENTITY_HEAL" -> "HEILUNG";
            case "ENTITY_SHOOT" -> "SCHUSS";
            case "ENTITY_BLOCK_INTERACT" -> "BLOCKNUTZUNG";
            case "ITEM_DESPAWN" -> "VERSCHWUNDEN";
            case "ITEM_MERGE" -> "ZUSAMMEN";
            case "PROJECTILE_LAUNCH" -> "PROJEKTIL";
            case "PROJECTILE_HIT" -> "TREFFER";
            case "BLOCK_INTERACT", "ENTITY_INTERACT" -> "INTERAKTION";
            case "EXPLOSION_BREAK" -> "EXPLOSION";
            default -> action.length() <= 10 ? action : action.substring(0, 10);
        };
    }

    private static String lookupActionLabel(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "BLOCK_BREAK" -> "Block-Abbau";
            case "BLOCK_PLACE" -> "Block-Platzierung";
            case "BLOCK_FLOW" -> "Wasser/Lava-Fluss";
            case "EXPLOSION_BREAK" -> "Explosion";
            case "CONTAINER_OPEN" -> "Container öffnen";
            case "CONTAINER_ITEM_CHANGE" -> "Container-Items";
            case "ENTITY_DEATH" -> "Entity-Tod";
            case "ENTITY_DAMAGE" -> "Entity-Schaden";
            case "ENTITY_SPAWN" -> "Entity-Spawn";
            case "ENTITY_EXPLODE" -> "Entity-Explosion";
            case "COMMAND" -> "Befehl";
            case "CHAT" -> "Chat";
            default -> action;
        };
    }

    private static NamedTextColor actionColor(String action) {
        if (action == null) {
            return NamedTextColor.GRAY;
        }
        return switch (action) {
            case "BLOCK_BREAK", "ENTITY_DAMAGE", "ENTITY_DEATH", "ENTITY_REMOVE",
                    "ENTITY_EXPLODE", "EXPLOSION_BREAK", "TNT_PRIME" -> NamedTextColor.RED;
            case "BLOCK_PLACE", "CONTAINER_OPEN", "ITEM_PICKUP", "ENTITY_SPAWN",
                    "ENTITY_BREED", "ENTITY_HEAL" -> NamedTextColor.GREEN;
            case "CONTAINER_ITEM_CHANGE", "CONTAINER_SNAPSHOT" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.AQUA;
        };
    }

    private static String shortTarget(String target) {
        if (target == null || target.isBlank()) {
            return "-";
        }
        String value = readableKey(target);
        if (value == null) {
            return "-";
        }
        return value.length() <= 24 ? value : value.substring(0, 21) + "…";
    }

    private static String containerChangeAction(AuditRecord record) {
        if (record.action().equals("CONTAINER_SNAPSHOT")) {
            return "INHALT";
        }
        if (record.beforeState() == null && record.afterState() != null) {
            return "EINLAGERN";
        }
        if (record.beforeState() != null && record.afterState() == null) {
            return "ENTFERNT";
        }
        return "GEÄNDERT";
    }

    private static String containerChangeTarget(AuditRecord record) {
        if (record.action().equals("CONTAINER_SNAPSHOT")) {
            return "Slot " + valueOrQuestion(detailValue(record.details(), "slot")) + " "
                    + displayItemState(record.beforeState());
        }
        return "Slot " + valueOrQuestion(detailValue(record.details(), "slot")) + " "
                + itemTransition(record.beforeState(), record.afterState());
    }

    private static String itemTransition(String before, String after) {
        if (before == null && after == null) {
            return "leer";
        }
        if (before == null) {
            return "+ " + displayItemState(after);
        }
        if (after == null) {
            return "- " + displayItemState(before);
        }
        return displayItemState(before) + " → " + displayItemState(after);
    }

    private static boolean isItemDetailKey(String key) {
        if (key == null) {
            return false;
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "item", "tool", "bow", "consumable", "cursor", "new-cursor" -> true;
            default -> false;
        };
    }

    private static boolean isItemEvent(String action) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case "ITEM_PICKUP", "ITEM_DROP", "ITEM_DESPAWN", "ITEM_MERGE", "ITEM_CONSUME",
                    "ENTITY_DROP", "BLOCK_DROP", "INVENTORY_CLICK", "INVENTORY_DRAG",
                    "INVENTORY_MOVE", "INVENTORY_PICKUP", "CRAFT", "FURNACE_EXTRACT",
                    "DISPENSE", "ENTITY_SHOOT", "ENTITY_BREED", "ARMOR_STAND_MANIPULATE",
                    "BLOCK_COOK", "CRAFTER_CRAFT" -> true;
            default -> false;
        };
    }

    private static String itemValue(AuditRecord record) {
        for (String key : List.of("item", "tool", "bow", "consumable", "cursor", "new-cursor")) {
            String value = detailValue(record.details(), key);
            if (value != null && !value.isBlank() && !value.equalsIgnoreCase("null")) {
                return value;
            }
        }
        return record.itemType();
    }

    private static String displayItemList(String value) {
        if (value == null || value.isBlank() || value.equals("-")) {
            return "-";
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        List<String> items = new ArrayList<>();
        int bracketDepth = 0;
        int start = 0;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '[') {
                bracketDepth++;
            } else if (character == ']') {
                bracketDepth--;
            } else if (character == ',' && bracketDepth == 0) {
                items.add(normalized.substring(start, index).trim());
                start = index + 1;
            }
        }
        if (start < normalized.length()) {
            items.add(normalized.substring(start).trim());
        }
        return items.stream()
                .filter(item -> !item.isBlank())
                .map(BlockProtectCommand::displayItemState)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String displayItemState(String state) {
        if (state == null || state.isBlank()) {
            return "leer";
        }
        String value = state.trim();
        String enchantments = null;
        int metadataStart = value.indexOf(" [");
        if (metadataStart > 0) {
            enchantments = value.substring(metadataStart + 2);
            if (enchantments.endsWith("]")) {
                enchantments = enchantments.substring(0, enchantments.length() - 1);
            }
            value = value.substring(0, metadataStart);
        }

        String result = null;
        int separator = value.lastIndexOf('x');
        if (separator > 0 && separator + 1 < value.length()) {
            Integer amount = integer(value.substring(separator + 1));
            if (amount != null) {
                result = amount + "x " + shortTarget(value.substring(0, separator));
            }
        }
        if (result == null) {
            result = shortTarget(value);
        }
        if (enchantments != null && !enchantments.isBlank()) {
            result += " · Verzauberungen: " + Arrays.stream(enchantments.split(",\\s*"))
                    .map(BlockProtectCommand::displayEnchantment)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return result;
    }

    private static String displayEnchantment(String value) {
        String enchantment = value.trim();
        int separator = enchantment.lastIndexOf(' ');
        if (separator <= 0 || separator + 1 >= enchantment.length()) {
            return readableKey(enchantment);
        }
        Integer level = integer(enchantment.substring(separator + 1));
        if (level == null) {
            return readableKey(enchantment);
        }
        return readableKey(enchantment.substring(0, separator)) + " " + romanLevel(level);
    }

    private static String romanLevel(int level) {
        if (level <= 0 || level > 20) {
            return Integer.toString(level);
        }
        int[] values = {10, 9, 5, 4, 1};
        String[] numerals = {"X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        int remaining = level;
        for (int index = 0; index < values.length; index++) {
            while (remaining >= values[index]) {
                result.append(numerals[index]);
                remaining -= values[index];
            }
        }
        return result.toString();
    }

    private static String valueOrQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String fixed(String value, int width) {
        String clipped = value.length() <= width ? value : value.substring(0, Math.max(0, width - 1)) + "…";
        return String.format(Locale.ROOT, "%-" + width + "s", clipped);
    }

    private boolean lookup(CommandSender sender, String[] args) {
        if (args.length > 1 && (args[1].equalsIgnoreCase("help") || args[1].equalsIgnoreCase("?"))) {
            lookupHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Lookup benötigt eine Spielerposition; führe den Befehl ingame aus.");
            return true;
        }
        if (!has(sender, "blockprotect.lookup")) {
            return true;
        }

        if (args.length > 1 && args[1].equalsIgnoreCase("filter")) {
            return lookupFilter(player);
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("refresh")
                || args[1].equalsIgnoreCase("reload"))) {
            return refreshLookup(player);
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
            auditViews.remove(player.getUniqueId());
            auditQueries.remove(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "Die letzte Lookup-Suche wurde geschlossen.");
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("gui")) {
            return lookupGui(player, args);
        }

        if (args.length > 1 && (args[1].equalsIgnoreCase("next")
                || args[1].equalsIgnoreCase("prev")
                || args[1].equalsIgnoreCase("page"))) {
            return lookupPage(player, args);
        }

        if (args.length > 1 && args[1].equalsIgnoreCase("block")) {
            org.bukkit.block.Block targetBlock = player.getTargetBlockExact(20);
            Location target = targetBlock == null ? null : targetBlock.getLocation();
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Du schaust auf keinen auswertbaren Block.");
                return true;
            }
            try {
                boolean gui = args.length > 2 && args[2].equalsIgnoreCase("gui");
                LookupOptions options = parseLookupOptions(args, gui ? 3 : 2, false);
                String heading = "Block " + target.getWorld().getName() + " @ "
                        + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ();
                AuditQuery query = exactQuery(target, options.filters(), options.limit());
                if (gui) {
                    runGuiQuery(player, "lookup", heading, target, options.filters(), query);
                } else {
                    sendLookup(player, query, heading, options.filters());
                }
            } catch (IllegalArgumentException exception) {
                player.sendMessage(ChatColor.RED + exception.getMessage());
            }
            return true;
        }

        LookupOptions options;
        try {
            options = parseLookupOptions(args, 1, true);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }

        Location location = player.getLocation();
        AuditQuery query = areaQuery(location, options);
        sendLookup(player, query, lookupHeading(location, options), options.filters());
        return true;
    }

    private boolean lookupGui(Player player, String[] args) {
        if (args.length > 2 && args[2].equalsIgnoreCase("block")) {
            org.bukkit.block.Block targetBlock = player.getTargetBlockExact(20);
            Location target = targetBlock == null ? null : targetBlock.getLocation();
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Du schaust auf keinen auswertbaren Block.");
                return true;
            }
            try {
                LookupOptions options = parseLookupOptions(args, 3, false);
                String heading = "Block " + target.getWorld().getName() + " @ "
                        + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ();
                runGuiQuery(player, "lookup", heading, target, options.filters(),
                        exactQuery(target, options.filters(), options.limit()));
            } catch (IllegalArgumentException exception) {
                player.sendMessage(ChatColor.RED + exception.getMessage());
            }
            return true;
        }
        LookupOptions options;
        try {
            options = parseLookupOptions(args, 2, true);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }

        Location location = player.getLocation();
        String heading = lookupHeading(location, options);
        runGuiQuery(player, "lookup", heading, null, options.filters(), areaQuery(location, options));
        return true;
    }

    private boolean lookupFilter(Player player) {
        AuditView view = auditViews.get(player.getUniqueId());
        if (view == null || !view.mode().equals("lookup")) {
            player.sendMessage(ChatColor.GRAY + "Starte zuerst eine Suche mit /bp lookup.");
            player.sendMessage(ChatColor.GRAY + "Danach öffnet /bp lookup filter die Filterauswahl.");
            return true;
        }
        openFilterGui(player);
        return true;
    }

    private boolean refreshLookup(Player player) {
        AuditView view = auditViews.get(player.getUniqueId());
        AuditQuery query = auditQueries.get(player.getUniqueId());
        if (view == null || !view.mode().equals("lookup") || query == null) {
            player.sendMessage(ChatColor.GRAY + "Es gibt noch keine Lookup-Suche zum Aktualisieren.");
            return true;
        }
        sendLookup(player, query, view.context(), view.filter());
        return true;
    }

    private void lookupHelp(CommandSender sender) {
        String base = "/bp lookup";
        sender.sendMessage(Component.text("╭─ Lookup · einfache Suche", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("│ " + base + "", NamedTextColor.AQUA)
                .append(Component.text("  – Umgebung mit Standardwerten durchsuchen", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ " + base + " gui", NamedTextColor.AQUA)
                .append(Component.text("  – dieselbe Suche als übersichtliche GUI", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ " + base + " block", NamedTextColor.AQUA)
                .append(Component.text("  – den Block unter deinem Fadenkreuz prüfen", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│ Filter werden mit Namen angegeben – Reihenfolge egal:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("│   --radius 15   --limit 100", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("│   --player Steve   --action break", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("│   --source entities   --target zombie", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("│   --time 2h   oder   --since 7d", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("│", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│ Häufige Aktionen: break, place, death, damage, spawn,", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│ explosion, flow, container, command, chat", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│ Quellen: blocks, containers, inventories, entities,", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│         environment, interactions, commands, chat", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("│ Beispiele:", NamedTextColor.YELLOW));
        helpLine(sender, base + " --radius 20 --player Steve --since 2h",
                "Spieleraktionen der letzten 2 Stunden", base + " --radius 20 --player Steve --since 2h");
        helpLine(sender, base + " --source entities --action death",
                "Entity-Tode in der Nähe", base + " --source entities --action death");
        helpLine(sender, base + " block --action break",
                "Abbau am anvisierten Block", base + " block --action break");
        sender.sendMessage(Component.text("│ Alte Schreibweise bleibt möglich: /bp lookup 10 50 Steve break", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("╰─ Nach einer Suche: /bp lookup filter · /bp lookup refresh · /bp lookup clear", NamedTextColor.DARK_GRAY));
    }

    private boolean rollback(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Rollback benötigt eine Spielerposition; führe den Befehl ingame aus.");
            return true;
        }
        if (!has(sender, "blockprotect.rollback")) {
            return true;
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("help") || args[1].equalsIgnoreCase("?"))) {
            rollbackHelp(player);
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
            return confirmRollback(player);
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("undo")) {
            String[] restoreArgs = args.length > 2
                    ? new String[]{"restore", args[2]}
                    : new String[]{"restore"};
            return restore(sender, restoreArgs);
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("cancel")) {
            pendingRollbacks.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Rollback-Vorschau verworfen.");
            return true;
        }

        RollbackOptions options;
        try {
            options = parseRollbackOptions(args);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }

        Location origin = player.getLocation();
        // Destructive block actions also need the slot snapshots written for containers.
        // They have their own action so the normal action filter cannot be used here.
        String queryAction = needsContainerSnapshots(options.action()) ? null : options.action();
        AuditQuery query = new AuditQuery(
                origin.getWorld().getName(),
                origin.getBlockX() - options.radius(), origin.getBlockX() + options.radius(),
                origin.getBlockY() - options.radius(), origin.getBlockY() + options.radius(),
                origin.getBlockZ() - options.radius(), origin.getBlockZ() + options.radius(),
                options.actorName(), queryAction, null, null,
                options.sinceTimestamp(), options.untilTimestamp(), options.limit()
        );
        player.sendMessage(Component.text("BlockProtect Rollback – Vorschau wird erstellt …", NamedTextColor.GRAY));
        store.query(query).whenComplete((records, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                player.sendMessage(Component.text("Rollback fehlgeschlagen: " + rootMessage(throwable), NamedTextColor.RED));
                return;
            }
            List<AuditRecord> candidates = records.stream()
                    .filter(record -> isRollbackCandidate(record, options))
                    .toList();
            if (candidates.isEmpty()) {
                pendingRollbacks.remove(player.getUniqueId());
                player.sendMessage(Component.text("Keine rückgängig machbaren Änderungen im gewählten Bereich.", NamedTextColor.GRAY));
                return;
            }

            long expiresAt = System.currentTimeMillis() + options.confirmSeconds() * 1_000L;
            PendingRollback pending = new PendingRollback(candidates, options, expiresAt);
            pendingRollbacks.put(player.getUniqueId(), pending);
            showRollbackPreview(player, pending);
        }));
        return true;
    }

    private void rollbackHelp(Player player) {
        player.sendMessage(Component.text("╭─ Rollback · Änderungen rückgängig machen", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ /bp rollback", NamedTextColor.AQUA)
                .append(Component.text("  – Vorschau der letzten Änderungen", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("│ /bp rollback --radius 20 --time 30m", NamedTextColor.AQUA)
                .append(Component.text("  – Bereich und Zeitraum festlegen", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("│ /bp rollback --player Steve --action break", NamedTextColor.AQUA)
                .append(Component.text("  – nach Spieler/Aktion filtern", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("│ Bestätigen: /bp rollback confirm", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("│ Abbrechen: /bp rollback cancel", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("╰─ Erst die Vorschau prüfen – erst danach bestätigen.", NamedTextColor.DARK_GRAY));
    }

    private boolean confirmRollback(Player player) {
        PendingRollback pending = pendingRollbacks.remove(player.getUniqueId());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            player.sendMessage(ChatColor.RED + "Keine gültige Rollback-Vorschau vorhanden. Starte zuerst /bp rollback.");
            return true;
        }
        player.sendMessage(ChatColor.YELLOW + "Rollback wird angewendet …");
        Bukkit.getScheduler().runTask(plugin, () -> applyRollback(player, pending));
        return true;
    }

    private RollbackOptions parseRollbackOptions(String[] args) {
        long now = System.currentTimeMillis();
        int radius = config.getInt("rollback.default-radius", config.getInt("lookup.default-radius", 10));
        int limit = config.getInt("rollback.max-records", 5_000);
        int confirmSeconds = config.getInt("rollback.confirm-seconds", 60);
        String actorName = null;
        String action = null;
        Long sinceTimestamp = null;
        Long untilTimestamp = now;
        String timeLabel = "alle Zeit";

        String defaultTime = config.getString("rollback.default-time", "30m");
        if (!defaultTime.isBlank() && !defaultTime.equalsIgnoreCase("all")) {
            sinceTimestamp = parseTimeValue(defaultTime, now).timestamp();
            timeLabel = "letzte " + defaultTime;
        }

        boolean radiusSet = false;
        boolean timeSet = false;
        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            String key = null;
            String value = null;
            if (token.startsWith("--")) {
                String raw = token.substring(2);
                int separator = raw.indexOf('=');
                if (separator >= 0) {
                    key = raw.substring(0, separator);
                    value = raw.substring(separator + 1);
                } else {
                    key = raw;
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("Filter " + token + " benötigt einen Wert.");
                    }
                    value = args[++index];
                }
            } else if (token.contains("=")) {
                int separator = token.indexOf('=');
                key = token.substring(0, separator);
                value = token.substring(separator + 1);
            } else if (!radiusSet && integer(token) != null) {
                radius = integer(token);
                radiusSet = true;
                continue;
            } else if (!timeSet && looksLikeTime(token)) {
                TimeValue time = parseTimeValue(token, now);
                sinceTimestamp = time.timestamp();
                untilTimestamp = now;
                timeLabel = time.timestamp() == null ? "alle Zeit" : "letzte " + token;
                timeSet = true;
                continue;
            } else if (actorName == null) {
                actorName = token;
                continue;
            } else if (action == null) {
                action = normalizeRollbackAction(token);
                continue;
            } else {
                throw new IllegalArgumentException("Unbekannter oder überzähliger Rollback-Filter: " + token);
            }

            if (key == null || value == null || value.isBlank()) {
                throw new IllegalArgumentException("Rollback-Filter benötigt einen Wert.");
            }
            switch (normalizeRollbackKey(key)) {
                case "radius" -> {
                    Integer parsed = integer(value);
                    if (parsed == null) {
                        throw new IllegalArgumentException("radius muss eine ganze Zahl sein.");
                    }
                    radius = parsed;
                    radiusSet = true;
                }
                case "limit" -> {
                    Integer parsed = integer(value);
                    if (parsed == null) {
                        throw new IllegalArgumentException("limit muss eine ganze Zahl sein.");
                    }
                    limit = parsed;
                }
                case "actor" -> actorName = value;
                case "action" -> action = normalizeRollbackAction(value);
                case "time" -> {
                    TimeValue time = parseTimeValue(value, now);
                    sinceTimestamp = time.timestamp();
                    untilTimestamp = now;
                    timeLabel = time.timestamp() == null ? "alle Zeit" : "letzte " + value;
                    timeSet = true;
                }
                case "since" -> {
                    sinceTimestamp = parseTimeValue(value, now).timestamp();
                    timeLabel = "seit " + value;
                    timeSet = true;
                }
                case "until" -> {
                    untilTimestamp = parseTimeValue(value, now).timestamp();
                    timeLabel = "bis " + value;
                    timeSet = true;
                }
                default -> throw new IllegalArgumentException("Unbekannter Rollback-Filter: " + key
                        + ". Erlaubt sind radius, time, since, until, player, action und limit.");
            }
        }

        if (radius < 0 || radius > 200) {
            throw new IllegalArgumentException("radius muss zwischen 0 und 200 liegen.");
        }
        int maxRecords = Math.max(1, Math.min(50_000, config.getInt("rollback.max-records", 5_000)));
        limit = Math.max(1, Math.min(maxRecords, limit));
        if (confirmSeconds < 10 || confirmSeconds > 600) {
            confirmSeconds = 60;
        }
        if (sinceTimestamp != null && untilTimestamp != null && sinceTimestamp > untilTimestamp) {
            throw new IllegalArgumentException("since darf nicht nach until liegen.");
        }
        if (actorName != null && actorName.isBlank()) {
            actorName = null;
        }
        if (action != null && !ROLLBACK_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Aktion ist nicht sicher rückgängig machbar: " + action
                    + ". Erlaubt: " + String.join(", ", ROLLBACK_ACTIONS));
        }
        return new RollbackOptions(radius, limit, confirmSeconds, actorName, action,
                sinceTimestamp, untilTimestamp, timeLabel);
    }

    private static boolean needsContainerSnapshots(String action) {
        return action == null
                || action.equals("BLOCK_BREAK")
                || action.equals("EXPLOSION_BREAK")
                || action.equals("BLOCK_BURN")
                || action.equals("BLOCK_FADE");
    }

    private void showRollbackPreview(Player player, PendingRollback pending) {
        RollbackOptions options = pending.options();
        List<AuditRecord> records = pending.records();
        player.sendMessage(Component.text("╭─ BlockProtect Rollback ─────────────────", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ Radius=" + options.radius() + "  ·  " + records.size()
                + " Änderungen  ·  neueste zuerst", NamedTextColor.GRAY));
        player.sendMessage(Component.text("│ Zeit: " + options.timeLabel(), NamedTextColor.YELLOW));
        if (options.actorName() != null) {
            player.sendMessage(Component.text("│ Filter: player=" + options.actorName()
                    + (options.action() == null ? "" : ", action=" + options.action()), NamedTextColor.YELLOW));
        } else if (options.action() != null) {
            player.sendMessage(Component.text("│ Filter: action=" + options.action(), NamedTextColor.YELLOW));
        }

        int end = Math.min(records.size(), ROLLBACK_PREVIEW_SIZE);
        for (int index = 0; index < end; index++) {
            sendChatAuditEntry(player, records.get(index), index + 1);
        }
        if (records.size() > end) {
            player.sendMessage(Component.text("│ … und " + (records.size() - end) + " weitere Änderungen", NamedTextColor.DARK_GRAY));
        }

        long seconds = Math.max(1, (pending.expiresAt() - System.currentTimeMillis() + 999L) / 1_000L);
        Component confirmation = Component.text("╰─ Vorschau gültig " + seconds + "s · ", NamedTextColor.GRAY)
                .append(Component.text("Rollback bestätigen", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/bp rollback confirm")));
        player.sendMessage(confirmation);
    }

    private void applyRollback(Player player, PendingRollback pending) {
        int restored = 0;
        int skipped = 0;
        List<RollbackChange> changes = new ArrayList<>();
        for (AuditRecord record : pending.records()) {
            try {
                RollbackChange change = rollbackRecord(record);
                if (change != null) {
                    changes.add(change);
                    restored++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                skipped++;
                plugin.getLogger().warning("Rollback von Audit-Eintrag fehlgeschlagen: " + exception.getMessage());
            }
        }
        plugin.getLogger().info("Rollback für " + player.getName() + ": " + restored
                + " wiederhergestellt, " + skipped + " übersprungen.");
        if (!changes.isEmpty()) {
            lastRollbacks.put(player.getUniqueId(), new AppliedRollback(changes, System.currentTimeMillis()));
        }
        if (player.isOnline()) {
            player.sendMessage(ChatColor.GREEN + "Rollback abgeschlossen: " + restored + " wiederhergestellt"
                    + (skipped == 0 ? "." : ", " + skipped + " übersprungen (Zustand nicht mehr passend oder nicht verfügbar)."));
        }
    }

    private boolean restore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Restore benötigt einen Spieler.");
            return true;
        }
        if (!has(sender, "blockprotect.rollback")) {
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
            return confirmRestore(player);
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("cancel")) {
            pendingRestores.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Restore-Vorschau verworfen.");
            return true;
        }

        AppliedRollback applied = lastRollbacks.get(player.getUniqueId());
        if (applied == null || applied.changes().isEmpty()) {
            player.sendMessage(ChatColor.RED + "Für dich ist kein rückgängig gemachter Vorgang verfügbar.");
            return true;
        }
        long expiresAt = System.currentTimeMillis()
                + Math.max(10, Math.min(600, config.getInt("rollback.confirm-seconds", 60))) * 1_000L;
        PendingRestore pending = new PendingRestore(applied.changes(), expiresAt);
        pendingRestores.put(player.getUniqueId(), pending);
        showRestorePreview(player, pending);
        return true;
    }

    private boolean confirmRestore(Player player) {
        PendingRestore pending = pendingRestores.remove(player.getUniqueId());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            player.sendMessage(ChatColor.RED + "Keine gültige Restore-Vorschau vorhanden. Nutze zuerst /bp restore.");
            return true;
        }
        player.sendMessage(ChatColor.YELLOW + "Restore wird angewendet …");
        Bukkit.getScheduler().runTask(plugin, () -> applyRestore(player, pending));
        return true;
    }

    private void showRestorePreview(Player player, PendingRestore pending) {
        player.sendMessage(Component.text("╭─ BlockProtect Restore ─────────────────", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ Stellt den letzten Rollback wieder her  ·  "
                + pending.changes().size() + " Änderungen", NamedTextColor.GRAY));
        int end = Math.min(pending.changes().size(), ROLLBACK_PREVIEW_SIZE);
        for (int index = 0; index < end; index++) {
            sendChatAuditEntry(player, pending.changes().get(index).record(), index + 1);
        }
        if (pending.changes().size() > end) {
            player.sendMessage(Component.text("│ … und " + (pending.changes().size() - end) + " weitere Änderungen", NamedTextColor.DARK_GRAY));
        }
        long seconds = Math.max(1, (pending.expiresAt() - System.currentTimeMillis() + 999L) / 1_000L);
        player.sendMessage(Component.text("╰─ Vorschau gültig " + seconds + "s · ", NamedTextColor.GRAY)
                .append(Component.text("Restore bestätigen", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/bp restore confirm"))));
    }

    private void applyRestore(Player player, PendingRestore pending) {
        int restored = 0;
        int skipped = 0;
        for (int index = pending.changes().size() - 1; index >= 0; index--) {
            try {
                if (restoreChange(pending.changes().get(index))) {
                    restored++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                skipped++;
                plugin.getLogger().warning("Restore von Rollback-Eintrag fehlgeschlagen: " + exception.getMessage());
            }
        }
        lastRollbacks.remove(player.getUniqueId());
        plugin.getLogger().info("Restore für " + player.getName() + ": " + restored
                + " wiederhergestellt, " + skipped + " übersprungen.");
        if (player.isOnline()) {
            player.sendMessage(ChatColor.GREEN + "Restore abgeschlossen: " + restored + " wiederhergestellt"
                    + (skipped == 0 ? "." : ", " + skipped + " übersprungen (zwischenzeitlich verändert)."));
        }
    }

    private boolean isRollbackCandidate(AuditRecord record, RollbackOptions options) {
        if (!ROLLBACK_ACTIONS.contains(record.action())
                || record.world() == null || record.x() == null || record.y() == null || record.z() == null) {
            return false;
        }
        if (record.action().equals("CONTAINER_SNAPSHOT")) {
            if (options.action() != null && !options.action().equals(snapshotOrigin(record))) {
                return false;
            }
            return detailValue(record.details(), "slot") != null && record.beforeState() != null;
        }
        if (options.action() != null && !record.action().equals(options.action())) {
            return false;
        }
        if (record.action().equals("CONTAINER_ITEM_CHANGE")) {
            return detailValue(record.details(), "slot") != null;
        }
        return record.beforeState() != null;
    }

    private RollbackChange rollbackRecord(AuditRecord record) {
        if (record.action().equals("CONTAINER_ITEM_CHANGE")
                || record.action().equals("CONTAINER_SNAPSHOT")) {
            return rollbackContainerItem(record);
        }
        if (!ROLLBACK_ACTIONS.contains(record.action())) {
            return null;
        }
        return rollbackBlock(record);
    }

    private RollbackChange rollbackBlock(AuditRecord record) {
        if (record.beforeState() == null) {
            return null;
        }
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(record.x(), record.y(), record.z());
        BlockData before = parseBlockData(record.beforeState());
        if (before == null) {
            return null;
        }
        String previousState = blockState(block);
        Map<Integer, String> previousInventory = inventorySnapshot(block);
        block.setBlockData(before, false);
        return new RollbackChange(record, previousState, previousInventory);
    }

    private RollbackChange rollbackContainerItem(AuditRecord record) {
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            return null;
        }
        Integer slot = integer(detailValue(record.details(), "slot"));
        if (slot == null) {
            return null;
        }
        Inventory inventory = inventoryFor(record, world);
        if (inventory == null) {
            return null;
        }
        if (slot < 0 || slot >= inventory.getSize()) {
            return null;
        }
        String current = itemState(inventory.getItem(slot));
        if (!Objects.equals(current, record.afterState())) {
            return null;
        }
        inventory.setItem(slot, parseItem(record, record.beforeState()));
        return new RollbackChange(record, current, null);
    }

    private boolean restoreChange(RollbackChange change) {
        AuditRecord record = change.record();
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            return false;
        }
        if (record.action().equals("CONTAINER_ITEM_CHANGE")
                || record.action().equals("CONTAINER_SNAPSHOT")) {
            Inventory inventory = inventoryFor(record, world);
            Integer slot = integer(detailValue(record.details(), "slot"));
            if (inventory == null || slot == null || slot < 0 || slot >= inventory.getSize()) {
                return false;
            }
            if (!Objects.equals(itemState(inventory.getItem(slot)), record.beforeState())) {
                return false;
            }
            inventory.setItem(slot, parseItem(record, change.previousState()));
            return true;
        }
        Block block = world.getBlockAt(record.x(), record.y(), record.z());
        if (record.beforeState() == null || !Objects.equals(blockState(block), record.beforeState())) {
            return false;
        }
        BlockData previous = parseBlockData(change.previousState());
        if (previous == null) {
            return false;
        }
        block.setBlockData(previous, false);
        restoreInventory(block, change.previousInventory());
        return true;
    }

    private static Inventory inventoryFor(AuditRecord record, World world) {
        BlockState state = world.getBlockAt(record.x(), record.y(), record.z()).getState();
        return state instanceof InventoryHolder holder ? holder.getInventory() : null;
    }

    private static Map<Integer, String> inventorySnapshot(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder holder)) {
            return null;
        }
        Inventory inventory = holder.getInventory();
        Map<Integer, String> snapshot = new HashMap<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            String item = serializedItemState(inventory.getItem(slot));
            if (item != null) {
                snapshot.put(slot, item);
            }
        }
        return Map.copyOf(snapshot);
    }

    private static void restoreInventory(Block block, Map<Integer, String> snapshot) {
        if (snapshot == null) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder holder)) {
            return;
        }
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, parseItem(entry.getValue()));
            }
        }
    }

    private static String serializedItemState(ItemStack item) {
        String data = AuditUtil.itemData(item);
        return data == null ? null : "serialized:" + data;
    }

    private static String blockState(Block block) {
        return block.getType().getKey() + " " + block.getBlockData().getAsString();
    }

    private static BlockData parseBlockData(String state) {
        String value = state.trim();
        int separator = value.indexOf(' ');
        if (separator >= 0) {
            value = value.substring(separator + 1).trim();
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return Bukkit.createBlockData(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ItemStack parseItem(AuditRecord record, String state) {
        String stackKey = state != null && state.equals(record.beforeState()) ? "before-stack" : "after-stack";
        ItemStack serialized = deserializeItem(detailValue(record.details(), stackKey));
        if (serialized == null) {
            serialized = deserializeItem(detailValue(record.details(), "stack"));
        }
        return serialized == null ? parseItem(state) : serialized;
    }

    private static ItemStack parseItem(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        if (state.startsWith("serialized:")) {
            return deserializeItem(state.substring("serialized:".length()));
        }
        String value = state.trim();
        int metadataStart = value.indexOf(" [");
        if (metadataStart > 0) {
            value = value.substring(0, metadataStart);
        }
        int separator = value.lastIndexOf('x');
        if (separator <= 0 || separator + 1 >= value.length()) {
            return null;
        }
        Integer parsedAmount = integer(value.substring(separator + 1));
        if (parsedAmount == null || parsedAmount <= 0) {
            return null;
        }
        int amount = parsedAmount;
        String key = value.substring(0, separator);
        String materialName = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) {
            return null;
        }
        return new ItemStack(material, amount);
    }

    private static ItemStack deserializeItem(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String itemState(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return AuditUtil.item(item);
    }

    private static String detailValue(String details, String key) {
        if (details == null || details.isBlank()) {
            return null;
        }
        for (String part : details.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0 && part.substring(0, separator).equalsIgnoreCase(key)) {
                return part.substring(separator + 1);
            }
        }
        return null;
    }

    private static String snapshotOrigin(AuditRecord record) {
        String origin = detailValue(record.details(), "origin-action");
        return origin == null || origin.isBlank() ? "BLOCK_BREAK" : origin.toUpperCase(Locale.ROOT);
    }

    private static String normalizeRollbackKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "r", "radius" -> "radius";
            case "limit", "max" -> "limit";
            case "player", "spieler", "actor", "user" -> "actor";
            case "action", "aktion" -> "action";
            case "time", "duration", "zeit" -> "time";
            case "since", "from", "ab" -> "since";
            case "until", "to", "before", "bis" -> "until";
            default -> key.toLowerCase(Locale.ROOT);
        };
    }

    private static String normalizeRollbackAction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "break", "blockbreak", "abbau" -> "BLOCK_BREAK";
            case "place", "blockplace", "platzieren" -> "BLOCK_PLACE";
            case "flow", "water", "lava", "fluss" -> "BLOCK_FLOW";
            case "explosion", "explode" -> "EXPLOSION_BREAK";
            case "items", "steal", "container", "container_item_change" -> "CONTAINER_ITEM_CHANGE";
            case "snapshot", "container_snapshot", "inhalt" -> "CONTAINER_SNAPSHOT";
            default -> action.toUpperCase(Locale.ROOT);
        };
    }

    private static boolean looksLikeTime(String value) {
        if (value.equalsIgnoreCase("all")) {
            return true;
        }
        if (parseDurationMillis(value) != null) {
            return true;
        }
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static TimeValue parseTimeValue(String value, long now) {
        if (value.equalsIgnoreCase("all")) {
            return new TimeValue(null);
        }
        Long duration = parseDurationMillis(value);
        if (duration != null) {
            return new TimeValue(now - duration);
        }
        try {
            return new TimeValue(Instant.parse(value).toEpochMilli());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Ungültiger Zeitfilter: " + value
                    + ". Beispiele: 30m, 2h, 7d oder 2026-09-06T12:00:00Z.");
        }
    }

    private static Long parseDurationMillis(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        int split = 0;
        while (split < normalized.length() && Character.isDigit(normalized.charAt(split))) {
            split++;
        }
        String number = normalized.substring(0, split);
        String unit = normalized.substring(split);
        if (number.isBlank() || (!unit.isBlank() && !Set.of("s", "m", "h", "d", "w").contains(unit))) {
            return null;
        }
        try {
            long amount = Long.parseLong(number);
            long multiplier = switch (unit) {
                case "s" -> 1_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 60_000L;
            };
            return Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Zeitdauer ist zu groß: " + value);
        }
    }

    private static AuditQuery exactQuery(Location location, LookupFilters filters, int limit) {
        return new AuditQuery(
                location.getWorld().getName(),
                location.getBlockX(), location.getBlockX(),
                location.getBlockY(), location.getBlockY(),
                location.getBlockZ(), location.getBlockZ(),
                filters.actorName(), filters.action(), filters.source(), filters.target(),
                filters.sinceTimestamp(), filters.untilTimestamp(), limit
        );
    }

    private static AuditQuery areaQuery(Location location, LookupOptions options) {
        int radius = options.radius();
        LookupFilters filters = options.filters();
        return new AuditQuery(
                location.getWorld().getName(),
                location.getBlockX() - radius, location.getBlockX() + radius,
                location.getBlockY() - radius, location.getBlockY() + radius,
                location.getBlockZ() - radius, location.getBlockZ() + radius,
                filters.actorName(), filters.action(), filters.source(), filters.target(),
                filters.sinceTimestamp(), filters.untilTimestamp(), options.limit()
        );
    }

    private static String lookupHeading(Location location, LookupOptions options) {
        return "Umgebung " + locationText(location) + " · Radius " + options.radius()
                + " · maximal " + options.limit() + " Einträge";
    }

    /**
     * Parses the lookup command in the form that is easiest to explain to an
     * administrator. The old positional form is deliberately still accepted:
     * /bp lookup 10 50 Steve break
     */
    private LookupOptions parseLookupOptions(String[] args, int start, boolean allowRadius) {
        long now = System.currentTimeMillis();
        int radius = config.getInt("lookup.default-radius", 10);
        int limit = config.getInt("lookup.default-limit", 50);
        String actorName = null;
        String action = null;
        String source = null;
        String target = null;
        Long sinceTimestamp = null;
        Long untilTimestamp = null;
        String timeDescription = null;
        boolean radiusSet = false;
        boolean limitSet = false;
        boolean timeSet = false;

        for (int index = start; index < args.length; index++) {
            String token = args[index].trim();
            if (token.isBlank()) {
                continue;
            }

            String key = null;
            String value = null;
            if (token.startsWith("--")) {
                String raw = token.substring(2);
                int separator = raw.indexOf('=');
                if (separator >= 0) {
                    key = raw.substring(0, separator);
                    value = raw.substring(separator + 1);
                } else {
                    key = raw;
                    if (index + 1 >= args.length) {
                        throw lookupFilterValueMissing(token);
                    }
                    value = args[++index];
                }
            } else if (token.contains("=")) {
                int separator = token.indexOf('=');
                key = token.substring(0, separator);
                value = token.substring(separator + 1);
            } else if (isLookupOptionKey(token)) {
                key = token;
                if (index + 1 >= args.length) {
                    throw lookupFilterValueMissing(token);
                }
                value = args[++index];
            } else if (token.equalsIgnoreCase("all") || token.equalsIgnoreCase("alle")) {
                sinceTimestamp = null;
                untilTimestamp = null;
                timeDescription = "alle Zeit";
                timeSet = true;
                continue;
            } else if (integer(token) != null) {
                Integer parsed = integer(token);
                if (!allowRadius) {
                    throw new IllegalArgumentException("Bei einer Blocksuche brauchst du keinen Radius. "
                            + "Nutze zum Begrenzen nur --limit <zahl>.");
                }
                if (!radiusSet) {
                    radius = parsed;
                    radiusSet = true;
                } else if (!limitSet) {
                    limit = parsed;
                    limitSet = true;
                } else {
                    throw new IllegalArgumentException("Zu viele Zahlen. Nutze --radius <zahl> und --limit <zahl>.");
                }
                continue;
            } else if (looksLikeTime(token) && !timeSet) {
                TimeValue time = parseTimeValue(token, now);
                sinceTimestamp = time.timestamp();
                untilTimestamp = time.timestamp() == null ? null : now;
                timeDescription = time.timestamp() == null ? "alle Zeit" : "letzte " + token;
                timeSet = true;
                continue;
            } else if (!allowRadius && actorName == null && action == null && looksLikeLookupAction(token)) {
                action = normalizeLookupAction(token);
                continue;
            } else if (actorName == null) {
                // Backwards-compatible positional player filter.
                actorName = token;
                continue;
            } else if (action == null) {
                // Backwards-compatible positional action filter.
                action = normalizeLookupAction(token);
                continue;
            } else {
                throw new IllegalArgumentException("Unbekannter oder überzähliger Lookup-Filter: " + token
                        + ". Nutze /bp lookup help für Beispiele.");
            }

            if (key == null || value == null || value.isBlank()
                    || (value.startsWith("--") && !value.contains("="))) {
                throw lookupFilterValueMissing(key == null ? token : "--" + key);
            }

            switch (normalizeLookupKey(key)) {
                case "radius" -> {
                    if (!allowRadius) {
                        throw new IllegalArgumentException("Bei einer Blocksuche brauchst du keinen Radius. "
                                + "Nutze zum Begrenzen nur --limit <zahl>.");
                    }
                    Integer parsed = integer(value);
                    if (parsed == null) {
                        throw new IllegalArgumentException("Der Radius muss eine ganze Zahl sein, zum Beispiel 10.");
                    }
                    radius = parsed;
                    radiusSet = true;
                }
                case "limit" -> {
                    Integer parsed = integer(value);
                    if (parsed == null) {
                        throw new IllegalArgumentException("Das Limit muss eine ganze Zahl sein, zum Beispiel 50.");
                    }
                    limit = parsed;
                    limitSet = true;
                }
                case "actor" -> actorName = value;
                case "action" -> action = normalizeLookupAction(value);
                case "source" -> source = normalizeLookupSource(value);
                case "target" -> target = normalizeLookupTarget(value);
                case "time" -> {
                    TimeValue time = parseTimeValue(value, now);
                    sinceTimestamp = time.timestamp();
                    untilTimestamp = time.timestamp() == null ? null : now;
                    timeDescription = time.timestamp() == null ? "alle Zeit" : "letzte " + value;
                    timeSet = true;
                }
                case "since" -> {
                    sinceTimestamp = parseTimeValue(value, now).timestamp();
                    timeDescription = sinceTimestamp == null ? "alle Zeit" : "seit " + value;
                    timeSet = true;
                }
                case "until" -> {
                    untilTimestamp = parseTimeValue(value, now).timestamp();
                    timeDescription = untilTimestamp == null ? "bis jetzt" : "bis " + value;
                    timeSet = true;
                }
                default -> throw unknownLookupFilter(key);
            }
        }

        if (radius < 0 || radius > 10_000) {
            throw new IllegalArgumentException("Der Radius muss zwischen 0 und 10000 liegen.");
        }
        int maxLimit = Math.max(1, Math.min(50_000, config.getInt("lookup.max-limit", 500)));
        limit = Math.max(1, Math.min(maxLimit, limit));
        if (sinceTimestamp != null && untilTimestamp != null && sinceTimestamp > untilTimestamp) {
            throw new IllegalArgumentException("Der Startzeitpunkt darf nicht nach dem Endzeitpunkt liegen.");
        }

        LookupFilters filters = new LookupFilters(blankToNull(actorName), blankToNull(action),
                blankToNull(source), blankToNull(target), sinceTimestamp, untilTimestamp, timeDescription);
        return new LookupOptions(radius, limit, filters);
    }

    private static boolean looksLikeLookupAction(String value) {
        return Set.of("BLOCK_BREAK", "BLOCK_PLACE", "BLOCK_FLOW", "BLOCK_BURN", "EXPLOSION_BREAK",
                "CONTAINER_ITEM_CHANGE", "CONTAINER_OPEN", "ENTITY_DEATH", "ENTITY_DAMAGE",
                "ENTITY_SPAWN", "ENTITY_EXPLODE", "ENTITY_REMOVE", "BLOCK_INTERACT", "CHAT", "COMMAND",
                "ITEM_PICKUP", "ITEM_DROP").contains(normalizeLookupAction(value));
    }

    private static IllegalArgumentException lookupFilterValueMissing(String token) {
        return new IllegalArgumentException("" + token + " braucht einen Wert. "
                + "Beispiel: --player Steve oder --radius 10.");
    }

    private static IllegalArgumentException unknownLookupFilter(String key) {
        return new IllegalArgumentException("Unbekannter Lookup-Filter: " + key
                + ". Erlaubt sind radius, limit, player, action, source, target, time, since und until.");
    }

    private static boolean isLookupOptionKey(String value) {
        return switch (normalizeLookupKey(value)) {
            case "radius", "limit", "actor", "action", "source", "target", "time", "since", "until" -> true;
            default -> false;
        };
    }

    private static String normalizeLookupKey(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "r", "radius", "weite", "bereich" -> "radius";
            case "limit", "max", "anzahl", "menge" -> "limit";
            case "player", "spieler", "actor", "user", "verursacher" -> "actor";
            case "action", "aktion", "event", "ereignis", "type", "typ" -> "action";
            case "source", "quelle", "module", "modul" -> "source";
            case "target", "ziel", "block", "gegenstand" -> "target";
            case "time", "duration", "zeit", "letzte" -> "time";
            case "since", "from", "ab", "seit" -> "since";
            case "until", "to", "before", "bis" -> "until";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    private static String normalizeLookupAction(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return switch (normalized) {
            case "break", "blockbreak", "abbau", "abbauen", "entfernen" -> "BLOCK_BREAK";
            case "place", "blockplace", "platzieren", "setzen" -> "BLOCK_PLACE";
            case "flow", "water", "lava", "fluss", "fliessen" -> "BLOCK_FLOW";
            case "burn", "feuer", "verbrennen" -> "BLOCK_BURN";
            case "explosion", "explosionbreak", "explodieren" -> "EXPLOSION_BREAK";
            case "container", "containeritems", "items", "itemchange", "inventarchange" -> "CONTAINER_ITEM_CHANGE";
            case "containeropen", "open", "oeffnen", "öffnen" -> "CONTAINER_OPEN";
            case "death", "tod", "sterben", "entitydeath" -> "ENTITY_DEATH";
            case "damage", "schaden", "entitydamage" -> "ENTITY_DAMAGE";
            case "spawn", "erzeugt", "erzeugen", "entityspawn" -> "ENTITY_SPAWN";
            case "entityexplode", "entityexplosion" -> "ENTITY_EXPLODE";
            case "remove", "entfernt", "entityremove" -> "ENTITY_REMOVE";
            case "interact", "interaction", "benutzen", "interaktion" -> "BLOCK_INTERACT";
            case "chat", "nachricht" -> "CHAT";
            case "command", "commands", "befehl", "befehle" -> "COMMAND";
            case "pickup", "aufheben", "aufgenommen" -> "ITEM_PICKUP";
            case "drop", "ablegen", "fallenlassen" -> "ITEM_DROP";
            default -> value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        };
    }

    private static String normalizeLookupSource(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "block", "blocks", "blöcke", "bloecke" -> "blocks";
            case "container", "containers" -> "containers";
            case "inventory", "inventories", "inventar" -> "inventories";
            case "entity", "entities", "entitys" -> "entities";
            case "world", "welt", "environment", "umgebung" -> "environment";
            case "interaction", "interactions", "interaktion" -> "interactions";
            case "command", "commands", "befehl", "befehle" -> "commands";
            case "chat" -> "chat";
            case "session", "sessions", "spieler" -> "sessions";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    private static String normalizeLookupTarget(String value) {
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.contains(":")) {
            return trimmed;
        }
        Material material = Material.matchMaterial(trimmed);
        if (material != null) {
            return material.getKey().toString();
        }
        try {
            return EntityType.valueOf(trimmed.toUpperCase(Locale.ROOT)).getKey().toString();
        } catch (IllegalArgumentException ignored) {
            return trimmed;
        }
    }

    private static LookupFilters parseFilters(String[] args, int start,
                                              String actorName, String action,
                                              String source, String target) {
        for (int index = start; index < args.length; index++) {
            String token = args[index];
            String key = null;
            String value = null;
            if (token.startsWith("--")) {
                key = token.substring(2);
                if (key.isBlank() || index + 1 >= args.length) {
                    throw new IllegalArgumentException("Filter " + token + " benötigt einen Wert.");
                }
                value = args[++index];
            } else if (token.contains("=")) {
                int separator = token.indexOf('=');
                key = token.substring(0, separator);
                value = token.substring(separator + 1);
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Filter " + key + " benötigt einen Wert.");
                }
            } else if (isFilterKey(token)) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Filter " + token + " benötigt einen Wert.");
                }
                key = token;
                value = args[++index];
            } else if (actorName == null) {
                actorName = token;
                continue;
            } else if (action == null) {
                action = token;
                continue;
            } else {
                throw new IllegalArgumentException("Unbekannter oder überzähliger Filter: " + token);
            }

            switch (normaliseFilterKey(key)) {
                case "actor" -> actorName = value;
                case "action" -> action = normalizeLookupAction(value);
                case "source" -> source = normalizeLookupSource(value);
                case "target" -> target = normalizeLookupTarget(value);
                default -> throw new IllegalArgumentException("Unbekannter Filter: " + key
                        + ". Erlaubt sind player, action, source und target.");
            }
        }
        return new LookupFilters(blankToNull(actorName), blankToNull(action),
                blankToNull(source), blankToNull(target), null, null, null);
    }

    private static boolean isFilterKey(String value) {
        return switch (normaliseFilterKey(value)) {
            case "actor", "action", "source", "target" -> true;
            default -> false;
        };
    }

    private static String normaliseFilterKey(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "player", "spieler", "actor", "user" -> "actor";
            case "action", "aktion" -> "action";
            case "source", "quelle", "module", "modul" -> "source";
            case "target", "ziel", "block" -> "target";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean module(CommandSender sender, String[] args) {
        if (!has(sender, "blockprotect.config")) {
            return true;
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("help") || args[1].equalsIgnoreCase("?"))) {
            moduleHelp(sender);
            return true;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(ChatColor.GOLD + "Module: " + moduleSummary());
            return true;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("set")) {
            sender.sendMessage(ChatColor.YELLOW + "Verwendung: /blockprotect module set <name> <on|off>");
            return true;
        }
        String path = "tracking.modules." + args[2].toLowerCase(Locale.ROOT);
        if (config.value(path) == null) {
            sender.sendMessage(ChatColor.RED + "Unbekanntes Modul: " + args[2]);
            return true;
        }
        String value = args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true") ? "true" :
                args[3].equalsIgnoreCase("off") || args[3].equalsIgnoreCase("false") ? "false" : null;
        if (value == null) {
            sender.sendMessage(ChatColor.RED + "Erwartet on oder off.");
            return true;
        }
        try {
            config.set(path, value);
            sender.sendMessage(ChatColor.GREEN + "Modul " + args[2] + " ist jetzt " + value + ". Änderung ist sofort aktiv.");
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
        }
        return true;
    }

    private static void moduleHelp(CommandSender sender) {
        sender.sendMessage(Component.text("╭─ Module · Aufzeichnungsbereiche", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("│ /bp module list", NamedTextColor.AQUA)
                .append(Component.text("  – aktuellen Status anzeigen", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ /bp module set <name> on|off", NamedTextColor.AQUA)
                .append(Component.text("  – Modul sofort umschalten", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ Beispiele: /bp module set entities on", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("╰─ Namen: blocks, containers, inventories, entities, interactions,", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("   environment, commands, chat, sessions", NamedTextColor.DARK_GRAY));
    }

    private boolean config(CommandSender sender, String[] args) {
        if (!has(sender, "blockprotect.config")) {
            return true;
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("help") || args[1].equalsIgnoreCase("?"))) {
            configHelp(sender);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Verwendung: /blockprotect config <get|set|list> ...");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            String prefix = args.length > 2 ? args[2] : "";
            List<String> keys = config.keys(prefix);
            sender.sendMessage(ChatColor.GOLD + "Einstellungen (" + keys.size() + "):");
            for (String key : keys) {
                sender.sendMessage(ChatColor.GRAY + "- " + key + " = " + config.value(key));
            }
            return true;
        }
        if (action.equals("get") && args.length >= 3) {
            String value = config.value(args[2]);
            sender.sendMessage(value == null
                    ? ChatColor.RED + "Unbekannter Einstellungspfad."
                    : ChatColor.YELLOW + args[2] + ChatColor.GRAY + " = " + value);
            return true;
        }
        if (action.equals("set") && args.length >= 4) {
            String raw = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            try {
                String value = config.set(args[2], raw);
                sender.sendMessage(ChatColor.GREEN + "Gesetzt: " + args[2] + " = " + value);
                sender.sendMessage(ChatColor.GRAY + "Die Änderung ist sofort aktiv und dauerhaft gespeichert.");
            } catch (IllegalArgumentException exception) {
                sender.sendMessage(ChatColor.RED + exception.getMessage());
            }
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Verwendung: /blockprotect config get <path> | set <path> <value> | list [prefix]");
        return true;
    }

    private static void configHelp(CommandSender sender) {
        sender.sendMessage(Component.text("╭─ Config · Einstellungen", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("│ /bp config list [prefix]", NamedTextColor.AQUA)
                .append(Component.text("  – Einstellungen anzeigen", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ /bp config get <path>", NamedTextColor.AQUA)
                .append(Component.text("  – einen Wert nachsehen", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ /bp config set <path> <wert>", NamedTextColor.AQUA)
                .append(Component.text("  – Wert ändern und speichern", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ Beispiel: /bp config set lookup.default-radius 15", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("╰─ Änderungen werden sofort gespeichert.", NamedTextColor.DARK_GRAY));
    }

    private boolean flush(CommandSender sender) {
        if (!has(sender, "blockprotect.config")) {
            return true;
        }
        store.flush().whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                sender.sendMessage(ChatColor.RED + "Flush fehlgeschlagen: " + rootMessage(throwable));
            } else {
                sender.sendMessage(ChatColor.GREEN + "Audit-Queue wurde geleert.");
            }
        }));
        return true;
    }

    private boolean purge(CommandSender sender, String[] args) {
        if (!has(sender, "blockprotect.purge")) {
            return true;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage(ChatColor.YELLOW + "Achtung: /blockprotect purge <tage> confirm löscht alte Audit-Einträge dauerhaft.");
            return true;
        }
        Integer days = integer(args[1]);
        if (days == null || days < 1 || days > 3650) {
            sender.sendMessage(ChatColor.RED + "Tage muss zwischen 1 und 3650 liegen.");
            return true;
        }
        store.purgeOlderThan(days).whenComplete((deleted, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                sender.sendMessage(ChatColor.RED + "Purge fehlgeschlagen: " + rootMessage(throwable));
            } else {
                sender.sendMessage(ChatColor.GREEN + "" + deleted + " Einträge gelöscht.");
            }
        }));
        return true;
    }

    private void sendLookup(Player player, AuditQuery query, String heading, LookupFilters filters) {
        player.sendMessage(Component.text("╭─ BlockProtect · Suche läuft …", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ Bereich: " + heading, NamedTextColor.GRAY));
        player.sendMessage(Component.text("│ Filter: " + filters.describe(), NamedTextColor.YELLOW));
        store.query(query).whenComplete((records, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                player.sendMessage(ChatColor.RED + "Lookup fehlgeschlagen: " + rootMessage(throwable));
                return;
            }
            auditViews.put(player.getUniqueId(), new AuditView(
                    "lookup", heading, null, filters, records, 0));
            showLookupPage(player, 0);
        }));
    }

    private void help(CommandSender sender, String label) {
        String base = "/" + label;
        sender.sendMessage(Component.text("╭─ BlockProtect ─────────────────────────", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("│ Schnellstart: ", NamedTextColor.YELLOW)
                .append(Component.text(base + " gui", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(base + " gui")))
                .append(Component.text(" öffnet das einfache Menü.", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("│ Audit & Nachvollziehbarkeit", NamedTextColor.YELLOW));
        helpLine(sender, base + " lookup",
                "Umgebung mit Standardwerten durchsuchen", base + " lookup ");
        helpLine(sender, base + " lookup gui",
                "Lookup als übersichtliche Inventar-GUI", base + " lookup gui ");
        helpLine(sender, base + " lookup block",
                "angesehenen Block durchsuchen", base + " lookup block ");
        helpLine(sender, base + " lookup help",
                "alle Lookup-Filter mit Beispielen anzeigen", base + " lookup help");
        helpLine(sender, base + " inspect [next|prev|page <nummer>]",
                "Block-Inspect an/aus", base + " inspect");
        helpLine(sender, base + " inspect filter <key> <wert>",
                "Inspect-Filter setzen", base + " inspect filter ");
        helpLine(sender, base + " rollback [filter]",
                "wichtige Änderungen rückgängig machen", base + " rollback ");
        helpLine(sender, base + " restore",
                "letzten Rollback wiederherstellen", base + " restore");
        sender.sendMessage(Component.text("│ Lookup-Filter: --radius --limit --player --action --source --target --time", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("├─ Verwaltung", NamedTextColor.YELLOW));
        helpLine(sender, base + " status", "Status und Module anzeigen", base + " status");
        helpLine(sender, base + " module list|set <name> <on|off>", "Module live schalten", base + " module ");
        helpLine(sender, base + " config get|set|list ...", "Einstellungen live ändern", base + " config ");
        helpLine(sender, base + " flush", "Audit-Queue sofort speichern", base + " flush");
        helpLine(sender, base + " purge <tage> confirm", "alte Logs löschen", base + " purge ");
        sender.sendMessage(Component.text("╰─ Klicke einen Befehl an oder nutze /" + label + " help erneut.", NamedTextColor.DARK_GRAY));
    }

    private static void helpLine(CommandSender sender, String command, String description, String suggestion) {
        sender.sendMessage(Component.text("│ ")
                .append(Component.text(command, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.suggestCommand(suggestion)))
                .append(Component.text("  –  " + description, NamedTextColor.GRAY)));
    }

    private String moduleSummary() {
        List<String> names = List.of("blocks", "containers", "inventories", "entities", "interactions",
                "environment", "commands", "chat", "sessions");
        return String.join(", ", names.stream()
                .map(name -> moduleLabel(name) + "="
                        + (config.getBoolean("tracking.modules." + name, false) ? "AN" : "AUS"))
                .toList());
    }

    private static String moduleLabel(String name) {
        return switch (name) {
            case "blocks" -> "Blöcke";
            case "containers" -> "Container";
            case "inventories" -> "Inventare";
            case "entities" -> "Entities";
            case "interactions" -> "Interaktionen";
            case "environment" -> "Welt";
            case "commands" -> "Befehle";
            case "chat" -> "Chat";
            case "sessions" -> "Sessions";
            default -> name;
        };
    }

    private boolean has(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(ChatColor.RED + "Dafür fehlt dir die Berechtigung: " + permission);
            return false;
        }
        return true;
    }

    private static Integer integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> partial(String input, String... values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return Arrays.stream(values).filter(value -> value.startsWith(lower)).toList();
    }

    private List<String> completeLookup(String[] args) {
        if (args.length == 2) {
            if (args[1].contains("=")) {
                return completeLookupOptions(args, 1);
            }
            return partial(args[1], "help", "gui", "block", "filter", "refresh", "clear",
                    "next", "prev", "page", "--radius", "--limit", "--player", "--action",
                    "--source", "--target", "--time", "--since", "--until");
        }
        if (args[1].equalsIgnoreCase("page")) {
            return partial(args[args.length - 1], "1", "2", "3", "4", "5");
        }
        if (args[1].equalsIgnoreCase("gui")) {
            if (args.length == 3 && args[2].toLowerCase(Locale.ROOT).startsWith("b")) {
                return partial(args[2], "block");
            }
            int start = args.length > 2 && args[2].equalsIgnoreCase("block") ? 3 : 2;
            return completeLookupOptions(args, start);
        }
        if (args[1].equalsIgnoreCase("block")) {
            if (args.length == 3 && args[2].toLowerCase(Locale.ROOT).startsWith("g")) {
                return partial(args[2], "gui");
            }
            int start = args.length > 2 && args[2].equalsIgnoreCase("gui") ? 3 : 2;
            return completeLookupOptions(args, start);
        }
        return completeLookupOptions(args, 1);
    }

    private List<String> completeLookupOptions(String[] args, int start) {
        String[] keys = {"--radius", "--limit", "--player", "--action", "--source", "--target",
                "--time", "--since", "--until"};
        if (args.length <= start) {
            return Arrays.asList(keys);
        }

        int last = args.length - 1;
        String current = args[last];
        String previous = last > start ? args[last - 1] : null;
        String previousKey = previous == null ? null
                : normalizeLookupKey(previous.startsWith("--") ? previous.substring(2) : previous);
        if (previous != null
                && isLookupOptionKey(previous.startsWith("--") ? previous.substring(2) : previous)
                && !previous.contains("=")) {
            return lookupValueSuggestions(previousKey, current);
        }

        int equals = current.indexOf('=');
        if (equals > 0) {
            String rawKey = current.substring(0, equals);
            String prefix = current.substring(equals + 1);
            String key = normalizeLookupKey(rawKey.startsWith("--") ? rawKey.substring(2) : rawKey);
            return lookupValueSuggestions(key, prefix).stream()
                    .map(value -> rawKey + "=" + value)
                    .toList();
        }
        if (current.startsWith("--")) {
            return partial(current, keys);
        }
        return partial(current, "radius", "limit", "player", "action", "source", "target",
                "time", "since", "until", "2h", "7d");
    }

    private List<String> lookupValueSuggestions(String key, String input) {
        String[] values = switch (key) {
            case "radius" -> new String[]{"5", "10", "20", "50"};
            case "limit" -> new String[]{"25", "50", "100", "250"};
            case "action" -> new String[]{"break", "place", "death", "damage", "spawn", "explosion",
                    "entity-explosion", "flow", "container", "open", "command", "chat"};
            case "source" -> new String[]{"blocks", "containers", "inventories", "entities", "environment",
                    "interactions", "commands", "chat", "sessions"};
            case "time", "since", "until" -> new String[]{"30m", "2h", "7d", "1w", "all"};
            case "actor" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toArray(String[]::new);
            case "target" -> new String[]{"stone", "chest", "zombie", "skeleton", "creeper", "player"};
            default -> new String[0];
        };
        return partial(input, values);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private enum GuiScreen {
        MAIN, STATUS, RESULTS, DETAIL, FILTER
    }

    private static final class GuiHolder implements InventoryHolder {
        private final UUID owner;
        private final GuiScreen screen;
        private final String mode;
        private final int page;
        private final AuditRecord record;
        private Inventory inventory;

        private GuiHolder(UUID owner, GuiScreen screen, String mode, int page, AuditRecord record) {
            this.owner = owner;
            this.screen = screen;
            this.mode = mode;
            this.page = page;
            this.record = record;
        }

        private UUID owner() {
            return owner;
        }

        private GuiScreen screen() {
            return screen;
        }

        private String mode() {
            return mode;
        }

        private int page() {
            return page;
        }

        private AuditRecord record() {
            return record;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record AuditView(String mode, String context, Location location,
                             LookupFilters filter, List<AuditRecord> records, int page) {
    }

    private record RollbackOptions(int radius, int limit, int confirmSeconds,
                                   String actorName, String action,
                                   Long sinceTimestamp, Long untilTimestamp,
                                   String timeLabel) {
    }

    private record PendingRollback(List<AuditRecord> records,
                                   RollbackOptions options,
                                   long expiresAt) {
    }

    private record RollbackChange(AuditRecord record, String previousState,
                                  Map<Integer, String> previousInventory) {
    }

    private record AppliedRollback(List<RollbackChange> changes, long appliedAt) {
    }

    private record PendingRestore(List<RollbackChange> changes, long expiresAt) {
    }

    private record TimeValue(Long timestamp) {
    }

    private record LookupOptions(int radius, int limit, LookupFilters filters) {
    }

    private record LookupFilters(String actorName, String action, String source, String target,
                                 Long sinceTimestamp, Long untilTimestamp, String timeDescription) {
        private static LookupFilters empty() {
            return new LookupFilters(null, null, null, null, null, null, null);
        }

        private boolean isEmpty() {
            return actorName == null && action == null && source == null && target == null
                    && sinceTimestamp == null && untilTimestamp == null;
        }

        private String describe() {
            StringBuilder result = new StringBuilder();
            append(result, "Spieler", actorName);
            append(result, "Aktion", lookupActionLabel(action));
            append(result, "Quelle", source == null ? null : moduleLabel(source));
            append(result, "Ziel", displayLookupTarget(target));
            append(result, "Zeit", timeDescription);
            return result.isEmpty() ? "keine (alle Ereignisse)" : result.toString();
        }

        private static void append(StringBuilder result, String key, String value) {
            if (value == null) {
                return;
            }
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append(key).append("=").append(value);
        }

        private static String displayLookupTarget(String target) {
            if (target == null) {
                return null;
            }
            return target.startsWith("minecraft:") ? target.substring("minecraft:".length()) : target;
        }
    }
}
