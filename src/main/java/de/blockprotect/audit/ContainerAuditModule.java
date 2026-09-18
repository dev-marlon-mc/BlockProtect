package de.blockprotect.audit;

import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ContainerAuditModule implements AuditModule {
    private final AuditRecorder recorder;
    private final Map<UUID, ContainerSession> sessions = new ConcurrentHashMap<>();

    public ContainerAuditModule(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String id() {
        return "containers";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        sessions.put(player.getUniqueId(), ContainerSession.capture(player.getUniqueId(), event.getView().getTopInventory()));
        recorder.record(id(), event, AuditRecorder.at(
                player.getUniqueId(), player.getName(), id(), "CONTAINER_OPEN", AuditUtil.location(inventory),
                AuditUtil.inventoryType(inventory), 0, null, null, null,
                AuditUtil.details("title", event.getView().getTitle(), "holder", inventory.getHolder())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        ContainerSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        Inventory inventory = event.getInventory();
        Map<Integer, ItemSnapshot> after = ContainerSession.snapshot(inventory);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemSnapshot beforeItem = session.items().get(slot);
            ItemSnapshot afterItem = after.get(slot);
            if (java.util.Objects.equals(beforeItem, afterItem)) {
                continue;
            }
            int beforeAmount = beforeItem == null ? 0 : beforeItem.amount();
            int afterAmount = afterItem == null ? 0 : afterItem.amount();
            ItemSnapshot representative = afterItem == null ? beforeItem : afterItem;
            recorder.record("inventories", event, AuditRecorder.at(
                    player.getUniqueId(), player.getName(), "inventories", "CONTAINER_ITEM_CHANGE", session.location(),
                    session.type(), afterAmount - beforeAmount,
                    representative == null ? null : representative.item(),
                    beforeItem == null ? null : beforeItem.item(),
                    afterItem == null ? null : afterItem.item(),
                    AuditUtil.details("slot", slot, "before", beforeItem, "after", afterItem,
                            "before-stack", beforeItem == null ? null : beforeItem.data(),
                            "after-stack", afterItem == null ? null : afterItem.data())
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        Inventory inventory = event.getClickedInventory() == null
                ? event.getView().getTopInventory()
                : event.getClickedInventory();
        recorder.record("inventories", event, AuditRecorder.at(
                player.getUniqueId(), player.getName(), "inventories", "INVENTORY_CLICK", AuditUtil.location(inventory),
                AuditUtil.inventoryType(inventory), current == null ? 0 : current.getAmount(),
                AuditUtil.item(current), null, null,
                AuditUtil.details("action", event.getAction(), "click", event.getClick(),
                        "slot", event.getRawSlot(), "cursor", AuditUtil.item(cursor))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack oldCursor = event.getOldCursor();
        recorder.record("inventories", event, AuditRecorder.at(
                player.getUniqueId(), player.getName(), "inventories", "INVENTORY_DRAG", AuditUtil.location(event.getInventory()),
                AuditUtil.inventoryType(event.getInventory()), oldCursor.getAmount(), AuditUtil.item(oldCursor),
                null, null, AuditUtil.details("slots", event.getRawSlots(), "new-cursor", AuditUtil.item(event.getCursor()))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        recorder.record("inventories", event, AuditRecorder.at(
                null, null, "inventories", "INVENTORY_MOVE", AuditUtil.location(event.getSource()),
                AuditUtil.inventoryType(event.getSource()), item.getAmount(), AuditUtil.item(item), null, null,
                AuditUtil.details("source", AuditUtil.location(event.getSource()),
                        "destination", AuditUtil.location(event.getDestination()),
                        "initiator", AuditUtil.location(event.getInitiator()))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        recorder.record("inventories", event, AuditRecorder.at(
                null, null, "inventories", "INVENTORY_PICKUP", AuditUtil.location(event.getInventory()),
                AuditUtil.inventoryType(event.getInventory()), item.getAmount(), AuditUtil.item(item), null, null,
                AuditUtil.details("item-entity", event.getItem().getUniqueId())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        recorder.record("inventories", event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), "inventories", "FURNACE_EXTRACT",
                event.getBlock().getLocation(), AuditUtil.blockType(event.getBlock()), event.getItemAmount(),
                event.getItemType().getKey().toString(), null, null,
                AuditUtil.details("exp", event.getExpToDrop())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getRecipe() == null ? null : event.getRecipe().getResult();
        recorder.record("inventories", event, AuditRecorder.at(
                player.getUniqueId(), player.getName(), "inventories", "CRAFT", AuditUtil.location(event.getInventory()),
                AuditUtil.inventoryType(event.getInventory()), result == null ? 0 : result.getAmount(),
                AuditUtil.item(result), null, null, AuditUtil.details("action", event.getAction())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBrew(BrewEvent event) {
        recorder.record("inventories", event, AuditRecorder.at(
                null, null, "inventories", "BREW", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), 0, null, null, null,
                AuditUtil.details("results", event.getResults())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDispense(BlockDispenseEvent event) {
        ItemStack item = event.getItem();
        recorder.record("inventories", event, AuditRecorder.at(
                null, null, "inventories", "DISPENSE", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), item.getAmount(), AuditUtil.item(item), null, null,
                AuditUtil.details("velocity", event.getVelocity())
        ));
    }

    private record ContainerSession(UUID player, Inventory inventory, String type, org.bukkit.Location location,
                                    Map<Integer, ItemSnapshot> items) {
        private static ContainerSession capture(UUID player, Inventory inventory) {
            return new ContainerSession(player, inventory, AuditUtil.inventoryType(inventory),
                    AuditUtil.location(inventory), snapshot(inventory));
        }

        private static Map<Integer, ItemSnapshot> snapshot(Inventory inventory) {
            Map<Integer, ItemSnapshot> result = new HashMap<>();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemSnapshot item = ItemSnapshot.of(inventory.getItem(slot));
                if (item != null) {
                    result.put(slot, item);
                }
            }
            return result;
        }
    }

    private record ItemSnapshot(String item, int amount, String data) {
        private static ItemSnapshot of(ItemStack itemStack) {
            if (itemStack == null || itemStack.getType().isAir()) {
                return null;
            }
            return new ItemSnapshot(AuditUtil.item(itemStack), itemStack.getAmount(), AuditUtil.itemData(itemStack));
        }
    }
}
