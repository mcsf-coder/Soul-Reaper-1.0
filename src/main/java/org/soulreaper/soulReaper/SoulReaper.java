package org.soulreaper.soulReaper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class SoulReaper extends JavaPlugin implements Listener {

    private NamespacedKey swordKey;
    private NamespacedKey soulsDataKey;

    private final Map activeSummons = new HashMap();
    private final Map summonToOwner = new HashMap();
    private final Map cooldownEnd = new HashMap();
    private final Map fearedMobsUntil = new HashMap();

    private static final long SUMMON_DURATION_MS = 30000L; // 30 seconds fighting
    private static final long COOLDOWN_AFTER_MS = 30000L;  // 30 seconds cooldown after summon ends
    private static final String RECIPE_GUI_TITLE = ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Soul Reaper Recipe";

    private static class ActiveSummon {
        final UUID ownerId;
        final UUID mobId;
        final long endTime;

        ActiveSummon(UUID ownerId, UUID mobId, long endTime) {
            this.ownerId = ownerId;
            this.mobId = mobId;
            this.endTime = endTime;
        }
    }

    @Override
    public void onEnable() {
        swordKey = new NamespacedKey(this, "soul_reaper_blade");
        soulsDataKey = new NamespacedKey(this, "stored_souls");
        getServer().getPluginManager().registerEvents(this, this);
        registerSecretRecipe();

        // 4-tick (0.2s) loop for black smoke around held Soul Reaper, active summoned soul, & feared mobs
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                // 1. Render black smoke orbiting player's Soul Reaper when holding at least 1 soul
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (isSoulReaper(hand)) {
                        List souls = getStoredSouls(hand);
                        if (!souls.isEmpty()) {
                            Location handLoc = player.getLocation().add(0, 1.1, 0).add(player.getLocation().getDirection().setY(0).normalize().multiply(0.55));
                            handLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, handLoc, 2, 0.22, 0.25, 0.22, 0.01);
                            handLoc.getWorld().spawnParticle(Particle.SQUID_INK, handLoc, 1, 0.18, 0.2, 0.18, 0.01);
                        }
                    }
                }

                // 2. Keep feared mobs blinded/panicking without targeting anyone
                Iterator fearIt = fearedMobsUntil.entrySet().iterator();
                while (fearIt.hasNext()) {
                    Map.Entry entry = (Map.Entry) fearIt.next();
                    UUID mobId = (UUID) entry.getKey();
                    long end = (Long) entry.getValue();
                    Entity ent = Bukkit.getEntity(mobId);

                    if (ent == null || !ent.isValid() || ent.isDead() || now == Math.max(now, end)) {
                        fearIt.remove();
                        continue;
                    }
                    if (ent instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                    ent.getWorld().spawnParticle(Particle.LARGE_SMOKE, ent.getLocation().add(0, 1.2, 0), 2, 0.25, 0.3, 0.25, 0.01);
                }

                // 3. Update active summoned soul mobs (black smoke body, target enemies, 30s timer)
                Iterator it = activeSummons.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry entry = (Map.Entry) it.next();
                    UUID ownerId = (UUID) entry.getKey();
                    ActiveSummon summon = (ActiveSummon) entry.getValue();
                    Player owner = Bukkit.getPlayer(ownerId);
                    Entity ent = Bukkit.getEntity(summon.mobId);

                    if (owner == null || !owner.isOnline() || ent == null || !ent.isValid() || ent.isDead() || now == Math.max(now, summon.endTime)) {
                        if (ent != null && ent.isValid()) {
                            Location endLoc = ent.getLocation().add(0, 1.0, 0);
                            endLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, endLoc, 30, 0.5, 0.7, 0.5, 0.05);
                            endLoc.getWorld().playSound(endLoc, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.6f);
                            ent.remove();
                        }
                        summonToOwner.remove(summon.mobId);
                        it.remove();
                        cooldownEnd.put(ownerId, now + COOLDOWN_AFTER_MS);
                        if (owner != null && owner.isOnline()) {
                            owner.sendActionBar(ChatColor.GRAY + "Soul returned to the void! " + ChatColor.RED + "Cooldown: 30s");
                        }
                        continue;
                    }

                    // Surround summoned mob body with black smoke
                    Location mobLoc = ent.getLocation().add(0, 0.9, 0);
                    mobLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, mobLoc, 6, 0.35, 0.55, 0.35, 0.01);
                    mobLoc.getWorld().spawnParticle(Particle.SQUID_INK, mobLoc, 3, 0.28, 0.45, 0.28, 0.01);

                    // Direct summoned mob to attack nearest monster/hostile target near owner
                    if (ent instanceof Mob mob) {
                        LivingEntity target = findNearestEnemy(owner, mob, 18.0);
                        if (target != null) {
                            mob.setTarget(target);
                        }
                    }
                }
            }
        }, 4L, 4L);
    }

    private void registerSecretRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "soul_reaper_secret_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createSoulReaper());
        // Top row: Creeper (Gunpowder), Zombie (Rotten Flesh), Skeleton (Bone)
        // Middle row: Enderman (Ender Pearl), Spider (Spider Eye), Magma Cube (Magma Cream)
        // Bottom row: 3 Blaze Rods
        recipe.shape("GFB", "PEM", "RRR");
        recipe.setIngredient('G', Material.GUNPOWDER);
        recipe.setIngredient('F', Material.ROTTEN_FLESH);
        recipe.setIngredient('B', Material.BONE);
        recipe.setIngredient('P', Material.ENDER_PEARL);
        recipe.setIngredient('E', Material.SPIDER_EYE);
        recipe.setIngredient('M', Material.MAGMA_CREAM);
        recipe.setIngredient('R', Material.BLAZE_ROD);
        Bukkit.addRecipe(recipe);
    }

    private void openRecipeGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, RECIPE_GUI_TITLE);

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        if (fMeta != null) {
            fMeta.setDisplayName(" ");
            filler.setItemMeta(fMeta);
        }
        for (int i = 0; i != 27; i++) {
            gui.setItem(i, filler);
        }

        // 3x3 Crafting Grid on the left (slots 1,2,3 | 10,11,12 | 19,20,21)
        gui.setItem(1, new ItemStack(Material.GUNPOWDER));
        gui.setItem(2, new ItemStack(Material.ROTTEN_FLESH));
        gui.setItem(3, new ItemStack(Material.BONE));

        gui.setItem(10, new ItemStack(Material.ENDER_PEARL));
        gui.setItem(11, new ItemStack(Material.SPIDER_EYE));
        gui.setItem(12, new ItemStack(Material.MAGMA_CREAM));

        gui.setItem(19, new ItemStack(Material.BLAZE_ROD));
        gui.setItem(20, new ItemStack(Material.BLAZE_ROD));
        gui.setItem(21, new ItemStack(Material.BLAZE_ROD));

        // Crafting Table indicator in slot 14 and Soul Reaper result in slot 16
        ItemStack craftIcon = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta cMeta = craftIcon.getItemMeta();
        if (cMeta != null) {
            cMeta.setDisplayName(ChatColor.YELLOW + "Craft in any Crafting Table ->");
            craftIcon.setItemMeta(cMeta);
        }
        gui.setItem(14, craftIcon);
        gui.setItem(16, createSoulReaper());

        player.openInventory(gui);
    }

    @EventHandler
    public void onRecipeGuiClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(RECIPE_GUI_TITLE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRecipeGuiDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().equals(RECIPE_GUI_TITLE)) {
            event.setCancelled(true);
        }
    }

    public ItemStack createSoulReaper() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Soul Reaper");
            meta.addEnchant(Enchantment.SHARPNESS, 3, true);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(soulsDataKey, PersistentDataType.STRING, "");
            sword.setItemMeta(meta);
        }
        updateSwordLore(sword, new ArrayList());
        return sword;
    }


    private boolean isSoulReaper(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    private List getStoredSouls(ItemStack sword) {
        List list = new ArrayList();
        if (!isSoulReaper(sword)) return list;
        ItemMeta meta = sword.getItemMeta();
        if (meta == null) return list;
        String raw = (String) meta.getPersistentDataContainer().getOrDefault(soulsDataKey, PersistentDataType.STRING, "");
        if (raw == null || raw.isEmpty()) return list;
        String[] parts = raw.split(",");
        for (int i = 0; i != parts.length; i++) {
            if (!parts[i].isEmpty()) {
                list.add(parts[i]);
            }
        }
        return list;
    }

    private void saveStoredSouls(ItemStack sword, List souls) {
        if (!isSoulReaper(sword)) return;
        ItemMeta meta = sword.getItemMeta();
        if (meta == null) return;
        String joined = String.join(",", souls);
        meta.getPersistentDataContainer().set(soulsDataKey, PersistentDataType.STRING, joined);
        sword.setItemMeta(meta);
        updateSwordLore(sword, souls);
    }

    private void updateSwordLore(ItemStack sword, List souls) {
        ItemMeta meta = sword.getItemMeta();
        if (meta == null) return;
        String nextSoul = souls.isEmpty() ? "None" : formatMobName((String) souls.get(0));
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Slay monsters directly with this blade to",
                ChatColor.GRAY + "absorb their souls into dark smoke.",
                ChatColor.YELLOW + "Press [F] (Swap Hand)" + ChatColor.GRAY + " to choose next soul!",
                ChatColor.YELLOW + "Sprint + Jump + Left-Click (Mid-Air)" + ChatColor.GRAY + " to summon!",
                "",
                ChatColor.DARK_PURPLE + "Stored Souls: " + ChatColor.WHITE + souls.size() + ChatColor.GRAY + " (Unlimited)",
                ChatColor.DARK_PURPLE + "Next Soul: " + ChatColor.AQUA + nextSoul
        ));
        sword.setItemMeta(meta);
    }

    private String formatMobName(String enumName) {
        String lower = enumName.toLowerCase().replace("_", " ");
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // Press F (Swap Hand) while holding Soul Reaper to cycle and choose which soul to summon next
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandCycleSoul(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack mainHand = event.getOffHandItem();
        if (!isSoulReaper(mainHand)) return;
        event.setCancelled(true);
        List souls = getStoredSouls(mainHand);
        if (souls.isEmpty()) {
            player.sendActionBar(ChatColor.RED + "No souls stored in your Soul Reaper!");
            return;
        }
        if (souls.size() == 1) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Next Soul: " + ChatColor.AQUA + formatMobName((String) souls.get(0)) + ChatColor.GRAY + " (Only 1 soul stored)");
            return;
        }
        Object first = souls.remove(0);
        souls.add(first);
        saveStoredSouls(mainHand, souls);
        player.getInventory().setItemInMainHand(mainHand);
        String selected = formatMobName((String) souls.get(0));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
        player.sendActionBar(ChatColor.DARK_PURPLE + "Selected Next Soul: " + ChatColor.AQUA + "" + ChatColor.BOLD + selected + ChatColor.GRAY + " (Total: " + souls.size() + ")");
    }

    // Collect soul ONLY when player kills a monster directly with Soul Reaper
    @EventHandler(priority = EventPriority.HIGH)
    public void onMonsterDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead instanceof Monster)) return;
        if (summonToOwner.containsKey(dead.getUniqueId())) return;
        Player killer = dead.getKiller();
        if (killer == null) return;
        if (dead.getLastDamageCause() instanceof EntityDamageByEntityEvent byEnt) {
            if (summonToOwner.containsKey(byEnt.getDamager().getUniqueId())) {
                return;
            }
            if (byEnt.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Entity shooterEnt) {
                if (summonToOwner.containsKey(shooterEnt.getUniqueId())) {
                    return;
                }
            }
        }
        ItemStack hand = killer.getInventory().getItemInMainHand();
        if (!isSoulReaper(hand)) return;
        List souls = getStoredSouls(hand);
        souls.add(dead.getType().name());
        saveStoredSouls(hand, souls);
        Location loc = dead.getLocation().add(0, 1.0, 0);
        loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 25, 0.4, 0.5, 0.4, 0.04);
        loc.getWorld().spawnParticle(Particle.SOUL, loc, 12, 0.3, 0.4, 0.3, 0.04);
        killer.playSound(killer.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 1.0f);
        killer.sendActionBar(ChatColor.DARK_PURPLE + "+1 " + ChatColor.AQUA + formatMobName(dead.getType().name()) + " Soul" + ChatColor.GRAY + " (Total: " + souls.size() + ")");
    }

    // Trigger summon on Sprint + Jump (mid-air) + Left-Click Air holding Soul Reaper
    @EventHandler
    public void onPlayerSwingMidAir(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR) return;
        Player player = event.getPlayer();
        if (!player.isSprinting()) return;
        if (player.isOnGround()) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isSoulReaper(hand)) return;
        trySummonSoul(player, hand);
    }

    private void trySummonSoul(Player player, ItemStack sword) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (activeSummons.containsKey(uuid)) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "A summoned soul is already fighting for you!");
            return;
        }
        long cdEnd = (Long) cooldownEnd.getOrDefault(uuid, 0L);
        if (now != Math.max(now, cdEnd)) {
            long secLeft = (long) Math.ceil((cdEnd - now) / 1000.0);
            player.sendActionBar(ChatColor.RED + "Soul Reaper recharging: " + ChatColor.YELLOW + secLeft + "s left");
            return;
        }
        List souls = getStoredSouls(sword);
        if (souls.isEmpty()) {
            player.sendActionBar(ChatColor.RED + "No souls stored in your Soul Reaper!");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            return;
        }
        String mobTypeName = (String) souls.remove(0);
        saveStoredSouls(sword, souls);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(mobTypeName);
        } catch (IllegalArgumentException e) {
            entityType = EntityType.ZOMBIE;
        }
        Location startLoc = player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.8));
        Vector velocity = player.getLocation().getDirection().normalize().multiply(0.95).add(new Vector(0, -0.25, 0));
        player.getWorld().playSound(startLoc, Sound.ENTITY_WITHER_SHOOT, 0.9f, 0.8f);
        final EntityType finalType = entityType;
        final Location projLoc = startLoc.clone();
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                projLoc.add(velocity);
                velocity.setY(velocity.getY() - 0.06);
                projLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, projLoc, 10, 0.25, 0.25, 0.25, 0.02);
                projLoc.getWorld().spawnParticle(Particle.SQUID_INK, projLoc, 5, 0.2, 0.2, 0.2, 0.02);
                Block blockAt = projLoc.getBlock();
                Block blockBelow = projLoc.clone().add(0, -0.4, 0).getBlock();
                if (blockAt.getType().isSolid() || blockBelow.getType().isSolid() || ticks == 25) {
                    Location spawnLoc = projLoc.clone();
                    while (spawnLoc.getBlock().getType().isSolid() && spawnLoc.getY() != spawnLoc.getWorld().getMaxHeight()) {
                        spawnLoc.add(0, 1.0, 0);
                    }
                    materializeSoulMob(player, spawnLoc, finalType);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void materializeSoulMob(Player owner, Location spawnLoc, EntityType type) {
        if (!owner.isOnline()) return;
        spawnLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, spawnLoc.clone().add(0, 1.0, 0), 35, 0.5, 0.8, 0.5, 0.06);
        spawnLoc.getWorld().spawnParticle(Particle.SOUL, spawnLoc.clone().add(0, 1.0, 0), 15, 0.4, 0.6, 0.4, 0.04);
        spawnLoc.getWorld().playSound(spawnLoc, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0f, 0.7f);
        Entity spawned = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
        if (spawned instanceof LivingEntity living) {
            living.setCustomName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + owner.getName() + "'s " + formatMobName(type.name()) + " Soul");
            living.setCustomNameVisible(true);
            living.setRemoveWhenFarAway(false);
            living.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 620, 0, false, false));
            living.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 620, 0, false, false));
        }
        UUID ownerId = owner.getUniqueId();
        activeSummons.put(ownerId, new ActiveSummon(ownerId, spawned.getUniqueId(), System.currentTimeMillis() + SUMMON_DURATION_MS));
        summonToOwner.put(spawned.getUniqueId(), ownerId);
        owner.sendTitle(
                ChatColor.DARK_GRAY + "" + ChatColor.BOLD + formatMobName(type.name()).toUpperCase() + " SOUL",
                ChatColor.AQUA + "Empowered & Fighting for 30 seconds!",
                3, 25, 7
        );
    }

    private LivingEntity findNearestEnemy(Player owner, Mob summonMob, double radius) {
        LivingEntity best = null;
        double bestDistSq = radius * radius;
        for (Entity ent : owner.getNearbyEntities(radius, radius, radius)) {
            if (ent.equals(owner) || ent.equals(summonMob) || ent.isDead()) continue;
            if (summonToOwner.containsKey(ent.getUniqueId())) continue;
            if (fearedMobsUntil.containsKey(ent.getUniqueId())) continue;
            if (ent instanceof Monster monster) {
                double d = monster.getLocation().distanceSquared(summonMob.getLocation());
                if (d == Math.min(d, bestDistSq)) {
                    bestDistSq = d;
                    best = monster;
                }
            } else if (ent instanceof Player other && other.getWorld().getPVP()) {
                double d = other.getLocation().distanceSquared(summonMob.getLocation());
                if (d == Math.min(d, bestDistSq)) {
                    bestDistSq = d;
                    best = other;
                }
            }
        }
        return best;
    }

    // Prevent summoned soul from ever targeting its owner, and prevent feared mobs from targeting anyone
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulTarget(EntityTargetLivingEntityEvent event) {
        if (fearedMobsUntil.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        UUID ownerId = (UUID) summonToOwner.get(event.getEntity().getUniqueId());
        if (ownerId != null && event.getTarget() != null && event.getTarget().getUniqueId().equals(ownerId)) {
            event.setCancelled(true);
        }
    }

    // Boost summoned soul attack damage (2.5x + 4.0 HP) and prevent it from ever hurting its owner
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulCombatDamage(EntityDamageByEntityEvent event) {
        UUID ownerId = (UUID) summonToOwner.get(event.getDamager().getUniqueId());
        if (ownerId == null && event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
            ownerId = (UUID) summonToOwner.get(shooter.getUniqueId());
        }
        if (ownerId != null) {
            if (event.getEntity().getUniqueId().equals(ownerId)) {
                event.setCancelled(true);
                return;
            }
            event.setDamage(event.getDamage() * 2.5 + 4.0);
        }
    }

    // Summoned Creeper soul explosion: deals heavy damage + inflicts Blindness & Fear on nearby mobs/enemies (except owner)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulCreeperExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof Creeper creeper) {
            UUID ownerId = (UUID) summonToOwner.get(creeper.getUniqueId());
            if (ownerId != null) {
                event.blockList().clear();
                Location blastLoc = event.getLocation();
                blastLoc.getWorld().spawnParticle(Particle.LARGE_SMOKE, blastLoc.clone().add(0, 1.0, 0), 50, 2.5, 1.5, 2.5, 0.05);
                blastLoc.getWorld().spawnParticle(Particle.SQUID_INK, blastLoc.clone().add(0, 1.0, 0), 30, 2.0, 1.2, 2.0, 0.04);
                long fearEnd = System.currentTimeMillis() + 4000L; // 4 seconds of Blindness & Fear
                for (Entity ent : blastLoc.getWorld().getNearbyEntities(blastLoc, 6.0, 6.0, 6.0)) {
                    if (ent.getUniqueId().equals(ownerId) || ent.equals(creeper)) continue;
                    if (summonToOwner.containsKey(ent.getUniqueId())) continue;
                    if (ent instanceof LivingEntity living) {
                        living.damage(18.0);
                        living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 1, false, false));
                        living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, false, false));
                        if (living instanceof Mob mob) {
                            mob.setTarget(null);
                            fearedMobsUntil.put(mob.getUniqueId(), fearEnd);
                            Vector fleeDir = mob.getLocation().toVector().subtract(blastLoc.toVector()).setY(0.35);
                            if (fleeDir.lengthSquared() != 0.0) {
                                mob.setVelocity(fleeDir.normalize().multiply(1.3));
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String... args) {
        List argList = Arrays.asList(args);
        if (command.getName().equalsIgnoreCase("sr")) {
            if (!sender.hasPermission("soulreaper.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }
            if (!argList.isEmpty()) {
                String sub = (String) argList.get(0);
                if (sub.equalsIgnoreCase("get") || sub.equalsIgnoreCase("give")) {
                    Player target = null;
                    if (argList.size() == 1 && sender instanceof Player p) {
                        target = p;
                    } else if (argList.size() == 2) {
                        target = Bukkit.getPlayerExact((String) argList.get(1));
                    }
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Usage: /sr get | /sr give ");
                        return true;
                    }
                    target.getInventory().addItem(createSoulReaper());
                    sender.sendMessage(ChatColor.GREEN + "Gave a " + ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Soul Reaper" + ChatColor.GREEN + " to " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN + "!");
                    return true;
                }
                if (sub.equalsIgnoreCase("reset")) {
                    Player target = null;
                    if (argList.size() == 1 && sender instanceof Player p) {
                        target = p;
                    } else if (argList.size() == 2) {
                        target = Bukkit.getPlayerExact((String) argList.get(1));
                    }
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Usage: /sr reset [player]");
                        return true;
                    }
                    cooldownEnd.remove(target.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Reset Soul Reaper cooldown for " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN + "!");
                    return true;
                }
            }
            sender.sendMessage(ChatColor.YELLOW + "Usage: /sr <get|give|reset> [player]");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("soul")) {
            if (!argList.isEmpty() && ((String) argList.get(0)).equalsIgnoreCase("recipe")) {
                openRecipeGui(player);
                return true;
            }
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            ItemStack hand = player.getInventory().getItemInMainHand();
            List souls = isSoulReaper(hand) ? getStoredSouls(hand) : new ArrayList();
            int count = souls.size();
            String nextInfo = souls.isEmpty() ? "" : (ChatColor.GRAY + " | Next: " + ChatColor.AQUA + formatMobName((String) souls.get(0)));
            if (activeSummons.containsKey(uuid)) {
                ActiveSummon summon = (ActiveSummon) activeSummons.get(uuid);
                long secLeft = Math.max(1L, (long) Math.ceil((summon.endTime - now) / 1000.0));
                player.sendMessage(ChatColor.DARK_PURPLE + "Soul Reaper: " + ChatColor.AQUA + "Soul Fighting (" + secLeft + "s left) " + ChatColor.GRAY + "| Stored Souls: " + ChatColor.WHITE + count + nextInfo);
                return true;
            }
            long cdEnd = (Long) cooldownEnd.getOrDefault(uuid, 0L);
            if (now != Math.max(now, cdEnd)) {
                long secLeft = (long) Math.ceil((cdEnd - now) / 1000.0);
                player.sendMessage(ChatColor.DARK_PURPLE + "Soul Reaper: " + ChatColor.RED + "Cooldown " + secLeft + "s remaining " + ChatColor.GRAY + "| Stored Souls: " + ChatColor.WHITE + count + nextInfo);
                return true;
            }
            player.sendMessage(ChatColor.DARK_PURPLE + "Soul Reaper: " + ChatColor.GREEN + "READY! " + ChatColor.GRAY + "| Stored Souls: " + ChatColor.WHITE + count + nextInfo + ChatColor.GRAY + " (Type /soul recipe to view crafting)");
            return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ActiveSummon summon = (ActiveSummon) activeSummons.remove(uuid);
        if (summon != null) {
            summonToOwner.remove(summon.mobId);
            Entity ent = Bukkit.getEntity(summon.mobId);
            if (ent != null) ent.remove();
        }
    }

    @Override
    public void onDisable() {
        for (Object obj : new ArrayList(activeSummons.values())) {
            ActiveSummon summon = (ActiveSummon) obj;
            Entity ent = Bukkit.getEntity(summon.mobId);
            if (ent != null) ent.remove();
        }
        activeSummons.clear();
        summonToOwner.clear();
        fearedMobsUntil.clear();
    }
}