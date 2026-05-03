package com.imNikos.quickenchant;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class QuickEnchant extends JavaPlugin implements Listener, TabCompleter {

    private final Random random = new Random();

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("quickenchant").setExecutor(this);
        getCommand("quickenchant").setTabCompleter(this);
        getLogger().info(ChatColor.GREEN + "Quick Enchant enabled!");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendMessage(CommandSender sender, String path, String... placeholders) {
        if (!getConfig().getBoolean("messages.enabled", true)) return;
        String msg = getConfig().getString("messages." + path);
        if (msg == null || msg.isBlank()) return;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            msg = msg.replace("<" + placeholders[i] + ">", placeholders[i + 1]);
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    private String formatEnchantDisplay(String enchants, String levels) {
        String[] enchArr = enchants.split(",");
        String[] levArr  = levels.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < enchArr.length; i++) {
            if (i > 0) sb.append(", ");
            String ench = enchArr[i].trim().replace("_", " ");
            for (String word : ench.split(" ")) {
                if (!word.isEmpty()) {
                    sb.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase())
                      .append(" ");
                }
            }
            if (sb.charAt(sb.length() - 1) == ' ') sb.deleteCharAt(sb.length() - 1);
            String level = (i < levArr.length) ? levArr[i].trim() : "1";
            sb.append(" ").append(level);
        }
        return sb.toString();
    }

    private List<String> getProfiles() {
        List<String> profiles = new ArrayList<>();
        profiles.add("default");
        ConfigurationSection sec = getConfig().getConfigurationSection("table-profiles");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                if (!profiles.contains(k)) profiles.add(k);
            }
        }
        return profiles;
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("quickenchant")) return false;

        if (!sender.hasPermission("quickenchant.admin")) {
            sendMessage(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage:");
            sender.sendMessage(ChatColor.RED + "/qe link [profile] | /qe unlink");
            sender.sendMessage(ChatColor.RED + "/qe list profile [profile] | /qe list item [item]");
            sender.sendMessage(ChatColor.RED + "/qe [add|remove|exclude] [enchantment(s)] [level(s)] to [item] [profile]");
            sender.sendMessage(ChatColor.RED + "/qe reset [profile]");
            sender.sendMessage(ChatColor.RED + "/qe reload");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload"  -> handleReload(sender);
            case "link"    -> handleLink(sender, args);
            case "unlink"  -> handleUnlink(sender, args);
            case "list"    -> handleList(sender, args);
            case "reset"   -> handleReset(sender, args);
            case "add", "remove", "exclude" -> handleModifyPool(sender, args, args[0].toLowerCase());
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try /qe for help.");
                yield true;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Sub-command handlers
    // -------------------------------------------------------------------------

    private boolean handleReload(CommandSender sender) {
        reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Quick Enchant config reloaded!");
        return true;
    }

    private boolean handleLink(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /qe link [profile]");
            return true;
        }
        Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType() != Material.ENCHANTING_TABLE) {
            sender.sendMessage(ChatColor.RED + "You must be looking directly at an Enchanting Table.");
            return true;
        }
        String profileName = args[1].toLowerCase();
        String locKey = target.getWorld().getName() + ","
                + target.getX() + "," + target.getY() + "," + target.getZ();
        getConfig().set("table-locations." + locKey, profileName);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Table linked to profile: " + ChatColor.YELLOW + profileName);
        return true;
    }

    private boolean handleUnlink(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType() != Material.ENCHANTING_TABLE) {
            sender.sendMessage(ChatColor.RED + "You must be looking directly at an Enchanting Table.");
            return true;
        }
        String locKey = target.getWorld().getName() + ","
                + target.getX() + "," + target.getY() + "," + target.getZ();
        getConfig().set("table-locations." + locKey, null);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Table unlinked! It is now using the default profile.");
        return true;
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        String profileName = (args.length >= 2) ? args[1].toLowerCase() : "default";

        if (!getConfig().contains("table-profiles." + profileName)) {
            sender.sendMessage(ChatColor.RED + "The profile '" + profileName + "' is already empty or doesn't exist.");
            return true;
        }

        getConfig().set("table-profiles." + profileName, null);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Profile " + ChatColor.YELLOW + profileName + ChatColor.GREEN + " has been successfully reset and cleared.");
        return true;
    }

    /**
     * Handles /qe add, /qe remove, and /qe exclude
     * Syntax: /qe [option] [enchantment] [level] to [item] [profile]
     */
    private boolean handleModifyPool(CommandSender sender, String[] args, String action) {
        int toIndex = -1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("to")) {
                toIndex = i;
                break;
            }
        }

        if (toIndex == -1 || toIndex < 2 || toIndex > 3 || args.length <= toIndex + 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /qe " + action + " [enchantment(s)] [level(s)] to [item] [profile]");
            return true;
        }

        String enchantsStr = args[1];
        String levelsStr = (toIndex == 3) ? args[2] : "";
        String itemStr = args[toIndex + 1].toUpperCase();
        String profile = (args.length > toIndex + 2) ? args[toIndex + 2].toLowerCase() : "default";

        if (Material.getMaterial(itemStr) == null) {
            sender.sendMessage(ChatColor.RED + "Invalid material: " + itemStr);
            return true;
        }

        String basePath = "table-profiles." + profile + "." + itemStr;
        String excludePath = "table-profiles." + profile + ".excluded." + itemStr;

        boolean isGrouped = enchantsStr.startsWith("[") && enchantsStr.endsWith("]");
        boolean isLevelsGrouped = levelsStr.startsWith("[") && levelsStr.endsWith("]");

        if (isGrouped) {
            String cleanEnchants = enchantsStr.substring(1, enchantsStr.length() - 1);
            String cleanLevels = isLevelsGrouped ? levelsStr.substring(1, levelsStr.length() - 1) : levelsStr;

            if (cleanLevels.isEmpty()) {
                int count = cleanEnchants.split(",").length;
                cleanLevels = String.join(",", Collections.nCopies(count, "1"));
            }

            if (action.equals("add")) {
                getConfig().set(basePath + "." + cleanEnchants, cleanLevels);
            } else if (action.equals("remove")) {
                getConfig().set(basePath + "." + cleanEnchants, null);
            } else if (action.equals("exclude")) {
                for (String e : cleanEnchants.split(",")) getConfig().set(excludePath + "." + e, true);
            }

            sender.sendMessage(ChatColor.GREEN + "An enchantment containing both " 
                + cleanEnchants.replace(",", " and ") + " was " 
                + (action.equals("add") ? "added to" : action.equals("remove") ? "removed from" : "excluded from")
                + " the enchantment pool for " + itemStr.toLowerCase().replace("_", " ") 
                + " in " + profile + " profile.");

        } else {
            String[] enchArray = enchantsStr.split(",");
            String[] lvlArray = levelsStr.split(",");

            StringBuilder addedMsg = new StringBuilder();

            for (int i = 0; i < enchArray.length; i++) {
                String e = enchArray[i];
                String l = (i < lvlArray.length && !lvlArray[i].isEmpty()) ? lvlArray[i] : "1";

                if (action.equals("add")) {
                    getConfig().set(basePath + "." + e, l);
                } else if (action.equals("remove")) {
                    getConfig().set(basePath + "." + e, null);
                } else if (action.equals("exclude")) {
                    getConfig().set(excludePath + "." + e, true);
                }

                if (i > 0) addedMsg.append(" as well as the ");
                addedMsg.append(e).append(" ").append(l);
            }

            sender.sendMessage(ChatColor.GREEN + "The " + addedMsg.toString() + " enchantment" 
                + (enchArray.length > 1 ? "s were " : " was ") 
                + (action.equals("add") ? "added to" : action.equals("remove") ? "removed from" : "excluded from")
                + " the enchantment pool for " + itemStr.toLowerCase().replace("_", " ") 
                + " in " + profile + " profile.");
        }

        saveConfig();
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /qe list profile [profile] OR /qe list item [item]");
            return true;
        }

        String targetType = args[1].toLowerCase();
        String query = args[2];

        if (targetType.equals("item")) {
            Material mat = Material.getMaterial(query.toUpperCase());
            if (mat == null) {
                sender.sendMessage(ChatColor.RED + "Invalid item: " + query);
                return true;
            }

            sender.sendMessage(ChatColor.GOLD + "=== Enchantments for "
                    + ChatColor.YELLOW + mat.name() + ChatColor.GOLD + " ===");

            ConfigurationSection profilesSec = getConfig().getConfigurationSection("table-profiles");
            if (profilesSec == null) {
                sender.sendMessage(ChatColor.GRAY + "No profiles configured.");
                return true;
            }

            boolean found = false;
            for (String profileName : profilesSec.getKeys(false)) {
                ConfigurationSection matSec = getConfig().getConfigurationSection(
                        "table-profiles." + profileName + "." + mat.name());
                if (matSec == null || matSec.getKeys(false).isEmpty()) continue;

                found = true;
                for (String enchKey : matSec.getKeys(false)) {
                    if (enchKey.equals("excluded")) continue;
                    String levels = matSec.getString(enchKey, "1");
                    sender.sendMessage(ChatColor.YELLOW + profileName + ": "
                            + ChatColor.WHITE + formatEnchantDisplay(enchKey, levels));
                }
            }
            if (!found) {
                sender.sendMessage(ChatColor.GRAY + "No enchantments configured for " + mat.name() + ".");
            }

        } else if (targetType.equals("profile")) {
            String profileName = query.toLowerCase();
            ConfigurationSection profileSec = getConfig().getConfigurationSection("table-profiles." + profileName);

            if (profileSec == null) {
                sender.sendMessage(ChatColor.RED + "Profile '" + profileName + "' not found.");
                return true;
            }

            sender.sendMessage(ChatColor.GOLD + "=== Enchantments in profile '"
                    + ChatColor.YELLOW + profileName + ChatColor.GOLD + "' ===");

            if (profileSec.getKeys(false).isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "This profile has no entries.");
                return true;
            }

            for (String matName : profileSec.getKeys(false)) {
                if (matName.equals("excluded")) continue;
                ConfigurationSection matSec = getConfig().getConfigurationSection(
                        "table-profiles." + profileName + "." + matName);
                if (matSec == null) continue;

                StringBuilder sb = new StringBuilder();
                for (String enchKey : matSec.getKeys(false)) {
                    String levels = matSec.getString(enchKey, "1");
                    if (!sb.isEmpty()) sb.append(ChatColor.GRAY).append(" | ").append(ChatColor.WHITE);
                    sb.append(formatEnchantDisplay(enchKey, levels));
                }
                sender.sendMessage(ChatColor.YELLOW + matName + ": " + ChatColor.WHITE + sb);
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /qe list profile [profile] OR /qe list item [item]");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler
    public void onGrindstoneInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.GRINDSTONE) return;
        if (!getConfig().getBoolean("enable-quick-disenchant", true)) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (item.getEnchantments().isEmpty()) return;

        event.setCancelled(true);

        int totalExp = 0;
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            int level = entry.getValue();
            totalExp += (random.nextInt(10) + 10) * level;
            item.removeEnchantment(entry.getKey());
        }

        if (totalExp > 0) {
            ExperienceOrb orb = block.getWorld().spawn(
                    block.getLocation().add(0.5, 1.0, 0.5), ExperienceOrb.class);
            orb.setExperience(totalExp);
        }

        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1.0f, 1.0f);
        sendMessage(player, "disenchant-success");
    }

    @EventHandler
    public void onTableInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENCHANTING_TABLE) return;
        if (!getConfig().getBoolean("enable-quick-enchant", true)) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!item.getEnchantments().isEmpty()) return;

        String locKey = block.getWorld().getName() + ","
                + block.getX() + "," + block.getY() + "," + block.getZ();
        String activeProfile = getConfig().getString("table-locations." + locKey, "default");

        String materialName = item.getType().name();
        ConfigurationSection customSection = getConfig().getConfigurationSection(
                "table-profiles." + activeProfile + "." + materialName);

        boolean isCustom = (customSection != null && !customSection.getKeys(false).isEmpty());
        List<Enchantment> vanillaEnchants = new ArrayList<>();

        if (!isCustom) {
            List<String> excluded = getConfig().getStringList("exclude-vanilla-enchants");
            
            // Apply profile-specific item exclusions
            ConfigurationSection profileExclusions = getConfig().getConfigurationSection(
                    "table-profiles." + activeProfile + ".excluded." + materialName);
            if (profileExclusions != null) {
                excluded.addAll(profileExclusions.getKeys(false));
            }

            for (Enchantment ench : Registry.ENCHANTMENT) {
                if (ench.canEnchantItem(item) && !excluded.contains(ench.getKey().getKey())) {
                    vanillaEnchants.add(ench);
                }
            }
            if (vanillaEnchants.isEmpty()) return;
        }

        int cost = getConfig().getInt("exp-level-cost", 1);
        if (player.getLevel() < cost) {
            sendMessage(player, "not-enough-exp", "cost", String.valueOf(cost));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        event.setCancelled(true);
        player.setLevel(player.getLevel() - cost);

        if (isCustom) {
            List<String> options = new ArrayList<>(customSection.getKeys(false));
            options.remove("excluded"); // Prevent attempting to roll 'excluded'
            if (options.isEmpty()) return;

            String chosenOption = options.get(random.nextInt(options.size()));

            String levelsString = customSection.getString(chosenOption);
            if (levelsString == null)
                levelsString = String.valueOf(customSection.getInt(chosenOption, 1));

            String[] enchants = chosenOption.split(",");
            String[] levels   = levelsString.split(",");

            for (int i = 0; i < enchants.length; i++) {
                String enchantName = enchants[i].trim();
                int level = 1;
                if (i < levels.length) {
                    try { level = Integer.parseInt(levels[i].trim()); }
                    catch (NumberFormatException ignored) {}
                }
                Enchantment ench = Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft(enchantName.toLowerCase()));
                if (ench != null) {
                    item.addUnsafeEnchantment(ench, level);
                } else {
                    sendMessage(player, "config-error", "enchant", enchantName);
                }
            }
        } else {
            Enchantment chosen = vanillaEnchants.get(random.nextInt(vanillaEnchants.size()));
            item.addUnsafeEnchantment(chosen, chosen.getStartLevel());
        }

        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
        sendMessage(player, "success");
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("quickenchant")) return completions;
        if (!sender.hasPermission("quickenchant.admin")) return completions;

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    Arrays.asList("add", "remove", "exclude", "link", "unlink", "list", "reset", "reload"),
                    completions);

        } else if (args[0].equalsIgnoreCase("list")) {
            if (args.length == 2) {
                StringUtil.copyPartialMatches(args[1].toLowerCase(), Arrays.asList("profile", "item"), completions);
            } else if (args.length == 3) {
                if (args[1].equalsIgnoreCase("profile")) {
                    StringUtil.copyPartialMatches(args[2].toLowerCase(), getProfiles(), completions);
                } else if (args[1].equalsIgnoreCase("item")) {
                    List<String> materials = new ArrayList<>();
                    for (Material mat : Material.values()) if (mat.isItem()) materials.add(mat.name());
                    StringUtil.copyPartialMatches(args[2].toUpperCase(), materials, completions);
                }
            }

        } else if (args[0].matches("(?i)add|remove|exclude")) {
            if (args.length == 2) {
                List<String> enchants = new ArrayList<>();
                for (Enchantment e : Registry.ENCHANTMENT) enchants.add(e.getKey().getKey());
                StringUtil.copyPartialMatches(args[1].toLowerCase(), enchants, completions);
            } else if (args.length == 3) {
                StringUtil.copyPartialMatches(args[2].toLowerCase(), Arrays.asList("to", "1", "1,1", "[1,1]"), completions);
            } else if (args.length == 4) {
                if (args[2].equalsIgnoreCase("to")) {
                    List<String> materials = new ArrayList<>();
                    for (Material mat : Material.values()) if (mat.isItem()) materials.add(mat.name());
                    StringUtil.copyPartialMatches(args[3].toUpperCase(), materials, completions);
                } else {
                    StringUtil.copyPartialMatches(args[3].toLowerCase(), Collections.singletonList("to"), completions);
                }
            } else if (args.length == 5) {
                if (args[3].equalsIgnoreCase("to")) {
                    List<String> materials = new ArrayList<>();
                    for (Material mat : Material.values()) if (mat.isItem()) materials.add(mat.name());
                    StringUtil.copyPartialMatches(args[4].toUpperCase(), materials, completions);
                } else if (args[2].equalsIgnoreCase("to")) {
                    StringUtil.copyPartialMatches(args[4].toLowerCase(), getProfiles(), completions);
                }
            } else if (args.length == 6) {
                if (args[3].equalsIgnoreCase("to")) {
                    StringUtil.copyPartialMatches(args[5].toLowerCase(), getProfiles(), completions);
                }
            }

        } else if (args[0].equalsIgnoreCase("link") || args[0].equalsIgnoreCase("reset")) {
            if (args.length == 2) {
                StringUtil.copyPartialMatches(args[1].toLowerCase(), getProfiles(), completions);
            }
        }

        Collections.sort(completions);
        return completions;
    }
}