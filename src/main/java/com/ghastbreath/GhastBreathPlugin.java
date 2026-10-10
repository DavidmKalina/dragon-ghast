package com.ghastbreath;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ghast Breath: right-click a grown happy ghast with dragon's breath and it changes for good.
 * <ul>
 * <li>It flies faster while ridden (a flying speed modifier, added only while a player is on it).</li>
 * <li>It wears a regular ghast's face. The harness model is a box exactly around the happy ghast's body, so a
 * resource-pack "harness" whose texture is the whole ghast body (assets/ghastbreath/equipment/) covers the
 * happy face. Each player with the pack is sent that look per viewer; the real body slot keeps the real
 * harness (or nothing), so riding, shearing and dispensers all work as usual. With a harness on, the look has
 * the ghast body under that harness colour, so the harness still shows.</li>
 * <li>Faint purple breath drifts off it ({@link BreathEffects}).</li>
 * </ul>
 */
public class GhastBreathPlugin extends JavaPlugin implements Listener {
	private static final String NAMESPACE = "ghastbreath";
	/** The look of a changed ghast with no harness: just the regular ghast body. */
	private static final Key BARE_LOOK = Key.key(NAMESPACE, "ghast_face");
	/**
	 * The looks are in the server resource pack, which the ResourcePack plugin sends. It sets this player metadata
	 * while a player's game has the pack loaded.
	 */
	private static final String PACK_LOADED = "resource-pack-loaded";

	/** On the ghast: set once it has had the breath. */
	private NamespacedKey breathedKey;
	private NamespacedKey speedKey;
	private Material item;
	private double speedMultiplier;
	private boolean ghastLook;
	private boolean particles;

	/** Changed ghasts in loaded chunks. */
	private final Map<UUID, HappyGhast> loaded = new HashMap<>();

