package io.github.sunglogbag81.itemrestrict;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ItemRestrictPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final Set<Material> restrictedMaterials = new HashSet<>();
    private boolean opBypass;
    private boolean removeRestrictedItems;
    private boolean scanOnJoin;
    private boolean scanOnInventoryActions;
    private boolean scanContainerContents;
    private boolean notifyRemoval;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPlugin();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("itemrestrict") != null) {
            getCommand("itemrestrict").setExecutor(this);
            getCommand("itemrestrict").setTabCompleter(this);
        }
    }

    private void reloadPlugin() {
        reloadConfig();
        restrictedMaterials.clear();
        opBypass = getConfig().getBoolean("settings.op-bypass", true);
        removeRestrictedItems = getConfig().getBoolean("settings.remove-restricted-items", true);
        scanOnJoin = getConfig().getBoolean("settings.scan-on-join", true);
        scanOnInventoryActions = getConfig().getBoolean("settings.scan-on-inventory-actions", true);
        scanContainerContents = getConfig().getBoolean("settings.scan-container-contents", true);
        notifyRemoval = getConfig().getBoolean("settings.notify-removal", true);
        prefix = color(getConfig().getString("messages.prefix", "&c[아이템제한] &f"));

        for (String raw : getConfig().getStringList("restricted-materials")) {
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                getLogger().warning("알 수 없는 Material 이름입니다: " + raw);
                continue;
            }
            restrictedMaterials.add(material);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            send(sender, "usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("itemrestrict.admin")) {
                    send(sender, "no-permission");
                    return true;
                }
                reloadPlugin();
                send(sender, "config-reloaded");
                return true;
            }
            case "list" -> {
                if (!sender.hasPermission("itemrestrict.admin")) {
                    send(sender, "no-permission");
                    return true;
                }
                List<String> names = restrictedMaterials.stream().map(Enum::name).sorted().toList();
                send(sender, "list-header", Map.of("%items%", String.join(", ", names)));
                return true;
            }
            case "scan" -> {
                if (!sender.hasPermission("itemrestrict.admin")) {
                    send(sender, "no-permission");
                    return true;
                }
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        send(sender, "player-not-found", Map.of("%player%", args[1]));
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    send(sender, "usage");
                    return true;
                }
                int removed = scanAndRemove(target, false);
                send(sender, "scanned-player", Map.of("%player%", target.getName(), "%amount%", String.valueOf(removed)));
                return true;
            }
            default -> {
                send(sender, "usage");
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "list", "scan"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList(), args[1]);
        }
        return List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!scanOnJoin || isBypassed(event.getPlayer())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> scanAndRemove(event.getPlayer(), true), 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || isBypassed(player)) {
            return;
        }
        if (isRestricted(event.getItem().getItemStack())) {
            event.setCancelled(true);
            send(player, "blocked-pickup");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player)) {
            return;
        }
        if (isRestricted(event.getItem())) {
            event.setCancelled(true);
            send(player, "blocked-use");
            scanLater(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isBypassed(event.getPlayer())) {
            return;
        }
        if (isRestricted(event.getItem())) {
            event.setCancelled(true);
            send(event.getPlayer(), "blocked-use");
            scanLater(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isBypassed(event.getPlayer())) {
            return;
        }
        if (isRestricted(event.getItemInHand())) {
            event.setCancelled(true);
            send(event.getPlayer(), "blocked-use");
            scanLater(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player) || isBypassed(player)) {
            return;
        }
        if (isRestricted(event.getCurrentItem()) || isRestricted(event.getCursor())) {
            event.setCancelled(true);
            send(player, "blocked-inventory");
            scanLater(player);
            return;
        }
        if (scanOnInventoryActions) {
            scanLater(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isBypassed(player)) {
            return;
        }
        if (isRestricted(event.getCursor()) || isRestricted(event.getCurrentItem())) {
            event.setCancelled(true);
            send(player, "blocked-inventory");
            scanLater(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isBypassed(player)) {
            return;
        }
        if (event.getNewItems().values().stream().anyMatch(this::isRestricted)) {
            event.setCancelled(true);
            send(player, "blocked-inventory");
            scanLater(player);
            return;
        }
        if (scanOnInventoryActions) {
            scanLater(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!scanOnInventoryActions || !(event.getPlayer() instanceof Player player) || isBypassed(player)) {
            return;
        }
        scanLater(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isBypassed(player)) {
            return;
        }
        if (isRestricted(event.getRecipe().getResult()) || inventoryContainsRestricted(event.getInventory())) {
            event.setCancelled(true);
            send(player, "blocked-craft");
            scanLater(player);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        if (isRestricted(inventory.getResult()) || inventoryContainsRestricted(inventory)) {
            inventory.setResult(null);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (isRestricted(event.getResult()) || inventoryContainsRestricted(event.getInventory())) {
            event.setResult(null);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (isRestricted(event.getResult()) || inventoryContainsRestricted(event.getInventory())) {
            event.setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (isRestricted(event.getSource()) || isRestricted(event.getResult())) {
            event.setCancelled(true);
        }
    }

    private void scanLater(Player player) {
        if (!removeRestrictedItems || isBypassed(player)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> scanAndRemove(player, true), 1L);
    }

    private int scanAndRemove(Player player, boolean notifyPlayer) {
        if (!removeRestrictedItems || isBypassed(player)) {
            return 0;
        }
        int removed = removeFromInventory(player.getInventory());
        if (notifyPlayer && notifyRemoval && removed > 0) {
            send(player, "removed-items", Map.of("%amount%", String.valueOf(removed)));
        }
        return removed;
    }

    private int removeFromInventory(Inventory inventory) {
        int removed = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (!isRestricted(item)) {
                continue;
            }
            removed += item.getAmount();
            inventory.setItem(i, null);
        }
        return removed;
    }

    private boolean inventoryContainsRestricted(Inventory inventory) {
        for (ItemStack content : inventory.getContents()) {
            if (isRestricted(content)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRestricted(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        if (restrictedMaterials.contains(itemStack.getType())) {
            return true;
        }
        return scanContainerContents && containsRestrictedContainerContent(itemStack);
    }

    private boolean containsRestrictedContainerContent(ItemStack itemStack) {
        if (!(itemStack.getItemMeta() instanceof BlockStateMeta blockStateMeta)) {
            return false;
        }
        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return false;
        }
        for (ItemStack content : shulkerBox.getInventory().getContents()) {
            if (isRestricted(content)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBypassed(Player player) {
        return (opBypass && player.isOp()) || player.hasPermission("itemrestrict.bypass");
    }

    private void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    private void send(CommandSender sender, String key, Map<String, String> replacements) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        String message = prefix + raw;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        sender.sendMessage(color(message));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private List<String> filter(List<String> values, String input) {
        String lowered = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .sorted(Comparator.naturalOrder())
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }
}
