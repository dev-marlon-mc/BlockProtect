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
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
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
        if (event instanceof EntityBlockFormEvent entityForm) {
            if (!recorder.option("tracking.options.log-environment", true)) {
                return;
            }
            recorder.record("environment", event, AuditRecorder.at(
                    null, null, "environment", "ENTITY_BLOCK_FORM", event.getBlock().getLocation(),
                    AuditUtil.blockType(event.getBlock()), 0, null, AuditUtil.blockState(event.getBlock()),
                    AuditUtil.blockState(event.getNewState()), AuditUtil.details(
                            "entity", AuditUtil.entityType(entityForm.getEntity()),
                            "new-state", event.getNewState().getType()
                    )));
            return;
        }
        environment(event, "BLOCK_FORM", event.getBlock(), null, AuditUtil.blockState(event.getNewState()),
                "new-state", event.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockGrow(BlockGrowEvent event) {
        environment(event, "BLOCK_GROW", event.getBlock(), null, AuditUtil.blockState(event.getNewState()),
                "new-state", event.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockSpread(BlockSpreadEvent event) {
        environment(event, "BLOCK_SPREAD", event.getBlock(), null, AuditUtil.blockState(event.getNewState()),
                "source", event.getSource().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        Entity source = event.getIgnitingEntity();
        Player player = event.getPlayer();
        recorder.record("environment", event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(),
                "environment", "BLOCK_IGNITE", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), 0, null, AuditUtil.blockState(event.getBlock()),
                "minecraft:fire", AuditUtil.details(
                        "cause", event.getCause(),
                        "entity", source == null ? null : AuditUtil.entityType(source),
                        "block", event.getIgnitingBlock() == null ? null : event.getIgnitingBlock().getLocation()
                )));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTntPrime(TNTPrimeEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        Entity source = event.getPrimingEntity();
        Player player = source instanceof Player possiblePlayer ? possiblePlayer : null;
        recorder.record("environment", event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(),
                "environment", "TNT_PRIME", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), 1, null, AuditUtil.blockState(event.getBlock()),
                "minecraft:tnt_entity", AuditUtil.details(
                        "cause", event.getCause(),
                        "entity", source == null ? null : AuditUtil.entityType(source),
                        "block", event.getPrimingBlock() == null ? null : event.getPrimingBlock().getLocation()
                )));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        Player player = event.getPlayer();
        for (BlockState state : event.getBlocks()) {
            Block block = state.getBlock();
            recorder.record("environment", event, AuditRecorder.at(
                    player == null ? null : player.getUniqueId(), player == null ? null : player.getName(),
                    "environment", "BLOCK_FERTILIZE", block.getLocation(), AuditUtil.blockType(block), 0, null,
                    AuditUtil.blockState(block), AuditUtil.blockState(state),
                    AuditUtil.details("source", "fertilizer", "origin", event.getBlock().getLocation())
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLeavesDecay(LeavesDecayEvent event) {
        environment(event, "LEAVES_DECAY", event.getBlock(), null, null, "cause", "natural");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMoistureChange(MoistureChangeEvent event) {
        environment(event, "MOISTURE_CHANGE", event.getBlock(), null,
                AuditUtil.blockState(event.getNewState()), "cause", "world");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCauldronLevelChange(CauldronLevelChangeEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        Entity source = event.getEntity();
        Player player = source instanceof Player possiblePlayer ? possiblePlayer : null;
        recorder.record("environment", event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(),
                "environment", "CAULDRON_CHANGE", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), event.getNewLevel() - event.getOldLevel(), null,
                AuditUtil.blockState(event.getBlock()), AuditUtil.blockState(event.getNewState()),
                AuditUtil.details("reason", event.getReason(), "old-level", event.getOldLevel(),
                        "new-level", event.getNewLevel(), "entity", source == null ? null : AuditUtil.entityType(source))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        for (BlockState state : event.getBlocks()) {
            Block block = state.getBlock();
            recorder.record("environment", event, AuditRecorder.at(
                    null, null, "environment", "SPONGE_ABSORB", block.getLocation(),
                    AuditUtil.blockType(block), 0, null, AuditUtil.blockState(state), "minecraft:air",
                    AuditUtil.details("sponge", event.getBlock().getLocation())
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        Block block = event.getBlock();
        recorder.record("environment", event, AuditRecorder.at(
                null, null, "environment", "FLUID_LEVEL_CHANGE", block.getLocation(),
                AuditUtil.blockType(block), 0, null, AuditUtil.blockState(block),
                event.getNewData().getMaterial().getKey().toString(), null
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockCook(BlockCookEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        recorder.record("environment", event, AuditRecorder.at(
                null, null, "environment", "BLOCK_COOK", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), event.getResult() == null ? 0 : event.getResult().getAmount(),
                AuditUtil.item(event.getResult()), AuditUtil.item(event.getSource()), AuditUtil.item(event.getResult()),
                AuditUtil.details("recipe", event.getRecipe() == null ? null : event.getRecipe().getKey())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
        recorder.record("environment", event, AuditRecorder.at(
                null, null, "environment", "CRAFTER_CRAFT", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), event.getResult() == null ? 0 : event.getResult().getAmount(),
                AuditUtil.item(event.getResult()), null, AuditUtil.item(event.getResult()),
                AuditUtil.details("recipe", event.getRecipe() == null ? null : event.getRecipe().getKey())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!recorder.option("tracking.options.log-environment", true)) {
            return;
        }
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
