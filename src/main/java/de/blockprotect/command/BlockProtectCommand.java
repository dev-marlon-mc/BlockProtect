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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
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
    private static final DateTimeFormatter INSPECT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int INSPECT_PAGE_SIZE = 8;
    private static final int ROLLBACK_PREVIEW_SIZE = 8;
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
                    "module", "config", "flush", "purge");
        }
        if (args[0].equalsIgnoreCase("inspect") && args.length == 2) {
            return partial(args[1], "next", "prev", "page", "filter");
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
        if ((args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("l")) && args.length == 2) {
            return partial(args[1], "block", "next", "prev", "page", "--player", "--action", "--source", "--target");
        }
        if ((args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("l"))
                && args.length == 3 && args[1].equalsIgnoreCase("page")) {
            return partial(args[2], "1", "2", "3");
        }
        if ((args[0].equalsIgnoreCase("rollback") || args[0].equalsIgnoreCase("rb")) && args.length == 2) {
            return partial(args[1], "confirm", "cancel", "undo", "--radius", "--time", "--since",
                    "--until", "--player", "--action", "--limit");
        }
        if ((args[0].equalsIgnoreCase("restore") || args[0].equalsIgnoreCase("redo")) && args.length == 2) {
            return partial(args[1], "confirm", "cancel");
        }
        if (args[0].equalsIgnoreCase("module") && args.length == 2) {
            return partial(args[1], "list", "set");
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
                return partial(args[1], "get", "set", "list");
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
            auditViews.put(player.getUniqueId(), new AuditView(
                    "inspect", null, location.clone(), filters, records, 0));
            showInspectPage(player, 0);
        }));
    }

    private boolean status(CommandSender sender) {
        if (!has(sender, "blockprotect.lookup")) {
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "BlockProtect Status");
        sender.sendMessage(ChatColor.GRAY + "Tracking: " + (config.getBoolean("tracking.enabled", true) ? ChatColor.GREEN + "aktiv" : ChatColor.RED + "deaktiviert"));
        sender.sendMessage(ChatColor.GRAY + "Queue: " + store.queuedCount() + " Events");
        sender.sendMessage(ChatColor.GRAY + "Verworfen wegen Queue-Limit: " + store.droppedCount());
        sender.sendMessage(ChatColor.GRAY + "Database: " + config.getString("storage.database", "blockprotect.db"));
        sender.sendMessage(ChatColor.GRAY + "Module: " + moduleSummary());
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Unterbefehl ist nur ingame verfügbar.");
            return true;
        }
        if (!has(sender, "blockprotect.inspect")) {
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
            inspectFilters.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "BlockProtect Inspect ist deaktiviert.");
        } else {
            player.sendMessage(ChatColor.GREEN + "BlockProtect Inspect ist aktiv. Rechtsklick auf einen Block oder platziere einen Block, um die Luftposition zu prüfen. Die Platzierung wird verhindert.");
        }
        return true;
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
        if (view.records().isEmpty()) {
            player.sendMessage(Component.text(
                    mode.equals("inspect")
                            ? "Keine Audit-Einträge an diesem Block."
                            : "Keine Audit-Einträge im gewählten Bereich.",
                    NamedTextColor.GRAY));
            return;
        }

        int pages = (view.records().size() + INSPECT_PAGE_SIZE - 1) / INSPECT_PAGE_SIZE;
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        auditViews.put(player.getUniqueId(), new AuditView(
                view.mode(), view.context(), view.location(), view.filter(), view.records(), page));

        String context = view.location() == null ? view.context() : locationText(view.location());
        String title = mode.equals("inspect") ? "BlockProtect Inspect" : "BlockProtect Lookup";
        String command = mode.equals("inspect") ? "/bp inspect " : "/bp lookup ";
        player.sendMessage(Component.text("╭─ " + title + " ─────────────────", NamedTextColor.GOLD));
        player.sendMessage(Component.text("│ " + context + "  ·  " + view.records().size()
                + " Einträge  ·  neueste zuerst", NamedTextColor.GRAY));
        if (!view.filter().isEmpty()) {
            player.sendMessage(Component.text("│ Filter: " + view.filter().describe(), NamedTextColor.YELLOW));
        }

        int start = page * INSPECT_PAGE_SIZE;
        int end = Math.min(view.records().size(), start + INSPECT_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            player.sendMessage(formatInspect(view.records().get(index)));
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
    }

    private static String locationText(Location location) {
        return location.getWorld().getName() + " @ "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private Component formatInspect(AuditRecord record) {
        String actor = fixed(record.actorName() == null ? "Umgebung" : record.actorName(), 12);
        boolean itemChange = record.action().equals("CONTAINER_ITEM_CHANGE")
                || record.action().equals("CONTAINER_SNAPSHOT");
        String action = fixed(itemChange ? containerChangeAction(record) : shortAction(record.action()), 10);
        String target = fixed(itemChange ? containerChangeTarget(record) : shortTarget(record.target()), 24);
        String amount = itemChange || record.amount() == 0
                ? "" : "  Δ" + (record.amount() > 0 ? "+" : "") + record.amount();
        String details = inspectDetails(record);

        Component line = Component.text("│ " + INSPECT_TIME.format(Instant.ofEpochMilli(record.timestamp())) + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text(actor, NamedTextColor.WHITE))
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(Component.text(action, actionColor(record.action())))
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(Component.text(target, NamedTextColor.GRAY))
                .append(Component.text(amount, NamedTextColor.YELLOW));
        if (!details.isBlank()) {
            line = line.hoverEvent(HoverEvent.showText(Component.text(details, NamedTextColor.GRAY)));
        }
        return line;
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
            case "ITEM_PICKUP" -> "PICKUP";
            case "ITEM_DROP" -> "DROP";
            case "BLOCK_FLOW" -> "FLUSS";
            case "BUCKET_EMPTY", "BUCKET_FILL" -> "EIMER";
            case "ENTITY_DAMAGE" -> "SCHADEN";
            case "ENTITY_DEATH" -> "TOD";
            case "ENTITY_SPAWN" -> "SPAWN";
            case "BLOCK_INTERACT", "ENTITY_INTERACT" -> "INTERAKTION";
            case "EXPLOSION_BREAK" -> "EXPLOSION";
            default -> action.length() <= 10 ? action : action.substring(0, 10);
        };
    }

    private static NamedTextColor actionColor(String action) {
        if (action == null) {
            return NamedTextColor.GRAY;
        }
        return switch (action) {
            case "BLOCK_BREAK", "ENTITY_DAMAGE", "ENTITY_DEATH", "EXPLOSION_BREAK" -> NamedTextColor.RED;
            case "BLOCK_PLACE", "CONTAINER_OPEN", "ITEM_PICKUP" -> NamedTextColor.GREEN;
            case "CONTAINER_ITEM_CHANGE", "CONTAINER_SNAPSHOT" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.AQUA;
        };
    }

    private static String shortTarget(String target) {
        if (target == null || target.isBlank()) {
            return "-";
        }
        String value = target;
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
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

    private static String displayItemState(String state) {
        if (state == null || state.isBlank()) {
            return "leer";
        }
        int separator = state.lastIndexOf('x');
        if (separator > 0 && separator + 1 < state.length()) {
            Integer amount = integer(state.substring(separator + 1));
            if (amount != null) {
                return amount + "x " + shortTarget(state.substring(0, separator));
            }
        }
        return shortTarget(state);
    }

    private static String valueOrQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String fixed(String value, int width) {
        String clipped = value.length() <= width ? value : value.substring(0, Math.max(0, width - 1)) + "…";
        return String.format(Locale.ROOT, "%-" + width + "s", clipped);
    }

    private boolean lookup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Lookup benötigt eine Spielerposition; führe den Befehl ingame aus.");
            return true;
        }
        if (!has(sender, "blockprotect.lookup")) {
            return true;
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
                LookupFilters filters = parseFilters(args, 2, null, null, null, null);
                int limit = Math.max(1, Math.min(config.getInt("lookup.max-limit", 500),
                        config.getInt("lookup.default-limit", 50)));
                String heading = "Block " + target.getWorld().getName() + " @ "
                        + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ();
                sendLookup(player, exactQuery(target, filters, limit),
                        heading, filters);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(ChatColor.RED + exception.getMessage());
            }
            return true;
        }

        int radius = config.getInt("lookup.default-radius", 10);
        int limit = config.getInt("lookup.default-limit", 50);
        int index = 1;
        if (args.length > index) {
            Integer parsed = integer(args[index]);
            if (parsed != null) {
                radius = Math.max(0, parsed);
                index++;
            }
        }
        if (args.length > index) {
            Integer parsed = integer(args[index]);
            if (parsed != null) {
                limit = parsed;
                index++;
            }
        }
        LookupFilters filters;
        try {
            filters = parseFilters(args, index, null, null, null, null);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }

        int maxLimit = config.getInt("lookup.max-limit", 500);
        limit = Math.max(1, Math.min(maxLimit, limit));
        radius = Math.min(10_000, radius);
        Location location = player.getLocation();
        AuditQuery query = new AuditQuery(
                location.getWorld().getName(),
                location.getBlockX() - radius, location.getBlockX() + radius,
                location.getBlockY() - radius, location.getBlockY() + radius,
                location.getBlockZ() - radius, location.getBlockZ() + radius,
                filters.actorName(), filters.action(), filters.source(), filters.target(), limit
        );
        sendLookup(player, query, "Radius=" + radius, filters);
        return true;
    }

    private boolean rollback(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Rollback benötigt eine Spielerposition; führe den Befehl ingame aus.");
            return true;
        }
        if (!has(sender, "blockprotect.rollback")) {
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
            player.sendMessage(formatInspect(records.get(index)));
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
            player.sendMessage(formatInspect(pending.changes().get(index).record()));
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
            inventory.setItem(slot, parseItem(change.previousState()));
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
        ItemStack serialized = deserializeItem(detailValue(record.details(), "stack"));
        return serialized == null ? parseItem(state) : serialized;
    }

    private static ItemStack parseItem(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        if (state.startsWith("serialized:")) {
            return deserializeItem(state.substring("serialized:".length()));
        }
        int separator = state.lastIndexOf('x');
        if (separator <= 0 || separator + 1 >= state.length()) {
            return null;
        }
        Integer parsedAmount = integer(state.substring(separator + 1));
        if (parsedAmount == null || parsedAmount <= 0) {
            return null;
        }
        int amount = parsedAmount;
        String key = state.substring(0, separator);
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
        return item.getType().getKey() + "x" + item.getAmount();
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
                filters.actorName(), filters.action(), filters.source(), filters.target(), limit
        );
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
                case "action" -> action = value;
                case "source" -> source = value;
                case "target" -> target = value;
                default -> throw new IllegalArgumentException("Unbekannter Filter: " + key
                        + ". Erlaubt sind player, action, source und target.");
            }
        }
        return new LookupFilters(blankToNull(actorName), blankToNull(action),
                blankToNull(source), blankToNull(target));
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

    private boolean config(CommandSender sender, String[] args) {
        if (!has(sender, "blockprotect.config")) {
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
        player.sendMessage(Component.text("BlockProtect Lookup – " + heading + " · Abfrage läuft …", NamedTextColor.GRAY));
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
        sender.sendMessage(Component.text("│ Audit & Nachvollziehbarkeit", NamedTextColor.YELLOW));
        helpLine(sender, base + " lookup [radius] [limit] [spieler] [aktion] [filter]",
                "Bereich durchsuchen", base + " lookup ");
        helpLine(sender, base + " lookup block [filter]",
                "angesehenen Block durchsuchen", base + " lookup block ");
        helpLine(sender, base + " inspect [next|prev|page <nummer>]",
                "Block-Inspect an/aus", base + " inspect");
        helpLine(sender, base + " inspect filter <key> <wert>",
                "Inspect-Filter setzen", base + " inspect filter ");
        helpLine(sender, base + " rollback [filter]",
                "wichtige Änderungen rückgängig machen", base + " rollback ");
        helpLine(sender, base + " restore",
                "letzten Rollback wiederherstellen", base + " restore");
        sender.sendMessage(Component.text("│ Filter: --player --action --source --target · Zeit: 30m, 2h, 7d", NamedTextColor.GRAY));
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
                .map(name -> name + "=" + (config.getBoolean("tracking.modules." + name, false) ? "on" : "off"))
                .toList());
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

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
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

    private record LookupFilters(String actorName, String action, String source, String target) {
        private static LookupFilters empty() {
            return new LookupFilters(null, null, null, null);
        }

        private boolean isEmpty() {
            return actorName == null && action == null && source == null && target == null;
        }

        private String describe() {
            StringBuilder result = new StringBuilder();
            append(result, "player", actorName);
            append(result, "action", action);
            append(result, "source", source);
            append(result, "target", target);
            return result.toString();
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
    }
}
