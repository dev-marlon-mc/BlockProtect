package de.blockprotect.audit;

import de.blockprotect.storage.AuditRecord;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class BlockAuditModule implements AuditModule {
    private final AuditRecorder recorder;

    public BlockAuditModule(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String id() {
        return "blocks";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        recordContainerSnapshot(event, event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                "BLOCK_BREAK", block);
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "BLOCK_BREAK",
                block.getLocation(), AuditUtil.blockType(block), 0, null,
                AuditUtil.blockState(block), null,
                AuditUtil.details("tool", AuditUtil.item(event.getPlayer().getInventory().getItemInMainHand()))
        ));
    }

    private void recordContainerSnapshot(Event event, java.util.UUID actorUuid, String actorName,
                                         String rollbackAction, Block block) {
        if (event instanceof Cancellable cancellable && cancellable.isCancelled()) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder holder)) {
            return;
        }
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            recorder.record(id(), event, AuditRecorder.at(
                    actorUuid, actorName, id(), "CONTAINER_SNAPSHOT",
                    block.getLocation(), AuditUtil.inventoryType(inventory), item.getAmount(), AuditUtil.item(item),
                    AuditUtil.item(item), null,
                    AuditUtil.details("slot", slot, "container", AuditUtil.inventoryType(inventory),
                            "snapshot", true, "origin-action", rollbackAction,
                            "stack", AuditUtil.itemData(item))
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multiPlace) {
            for (BlockState replaced : multiPlace.getReplacedBlockStates()) {
                Block block = replaced.getBlock();
                recorder.record(id(), event, AuditRecorder.at(
                        event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "BLOCK_PLACE",
                        block.getLocation(), AuditUtil.blockType(block), event.getItemInHand().getAmount(),
                        AuditUtil.item(event.getItemInHand()),
                        replaced.getType().getKey() + " " + replaced.getBlockData().getAsString(),
                        AuditUtil.blockState(block),
                        AuditUtil.details("hand", event.getHand(), "multi", true)
                ));
            }
            return;
        }

        Block block = event.getBlockPlaced();
        BlockState replaced = event.getBlockReplacedState();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "BLOCK_PLACE",
                block.getLocation(), AuditUtil.blockType(block), event.getItemInHand().getAmount(),
                AuditUtil.item(event.getItemInHand()),
                replaced.getType().getKey() + " " + replaced.getBlockData().getAsString(),
                AuditUtil.blockState(block),
                AuditUtil.details("hand", event.getHand())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        for (Block block : event.blockList()) {
            recordContainerSnapshot(event, null, null, "EXPLOSION_BREAK", block);
            recorder.record("environment", event, AuditRecorder.at(
                    null, null, "environment", "EXPLOSION_BREAK", block.getLocation(),
                    AuditUtil.blockType(block), 0, null, AuditUtil.blockState(block), null,
                    AuditUtil.details("source", "block", "origin", event.getBlock().getLocation())
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        Entity source = event.getEntity();
        for (Block block : event.blockList()) {
            recordContainerSnapshot(event, null, null, "EXPLOSION_BREAK", block);
            recorder.record("environment", event, AuditRecorder.at(
                    null, null, "environment", "EXPLOSION_BREAK", block.getLocation(),
                    AuditUtil.blockType(block), 0, null, AuditUtil.blockState(block), null,
                    AuditUtil.details("source", AuditUtil.entityType(source), "origin", source.getLocation())
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockBurn(BlockBurnEvent event) {
        environment(event, "BLOCK_BURN", event.getBlock(), null, null, "cause", "fire");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockFade(BlockFadeEvent event) {
        environment(event, "BLOCK_FADE", event.getBlock(), null, null, "cause", "world");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockForm(BlockFormEvent event) {
        environment(event, "BLOCK_FORM", event.getBlock(), null, AuditUtil.blockState(event.getNewState().getBlock()),
                "new-state", event.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockGrow(BlockGrowEvent event) {
        environment(event, "BLOCK_GROW", event.getBlock(), null, AuditUtil.blockState(event.getNewState().getBlock()),
                "new-state", event.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockSpread(BlockSpreadEvent event) {
        environment(event, "BLOCK_SPREAD", event.getBlock(), null, AuditUtil.blockState(event.getNewState().getBlock()),
                "source", event.getSource().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block destination = event.getToBlock();
        Block source = event.getBlock();
        recorder.record("environment", event, AuditRecorder.at(
                null, null, "environment", "BLOCK_FLOW", destination.getLocation(),
                AuditUtil.blockType(source), 0, null,
                AuditUtil.blockState(destination), AuditUtil.blockState(source),
                AuditUtil.details("from", source.getLocation())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        for (Block moved : event.getBlocks()) {
            recorder.record("environment", event, AuditAudit.piston(
                    event.getBlock(), moved, "PISTON_EXTEND", event.getDirection().name()
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        for (Block moved : event.getBlocks()) {
            recorder.record("environment", event, AuditAudit.piston(
                    event.getBlock(), moved, "PISTON_RETRACT", event.getDirection().name()
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        for (org.bukkit.entity.Item itemEntity : event.getItems()) {
            ItemStack item = itemEntity.getItemStack();
            recorder.record(id(), event, AuditRecorder.at(
                    player.getUniqueId(), player.getName(), id(), "BLOCK_DROP", event.getBlock().getLocation(),
                    AuditUtil.blockType(event.getBlock()), item.getAmount(), AuditUtil.item(item),
                    null, null, AuditUtil.details("drop", itemEntity.getUniqueId())
            ));
        }
    }

    private void environment(org.bukkit.event.Event event, String action, Block block, BlockState before,
                             String after, Object detailKey, Object detailValue) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        if (after == null) {
            recordContainerSnapshot(event, null, null, action, block);
        }
        String beforeState = before == null ? AuditUtil.blockState(block) : AuditUtil.blockState(before.getBlock());
        recorder.record("environment", event, AuditRecorder.at(
                null, null, "environment", action, block.getLocation(), AuditUtil.blockType(block), 0, null,
                beforeState, after, AuditUtil.details(detailKey, detailValue)
        ));
    }

    private static final class AuditAudit {
        private static AuditRecord piston(Block piston, Block moved, String action, String direction) {
            Location location = moved.getLocation();
            return AuditRecorder.at(
                    null, null, "environment", action, location, AuditUtil.blockType(moved), 0, null,
                    AuditUtil.blockState(moved), null,
                    AuditUtil.details("piston", piston.getLocation(), "direction", direction)
            );
        }
    }
}
