package de.blockprotect.audit;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class EntityAuditModule implements AuditModule {
    private final AuditRecorder recorder;

    public EntityAuditModule(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String id() {
        return "entities";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        recorder.record(id(), event, AuditRecorder.at(
                null, null, id(), "ENTITY_SPAWN", entity.getLocation(), AuditUtil.entityType(entity), 0, null,
                null, null, AuditUtil.details("reason", "entity-spawn")
        ), recorder.option("tracking.options.entity-details", true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        recorder.record(id(), event, AuditRecorder.at(
                killer == null ? null : killer.getUniqueId(), killer == null ? null : killer.getName(),
                id(), "ENTITY_DEATH", entity.getLocation(), AuditUtil.entityType(entity), 0, null,
                null, null, AuditUtil.details("drops", event.getDrops(), "xp", event.getDroppedExp())
        ), recorder.option("tracking.options.entity-details", true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!recorder.option("tracking.options.log-damage", true)) {
            return;
        }
        Entity damager = event.getDamager();
        Player player = damager instanceof Player possiblePlayer ? possiblePlayer : null;
        recorder.record(id(), event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(), id(),
                "ENTITY_DAMAGE", event.getEntity().getLocation(), AuditUtil.entityType(event.getEntity()),
                0, null, null, null,
                AuditUtil.details("damager", AuditUtil.entityType(damager), "damage", event.getFinalDamage(),
                        "cause", event.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        ItemStack item = event.getItem().getItemStack();
        recorder.record(id(), event, AuditRecorder.at(
                entity instanceof Player player ? player.getUniqueId() : null,
                entity instanceof Player player ? player.getName() : null,
                id(), "ITEM_PICKUP", entity.getLocation(), AuditUtil.entityType(entity), item.getAmount(),
                AuditUtil.item(item), null, null,
                AuditUtil.details("item-entity", event.getItem().getUniqueId())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "ITEM_DROP",
                event.getPlayer().getLocation(), "minecraft:item", item.getAmount(), AuditUtil.item(item),
                null, null, AuditUtil.details("item-entity", event.getItemDrop().getUniqueId())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        Entity caught = event.getCaught();
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "FISHING",
                event.getPlayer().getLocation(), caught == null ? null : AuditUtil.entityType(caught), 0, null,
                null, null, AuditUtil.details("state", event.getState())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTame(EntityTameEvent event) {
        Player player = event.getOwner() instanceof Player possiblePlayer ? possiblePlayer : null;
        recorder.record(id(), event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(), id(),
                "ENTITY_TAME", event.getEntity().getLocation(), AuditUtil.entityType(event.getEntity()), 0, null,
                null, null, AuditUtil.details("owner", event.getOwner())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                null, null, id(), "ENTITY_CHANGE_BLOCK", event.getBlock().getLocation(),
                AuditUtil.blockType(event.getBlock()), 0, null, AuditUtil.blockState(event.getBlock()),
                event.getTo().getKey().toString(), AuditUtil.details("entity", AuditUtil.entityType(event.getEntity()))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        recorder.record(id(), event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(), id(),
                "HANGING_PLACE", event.getEntity().getLocation(), AuditUtil.entityType(event.getEntity()), 0, null,
                null, null, AuditUtil.details("block-face", event.getBlockFace())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        Player player = remover instanceof Player possiblePlayer ? possiblePlayer : null;
        recorder.record(id(), event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(), player == null ? null : player.getName(), id(),
                "HANGING_BREAK", event.getEntity().getLocation(), AuditUtil.entityType(event.getEntity()), 0, null,
                null, null, AuditUtil.details("remover", AuditUtil.entityType(remover), "cause", event.getCause())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLeash(PlayerLeashEntityEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "LEASH",
                event.getEntity().getLocation(), AuditUtil.entityType(event.getEntity()), 0, null,
                null, null, AuditUtil.details("leash-holder", AuditUtil.entityType(event.getLeashHolder()))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "UNLEASH",
                event.getEntity().getLocation(), AuditUtil.entityType(event.getEntity()), 0, null,
                null, null, null
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(), id(), "ARMOR_STAND_MANIPULATE",
                event.getRightClicked().getLocation(), AuditUtil.entityType(event.getRightClicked()),
                event.getPlayerItem() == null ? 0 : event.getPlayerItem().getAmount(),
                AuditUtil.item(event.getPlayerItem()), null, null,
                AuditUtil.details("item", AuditUtil.item(event.getPlayerItem()))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                null, null, id(), "PROJECTILE_LAUNCH", event.getEntity().getLocation(),
                AuditUtil.entityType(event.getEntity()), 0, null, null, null,
                AuditUtil.details("projectile", AuditUtil.entityType(event.getEntity()))
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        recorder.record(id(), event, AuditRecorder.at(
                null, null, id(), "PROJECTILE_HIT", event.getEntity().getLocation(),
                event.getHitEntity() == null ? null : AuditUtil.entityType(event.getHitEntity()), 0, null,
                null, null, AuditUtil.details("hit-block", event.getHitBlock(), "hit-entity", event.getHitEntity())
        ));
    }
}
