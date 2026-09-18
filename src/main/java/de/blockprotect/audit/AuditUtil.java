package de.blockprotect.audit;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

public final class AuditUtil {
    private AuditUtil() {
    }

    public static String blockType(Block block) {
        return block == null ? null : block.getType().getKey().toString();
    }

    public static String blockState(Block block) {
        if (block == null) {
            return null;
        }
        return block.getType().getKey() + " " + block.getBlockData().getAsString();
    }

    public static String blockState(BlockState state) {
        if (state == null) {
            return null;
        }
        return state.getType().getKey() + " " + state.getBlockData().getAsString();
    }

    public static String item(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String result = item.getType().getKey() + "x" + item.getAmount();
        Map<String, Integer> enchantments = enchantments(item);
        if (!enchantments.isEmpty()) {
            StringBuilder suffix = new StringBuilder(" [");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                if (!first) {
                    suffix.append(", ");
                }
                suffix.append(entry.getKey()).append(' ').append(entry.getValue());
                first = false;
            }
            result += suffix.append(']').toString();
        }
        return result;
    }

    private static Map<String, Integer> enchantments(ItemStack item) {
        Map<String, Integer> result = new TreeMap<>();
        item.getEnchantments().forEach((enchantment, level) ->
                result.merge(enchantment.getKey().toString(), level, Math::max));

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta stored) {
            stored.getStoredEnchants().forEach((enchantment, level) ->
                    result.merge(enchantment.getKey().toString(), level, Math::max));
        }
        return result;
    }

    public static String itemData(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static String entityType(Entity entity) {
        return entity == null ? null : entity.getType().getKey().toString();
    }

    public static Location location(Entity entity) {
        return entity == null ? null : entity.getLocation();
    }

    public static Location location(Inventory inventory) {
        return inventory == null ? null : inventory.getLocation();
    }

    public static String inventoryType(Inventory inventory) {
        return inventory == null ? null : inventory.getType().name();
    }

    public static String details(Object... pairs) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (!result.isEmpty()) {
                result.append(';');
            }
            result.append(String.valueOf(pairs[index])).append('=').append(String.valueOf(pairs[index + 1]));
        }
        return result.toString();
    }
}