	@Override
	public void onEnable() {
		breathedKey = new NamespacedKey(this, "breathed");
		speedKey = new NamespacedKey(this, "ridden_speed");
		saveDefaultConfig();
		Material configured = Material.matchMaterial(getConfig().getString("item", "DRAGON_BREATH"));
		if (configured == null || !configured.isItem()) {
			getLogger().warning("Unknown item '" + getConfig().getString("item") + "', using dragon's breath");
			configured = Material.DRAGON_BREATH;
		}
		item = configured;
		speedMultiplier = Math.max(0.1, getConfig().getDouble("ridden-speed-multiplier", 2.0));
		ghastLook = getConfig().getBoolean("ghast-look", true);
		particles = getConfig().getBoolean("particles", true);

		getServer().getPluginManager().registerEvents(this, this);
		for (World world : Bukkit.getWorlds()) {
			for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
				if (isBreathed(ghast)) {
					loaded.put(ghast.getUniqueId(), ghast);
				}
			}
		}
		// Keeps the speed in step with riders, and re-sends the look in case anything replaced it
		Bukkit.getScheduler().runTaskTimer(this, this::refreshAll, 40, 40);
		if (particles) {
			BreathEffects effects = new BreathEffects();
			Bukkit.getScheduler().runTaskTimer(this, () -> effects.tick(loaded), 1, 1);
		}
	}

	@Override
	public void onDisable() {
		for (HappyGhast ghast : loaded.values()) {
			setFast(ghast, false);
		}
		loaded.clear();
	}

	boolean isBreathed(Entity entity) {
		return entity instanceof HappyGhast && entity.getPersistentDataContainer().has(breathedKey);
	}

	// ---- Giving the breath ----

	@EventHandler(ignoreCancelled = true)
	public void onInteract(PlayerInteractEntityEvent event) {
		if (!(event.getRightClicked() instanceof HappyGhast ghast)) {
			return;
		}
		Player player = event.getPlayer();
		ItemStack hand = player.getInventory().getItem(event.getHand());
		if (hand.getType() != item) {
			return;
		}
		event.setCancelled(true); // don't also climb on
		if (!ghast.isAdult()) {
			player.sendActionBar(Component.text("This ghastling is too young for that. Let it grow up first.", NamedTextColor.GRAY));
			return;
		}
		if (isBreathed(ghast)) {
			player.sendActionBar(Component.text("This ghast has already had the breath.", NamedTextColor.GRAY));
			return;
		}

		if (player.getGameMode() != GameMode.CREATIVE) {
			Material remainder = hand.getType().getCraftingRemainingItem();
			hand.subtract();
			if (remainder != null) {
				player.getInventory().addItem(ItemStack.of(remainder)).values()
						.forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
			}
		}
		player.swingHand(event.getHand());

		ghast.getPersistentDataContainer().set(breathedKey, PersistentDataType.BYTE, (byte) 1);
		loaded.put(ghast.getUniqueId(), ghast);
		updateSpeed(ghast);
		sendLook(ghast);

		Location centre = ghast.getBoundingBox().getCenter().toLocation(ghast.getWorld());
		breath(ghast.getTrackedBy(), centre, 120, 1.6, 0.02);
		ghast.getWorld().playSound(centre, Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
		ghast.getWorld().playSound(centre, Sound.ENTITY_GHAST_WARN, 1f, 0.9f);
		player.sendActionBar(Component.text("The ghast breathes in the dragon's breath. It feels faster!", NamedTextColor.LIGHT_PURPLE));
	}

	/** Real dragon's breath particles (lingering purple puffs), shown to these players. */
	static void breath(Collection<Player> viewers, Location at, int count, double spread, double speed) {
		Particle particle = Particle.DRAGON_BREATH;
		Class<?> data = particle.getDataType();
		for (Player viewer : viewers) {
			if (data == Float.class) {
				viewer.spawnParticle(particle, at, count, spread, spread, spread, speed, 1f);
			} else if (data == Void.class) {
				viewer.spawnParticle(particle, at, count, spread, spread, spread, speed);
			} else {
				viewer.spawnParticle(Particle.REVERSE_PORTAL, at, count, spread, spread, spread, speed);
			}
		}
	}

	// ---- Speed (only while ridden) ----

	private void updateSpeed(HappyGhast ghast) {
		setFast(ghast, ghast.isValid() && ghast.getPassengers().stream().anyMatch(Player.class::isInstance));
	}

	private void setFast(HappyGhast ghast, boolean fast) {
		AttributeInstance speed = ghast.getAttribute(Attribute.FLYING_SPEED);
		if (speed == null) {
			return;
		}
		boolean has = speed.getModifier(speedKey) != null;
		if (fast && !has) {
			// Transient, so it is never saved with the ghast
			speed.addTransientModifier(new AttributeModifier(speedKey, speedMultiplier - 1,
					AttributeModifier.Operation.MULTIPLY_SCALAR_1));
		} else if (!fast && has) {
			speed.removeModifier(speedKey);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMount(EntityMountEvent event) {
		if (isBreathed(event.getMount())) {
			HappyGhast ghast = (HappyGhast) event.getMount();
			Bukkit.getScheduler().runTask(this, () -> updateSpeed(ghast));
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDismount(EntityDismountEvent event) {
		if (isBreathed(event.getDismounted())) {
			HappyGhast ghast = (HappyGhast) event.getDismounted();
			Bukkit.getScheduler().runTask(this, () -> updateSpeed(ghast));
		}
	}

	// ---- The ghast look ----

	/**
	 * What a player with the pack is shown in the ghast's body slot: the same harness drawn over a regular ghast
	 * body, or the bare ghast body when it has no harness. Null to leave it alone (something unexpected is worn).
	 */
	ItemStack lookFor(HappyGhast ghast) {
		ItemStack worn = ghast.getEquipment().getItem(EquipmentSlot.BODY);
		Key look;
		ItemStack shown;
		if (worn.isEmpty()) {
			look = BARE_LOOK;
			shown = ItemStack.of(Material.WHITE_HARNESS);
		} else {
			Equippable equippable = worn.getData(DataComponentTypes.EQUIPPABLE);
			String name = worn.getType().name();
			// Only the plain harnesses, which have a matching look in the pack
			if (equippable == null || !name.endsWith("_HARNESS")
					|| !Key.key(Key.MINECRAFT_NAMESPACE, name.toLowerCase(Locale.ROOT)).equals(equippable.assetId())) {
				return null;
			}
			look = Key.key(NAMESPACE, "ghast_" + name.toLowerCase(Locale.ROOT));
			shown = worn.clone();
		}
		shown.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.BODY)
				.assetId(look).build());
		return shown;
	}

	private void sendLook(HappyGhast ghast) {
		for (Player viewer : ghast.getTrackedBy()) {
			sendLook(ghast, viewer);
		}
	}

	private void sendLook(HappyGhast ghast, Player viewer) {
		if (!ghastLook || !ghast.isValid() || !viewer.isOnline() || !viewer.hasMetadata(PACK_LOADED)) {
			return;
		}
		ItemStack look = lookFor(ghast);
		if (look != null) {
			viewer.sendEquipmentChange(ghast, EquipmentSlot.BODY, look);
		}
	}

	/** A player starts seeing a changed ghast: its normal equipment is sent with it, so swap in the look after. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTrack(PlayerTrackEntityEvent event) {
		if (isBreathed(event.getEntity())) {
			HappyGhast ghast = (HappyGhast) event.getEntity();
			Player viewer = event.getPlayer();
			Bukkit.getScheduler().runTask(this, () -> {
				if (ghast.getTrackedBy().contains(viewer)) {
					sendLook(ghast, viewer);
				}
			});
		}
	}

	/** A harness was put on or taken off: everyone was just sent the real one. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onEquipmentChange(EntityEquipmentChangedEvent event) {
		if (isBreathed(event.getEntity()) && event.getEquipmentChanges().containsKey(EquipmentSlot.BODY)) {
			HappyGhast ghast = (HappyGhast) event.getEntity();
			Bukkit.getScheduler().runTask(this, () -> sendLook(ghast));
		}
	}

	/** The pack just finished loading: show the look on the changed ghasts this player can already see. */
	@EventHandler
	public void onPackStatus(PlayerResourcePackStatusEvent event) {
		Player player = event.getPlayer();
		if (event.getStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED || !player.hasMetadata(PACK_LOADED)) {
			return;
		}
		for (HappyGhast ghast : loaded.values()) {
			if (ghast.getTrackedBy().contains(player)) {
				sendLook(ghast, player);
			}
		}
	}

	// ---- Keeping track of loaded changed ghasts ----

	@EventHandler
	public void onAdd(EntityAddToWorldEvent event) {
		if (isBreathed(event.getEntity())) {
			loaded.put(event.getEntity().getUniqueId(), (HappyGhast) event.getEntity());
		}
	}

	@EventHandler
	public void onRemove(EntityRemoveFromWorldEvent event) {
		if (loaded.remove(event.getEntity().getUniqueId()) != null) {
			setFast((HappyGhast) event.getEntity(), false);
		}
	}

	private void refreshAll() {
		loaded.values().removeIf(ghast -> !ghast.isValid());
		for (HappyGhast ghast : loaded.values()) {
			updateSpeed(ghast);
			sendLook(ghast);
		}
	}
}
