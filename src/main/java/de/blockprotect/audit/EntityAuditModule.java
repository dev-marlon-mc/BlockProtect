package de.blockprotect.audit;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityEnterBlockEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntitySpellCastEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Records entity events with their complete causality chain.
 *
 * <p>In particular, a death is not attributed only through
 * {@link LivingEntity#getKiller()}. That Bukkit convenience method only
 * exposes a player killer. Paper's {@link DamageSource} also contains the
 * mob, projectile, TNT or other entity that actually caused the death.</p>
 */
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
        String reason = event instanceof CreatureSpawnEvent creature
                ? creature.getSpawnReason().name()
                : "ENTITY_EVENT";
        record(event, "ENTITY_SPAWN", entity, null, 0, AuditUtil.details(
                "reason", reason,
                "entity", describe(entity),
                "entity-uuid", uuid(entity)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        DamageSource damageSource = event.getDamageSource();
        Entity causing = damageSource == null ? null : damageSource.getCausingEntity();
        Entity direct = damageSource == null ? null : damageSource.getDirectEntity();
        Player playerKiller = firstPlayer(entity.getKiller(), causing, direct);
        String cause = damageSource == null || damageSource.getDamageType() == null
                ? "unknown"
                : damageSource.getDamageType().getKey().toString();

        record(event, "ENTITY_DEATH", entity, playerKiller, event.getDroppedExp(), AuditUtil.details(
                "cause", cause,
                "causing-entity", describe(causing),
                "causing-uuid", uuid(causing),
                "direct-entity", describe(direct),
                "direct-uuid", uuid(direct),
                "player-killer", playerKiller == null ? null : playerKiller.getName(),
                "drops", itemSummary(event.getDrops()),
                "drop-count", event.getDrops().size(),
                "entity-uuid", uuid(entity)
        ));
    }

    /** Records both entity-caused and environmental damage. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!recorder.option("tracking.options.log-damage", true)) {
            return;
        }
        Entity victim = event.getEntity();
        DamageSource source = event.getDamageSource();
        Entity direct = source == null ? null : source.getDirectEntity();
        Entity causing = source == null ? null : source.getCausingEntity();
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            direct = byEntity.getDamager();
            if (causing == null) {
                causing = causingEntity(direct);
            }
        }
        Player player = firstPlayer(causing, direct);
        String damageType = source == null || source.getDamageType() == null
                ? event.getCause().name().toLowerCase(Locale.ROOT)
                : source.getDamageType().getKey().toString();

        record(event, "ENTITY_DAMAGE", victim, player, 0, AuditUtil.details(
                "cause", event.getCause(),
                "damage-type", damageType,
                "damage", event.getFinalDamage(),
                "causing-entity", describe(causing),
                "causing-uuid", uuid(causing),
                "direct-entity", describe(direct),
                "direct-uuid", uuid(direct),
                "source-location", locationText(source == null ? null : source.getSourceLocation())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        record(event, "ENTITY_EXPLODE", entity, firstPlayer(entity), event.blockList().size(), AuditUtil.details(
                "entity", describe(entity),
                "entity-uuid", uuid(entity),
                "blocks", event.blockList().size(),
                "yield", event.getYield(),
                "origin", locationText(event.getLocation())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTransform(EntityTransformEvent event) {
        Entity original = event.getEntity();
        record(event, "ENTITY_TRANSFORM", original, firstPlayer(original), event.getTransformedEntities().size(), AuditUtil.details(
                "reason", event.getTransformReason(),
                "from", describe(original),
                "to", entitySummary(event.getTransformedEntities()),
                "entity-uuid", uuid(original)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onRemove(EntityRemoveEvent event) {
        // ENTITY_DEATH remains the detailed death record. This additional
        // event records despawns, unloads, pickups, merges and plugin removals.
        if (switch (event.getCause()) {
            case DEATH, DESPAWN, MERGE, HIT, PICKUP, TRANSFORMATION, ENTER_BLOCK -> true;
            default -> false;
        }) {
            return;
        }
        Entity entity = event.getEntity();
        record(event, "ENTITY_REMOVE", entity, null, 0, AuditUtil.details(
                "cause", event.getCause(),
                "entity", describe(entity),
                "entity-uuid", uuid(entity)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTarget(EntityTargetEvent event) {
        Entity target = event.getTarget();
        record(event, "ENTITY_TARGET", event.getEntity(), firstPlayer(event.getEntity()), 0, AuditUtil.details(
                "target", describe(target),
                "target-uuid", uuid(target),
                "reason", event.getReason()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBreed(EntityBreedEvent event) {
        record(event, "ENTITY_BREED", event.getEntity(), firstPlayer(event.getBreeder()), 1, AuditUtil.details(
                "mother", describe(event.getMother()),
                "father", describe(event.getFather()),
                "breeder", describe(event.getBreeder()),
                "item", AuditUtil.item(event.getBredWith()),
                "experience", event.getExperience()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEnterLoveMode(EntityEnterLoveModeEvent event) {
        HumanEntity human = event.getHumanEntity();
        record(event, "ENTITY_LOVE_MODE", event.getEntity(), human instanceof Player player ? player : null,
                event.getTicksInLove(), AuditUtil.details("player", human.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMount(EntityMountEvent event) {
        record(event, "ENTITY_MOUNT", event.getEntity(), firstPlayer(event.getEntity()), 0, AuditUtil.details(
                "mount", describe(event.getMount()), "mount-uuid", uuid(event.getMount())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDismount(EntityDismountEvent event) {
        record(event, "ENTITY_DISMOUNT", event.getEntity(), firstPlayer(event.getEntity()), 0, AuditUtil.details(
                "dismounted", describe(event.getDismounted()), "dismounted-uuid", uuid(event.getDismounted())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        record(event, "ENTITY_TELEPORT", entity, firstPlayer(entity), 0, AuditUtil.details(
                "from", locationText(event.getFrom()),
                "to", locationText(event.getTo()),
                "entity-uuid", uuid(entity)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEnterBlock(EntityEnterBlockEvent event) {
        record(event, "ENTITY_ENTER_BLOCK", event.getEntity(), firstPlayer(event.getEntity()), 0, AuditUtil.details(
                "block", AuditUtil.blockType(event.getBlock()),
                "block-location", locationText(event.getBlock().getLocation())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        record(event, "ENTITY_PLACE", event.getEntity(), player, 0, AuditUtil.details(
                "block", AuditUtil.blockType(event.getBlock()),
                "block-location", locationText(event.getBlock().getLocation()),
                "face", event.getBlockFace(),
                "hand", event.getHand()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrop(EntityDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Entity entity = event.getEntity();
        record(event, "ENTITY_DROP", entity, firstPlayer(entity), item.getAmount(), AuditUtil.details(
                "item", AuditUtil.item(item),
                "item-entity-uuid", uuid(event.getItemDrop())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCombust(EntityCombustEvent event) {
        Entity entity = event.getEntity();
        Entity source = event instanceof EntityCombustByEntityEvent byEntity ? byEntity.getCombuster() : null;
        Block block = event instanceof EntityCombustByBlockEvent byBlock ? byBlock.getCombuster() : null;
        record(event, "ENTITY_COMBUST", entity, firstPlayer(source), 0, AuditUtil.details(
                "duration-seconds", event.getDuration(),
                "entity-source", describe(source),
                "block-source", block == null ? null : AuditUtil.blockType(block),
                "block-location", block == null ? null : locationText(block.getLocation())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        record(event, "ENTITY_POTION_EFFECT", event.getEntity(), firstPlayer(event.getSource()), 0, AuditUtil.details(
                "effect", event.getModifiedType(),
                "action", event.getAction(),
                "cause", event.getCause(),
                "source", describe(event.getSource()),
                "old", event.getOldEffect(),
                "new", event.getNewEffect()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        record(event, "ENTITY_HEAL", event.getEntity(), null, 0, AuditUtil.details(
                "amount", event.getAmount(), "reason", event.getRegainReason()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onResurrect(EntityResurrectEvent event) {
        record(event, "ENTITY_RESURRECT", event.getEntity(), firstPlayer(event.getEntity()), 0, AuditUtil.details(
                "hand", event.getHand(), "entity-uuid", uuid(event.getEntity())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onShootBow(EntityShootBowEvent event) {
        Entity shooter = event.getEntity();
        record(event, "ENTITY_SHOOT", shooter, firstPlayer(shooter), 0, AuditUtil.details(
                "projectile", describe(event.getProjectile()),
                "bow", AuditUtil.item(event.getBow()),
                "consumable", AuditUtil.item(event.getConsumable()),
                "force", event.getForce(),
                "hand", event.getHand()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSpellCast(EntitySpellCastEvent event) {
        record(event, "ENTITY_SPELL", event.getEntity(), firstPlayer(event.getEntity()), 0,
                AuditUtil.details("spell", event.getSpell()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onAreaEffectCloud(AreaEffectCloudApplyEvent event) {
        Entity cloud = event.getEntity();
        record(event, "AREA_EFFECT_APPLY", cloud, firstPlayer(cloud), event.getAffectedEntities().size(), AuditUtil.details(
                "affected", entitySummary(event.getAffectedEntities()),
                "affected-count", event.getAffectedEntities().size()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onUnleash(EntityUnleashEvent event) {
        Entity entity = event.getEntity();
        Player player = event instanceof org.bukkit.event.player.PlayerUnleashEntityEvent playerEvent
                ? playerEvent.getPlayer() : firstPlayer(entity);
        record(event, "ENTITY_UNLEASH", entity, player, 0, AuditUtil.details(
                "reason", event.getReason(), "drop-leash", event.isDropLeash()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        ItemStack item = event.getItem().getItemStack();
        record(event, "ITEM_PICKUP", entity, firstPlayer(entity), item.getAmount(), AuditUtil.details(
                "item", AuditUtil.item(item), "item-entity-uuid", uuid(event.getItem())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        record(event, "ITEM_DROP", event.getPlayer(), event.getPlayer(), item.getAmount(), AuditUtil.details(
                "item", AuditUtil.item(item), "item-entity-uuid", uuid(event.getItemDrop())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        Entity caught = event.getCaught();
        record(event, "FISHING", event.getPlayer(), event.getPlayer(), 0, AuditUtil.details(
                "state", event.getState(), "caught", describe(caught), "caught-uuid", uuid(caught)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTame(EntityTameEvent event) {
        Player player = event.getOwner() instanceof Player possiblePlayer ? possiblePlayer : null;
        record(event, "ENTITY_TAME", event.getEntity(), player, 0, AuditUtil.details(
                "owner", ownerText(event.getOwner()), "entity-uuid", uuid(event.getEntity())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        record(event, "ENTITY_CHANGE_BLOCK", entity, firstPlayer(entity), 0, AuditUtil.details(
                "entity", describe(entity),
                "before", AuditUtil.blockState(event.getBlock()),
                "after", event.getTo().getKey(),
                "entity-uuid", uuid(entity)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        record(event, "HANGING_PLACE", event.getEntity(), player, 0, AuditUtil.details(
                "block-face", event.getBlockFace(), "entity-uuid", uuid(event.getEntity())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        record(event, "HANGING_BREAK", event.getEntity(), firstPlayer(remover), 0, AuditUtil.details(
                "remover", describe(remover), "cause", event.getCause(), "remover-uuid", uuid(remover)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLeash(PlayerLeashEntityEvent event) {
        record(event, "LEASH", event.getEntity(), event.getPlayer(), 0, AuditUtil.details(
                "leash-holder", describe(event.getLeashHolder()), "holder-uuid", uuid(event.getLeashHolder())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        record(event, "ARMOR_STAND_MANIPULATE", event.getRightClicked(), event.getPlayer(),
                event.getPlayerItem() == null ? 0 : event.getPlayerItem().getAmount(), AuditUtil.details(
                        "item", AuditUtil.item(event.getPlayerItem()),
                        "hand", event.getHand()
                ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        Entity shooter = projectile.getShooter() instanceof Entity entity ? entity : null;
        record(event, "PROJECTILE_LAUNCH", projectile, firstPlayer(shooter), 0, AuditUtil.details(
                "projectile", describe(projectile),
                "shooter", describe(shooter),
                "shooter-uuid", uuid(shooter)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Entity hitEntity = event.getHitEntity();
        Block hitBlock = event.getHitBlock();
        Entity shooter = projectile.getShooter() instanceof Entity entity ? entity : null;
        record(event, "PROJECTILE_HIT", projectile, firstPlayer(shooter), 0, AuditUtil.details(
                "projectile", describe(projectile),
                "hit-block", hitBlock == null ? null : AuditUtil.blockType(hitBlock),
                "hit-block-location", hitBlock == null ? null : locationText(hitBlock.getLocation()),
                "hit-entity", describe(hitEntity),
                "hit-entity-uuid", uuid(hitEntity)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemDespawn(ItemDespawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        record(event, "ITEM_DESPAWN", event.getEntity(), null, item.getAmount(), AuditUtil.details(
                "item", AuditUtil.item(item), "reason", "timeout"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemMerge(ItemMergeEvent event) {
        record(event, "ITEM_MERGE", event.getEntity(), null, event.getEntity().getItemStack().getAmount(), AuditUtil.details(
                "item", AuditUtil.item(event.getEntity().getItemStack()),
                "target", describe(event.getTarget()), "target-uuid", uuid(event.getTarget())
        ));
    }

    private void record(Event event, String action, Entity target, Player player, int amount, String details) {
        String storedDetails = recorder.option("tracking.options.entity-details", true) ? details : null;
        recorder.record(id(), event, AuditRecorder.at(
                player == null ? null : player.getUniqueId(),
                player == null ? null : player.getName(),
                id(), action, target == null ? null : target.getLocation(),
                AuditUtil.entityType(target), amount, null, null, null, storedDetails
        ));
    }

    private static Player firstPlayer(Entity... entities) {
        for (Entity entity : entities) {
            if (entity instanceof Player player) {
                return player;
            }
            Entity causing = causingEntity(entity);
            if (causing instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private static Entity causingEntity(Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return null;
    }

    private static String describe(Entity entity) {
        if (entity == null) {
            return null;
        }
        String name = entity instanceof Player player ? player.getName() : entity.getType().getKey().toString();
        return name + " [" + entity.getUniqueId() + "]";
    }

    private static String uuid(Entity entity) {
        return entity == null ? null : entity.getUniqueId().toString();
    }

    private static String ownerText(Object owner) {
        if (owner instanceof Entity entity) {
            return describe(entity);
        }
        return owner == null ? null : String.valueOf(owner);
    }

    private static String entitySummary(Collection<? extends Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return "-";
        }
        return entities.stream().map(EntityAuditModule::describe).toList().toString();
    }

    private static String itemSummary(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return "-";
        }
        return items.stream().map(AuditUtil::item).toList().toString();
    }

    private static String locationText(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getName() + " @ "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
