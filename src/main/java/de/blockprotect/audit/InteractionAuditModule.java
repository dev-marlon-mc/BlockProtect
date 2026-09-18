package de.blockprotect.audit;

import io.papermc.paper.event.player.AsyncChatEvent;
import de.blockprotect.audit.InspectionState;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

public final class InteractionAuditModule implements AuditModule {
    private final AuditRecorder recorder;
    private final InspectionState inspectionState;

    public InteractionAuditModule(AuditRecorder recorder, InspectionState inspectionState) {
        this.recorder = recorder;
        this.inspectionState = inspectionState;
    }

    @Override
    public String id() {
        return "interactions";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (inspectionState.isActive(event.getPlayer().getUniqueId())
                && (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR)) {
            return;
        }
        if (!recorder.option("tracking.options.log-interactions", true)) {
            return;
        }
        // Breaking and placing already have their own, much more useful audit
        // events. PlayerInteractEvent is also fired for those actions and would
        // otherwise create confusing duplicate "block used" entries.
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || isHandledByDedicatedEvent(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        ItemStack item = event.getItem();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "BLOCK_INTERACT",
                block == null ? event.getPlayer().getLocation() : block.getLocation(),
                block == null ? null : AuditUtil.blockType(block), item == null ? 0 : item.getAmount(),
                AuditUtil.item(item), null, null,
                AuditUtil.details("action", event.getAction(), "face", event.getBlockFace(), "hand", event.getHand())
        ));
    }

    private static boolean isHandledByDedicatedEvent(ItemStack item) {
        if (item == null) {
            return false;
        }
        Material material = item.getType();
        String materialName = material.name();
        return material.isBlock()
                || material.isEdible()
                || materialName.endsWith("_BOAT")
                || materialName.endsWith("_MINECART")
                || materialName.endsWith("_SPAWN_EGG")
                || switch (material) {
                    case BUCKET, WATER_BUCKET, LAVA_BUCKET, POWDER_SNOW_BUCKET,
                            FLINT_AND_STEEL, FIRE_CHARGE, BONE_MEAL,
                            ARMOR_STAND, PAINTING, ITEM_FRAME, GLOW_ITEM_FRAME,
                            END_CRYSTAL, LEAD, NAME_TAG -> true;
                    default -> false;
                };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!recorder.option("tracking.options.log-interactions", true)) {
            return;
        }
        Entity entity = event.getRightClicked();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "ENTITY_INTERACT",
                entity.getLocation(), AuditUtil.entityType(entity), 0, null, null, null,
                AuditUtil.details("hand", event.getHand())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityInteract(EntityInteractEvent event) {
        Entity entity = event.getEntity();
        recorder.record(id(), event, AuditRecorder.at(
                null, null, id(), "ENTITY_BLOCK_INTERACT", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), 0, null, null, null,
                AuditUtil.details("entity", AuditUtil.entityType(entity))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "BUCKET_EMPTY",
                target.getLocation(), AuditUtil.blockType(target), 1, event.getBucket().getKey().toString(),
                AuditUtil.blockState(target), null, AuditUtil.details("clicked", event.getBlockClicked().getLocation())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block source = event.getBlockClicked();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "BUCKET_FILL",
                source.getLocation(), AuditUtil.blockType(source), 1, event.getBucket().getKey().toString(),
                AuditUtil.blockState(source), null, AuditUtil.details("face", event.getBlockFace())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "ITEM_CONSUME",
                event.getPlayer().getLocation(), "minecraft:player", item.getAmount(), AuditUtil.item(item),
                null, null, null
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "SIGN_CHANGE",
                event.getBlock().getLocation(), AuditUtil.blockType(event.getBlock()), 0, null,
                null, AuditUtil.blockState(event.getBlock()),
                AuditUtil.details("lines", event.getLines())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!recorder.option("tracking.options.log-commands", true)) {
            return;
        }
        recorder.record("commands", event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), "commands", "COMMAND",
                event.getPlayer().getLocation(), "minecraft:command", 0, null, null, null,
                AuditUtil.details("command", event.getMessage())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        if (!recorder.option("tracking.options.log-chat", true)) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        recorder.record("chat", event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), "chat", "CHAT",
                null, "minecraft:chat", 0, null, null, null, AuditUtil.details("message", message)
        ));
    }
}
