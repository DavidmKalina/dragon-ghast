package com.ghastbreath;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Faint purple dragon's breath around a changed ghast, in the same style as FieryWings' fire: wisps dripping off
 * the tentacle tips while it drifts, and a wake streaming off its back while it flies fast.
 * <p>
 * Drawn with "trail" particles, which fly from where they're spawned to a target over an exact number of ticks
 * and then vanish. Ones that should stay with the ghast are aimed along its movement, so they keep up even at full
 * speed (ordinary particles stay where they're spawned for a second or two and get left behind).
 */
final class BreathEffects {
	private static final Color[] BREATH = {Color.fromRGB(226, 150, 255), Color.fromRGB(184, 86, 240), Color.fromRGB(128, 44, 196)};
	/** Movement per tick above which the ghast counts as flying fast and leaves a wake. */
	private static final double WAKE_SPEED = 0.12;
	/** Particles further away than this aren't drawn by the game anyway. */
	private static final double RANGE = 32;

	/** Where each ghast was last tick, to work out how it's moving. */
	private final Map<UUID, Location> lastSeen = new HashMap<>();
	private int ticks;

	void tick(Map<UUID, HappyGhast> ghasts) {
		ticks++;
		lastSeen.keySet().retainAll(ghasts.keySet());
		for (HappyGhast ghast : ghasts.values()) {
			if (!ghast.isValid()) {
				continue;
			}
			Location now = ghast.getLocation();
			Location before = lastSeen.put(ghast.getUniqueId(), now);
			if (ghast.isInvisible()) {
				continue;
			}
			Vector motion = before != null && before.getWorld().equals(now.getWorld()) ? now.toVector().subtract(before.toVector())
					: new Vector();
			if (motion.lengthSquared() > 25) {
				motion = new Vector(); // a teleport, not movement
			}
			Collection<Player> viewers = viewers(ghast);
			if (viewers.isEmpty()) {
				continue;
			}
			if (ticks % 4 == 0) {
				drip(ghast, motion, viewers);
			}
			if (motion.length() > WAKE_SPEED) {
				wake(ghast, motion, viewers);
			}
		}
	}

	private static Collection<Player> viewers(HappyGhast ghast) {
		Location at = ghast.getLocation();
		return ghast.getTrackedBy().stream()
				.filter(player -> player.getWorld().equals(at.getWorld()) && player.getLocation().distanceSquared(at) <= RANGE * RANGE)
				.toList();
	}

	/** A dot of colour that rides along with the ghast for {@code life} ticks, plus an optional drift. */
	private static void dot(Collection<Player> viewers, Location at, Vector motion, Vector drift, int life) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		// Start a tick ahead: the rider moves the ghast on their own screen before the server hears about it
		Location start = at.clone().add(motion);
		Location end = start.clone().add(motion.clone().multiply(life)).add(drift);
		Particle.Trail trail = new Particle.Trail(end, BREATH[random.nextInt(BREATH.length)], life);
		for (Player viewer : viewers) {
			viewer.spawnParticle(Particle.TRAIL, start, 1, 0, 0, 0, 0, trail);
		}
	}

	/** A wisp of breath dripping off one of the tentacle tips and sinking away (the tentacles hang 1-2 blocks below). */
	private static void drip(HappyGhast ghast, Vector motion, Collection<Player> viewers) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Location tip = ghast.getLocation().add(random.nextDouble(-1.3, 1.3), -random.nextDouble(0.9, 1.6), random.nextDouble(-1.3, 1.3));
		Vector sink = new Vector(random.nextDouble(-0.1, 0.1), -(0.35 + random.nextDouble(0.3)), random.nextDouble(-0.1, 0.1));
		dot(viewers, tip, motion, sink, 14 + random.nextInt(6));
	}

	/**
	 * Breath streaming off the back while it flies fast: streaks peeling away behind, and now and then a puff of
	 * real dragon's breath left hanging in the air where it has been.
	 */
	private void wake(HappyGhast ghast, Vector motion, Collection<Player> viewers) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Vector forward = motion.clone().normalize();
		Vector side = forward.clone().crossProduct(new Vector(0, 1, 0));
		if (side.lengthSquared() < 1.0E-4) {
			side = new Vector(1, 0, 0); // flying straight up or down
		}
		side.normalize();
		Vector up = side.clone().crossProduct(forward).normalize();
		Location centre = ghast.getLocation().add(0, 2, 0);
		Location back = centre.clone().subtract(forward.clone().multiply(1.9));
		for (int i = 0; i < 2; i++) {
			Location from = back.clone().add(side.clone().multiply(random.nextDouble(-1.7, 1.7)))
					.add(up.clone().multiply(random.nextDouble(-1.9, 1.7)));
			Vector away = forward.clone().multiply(-(0.8 + random.nextDouble(0.8)));
			dot(viewers, from, motion, away, 5 + random.nextInt(3));
		}
		if (ticks % 10 == 0) {
			Location puff = back.clone().subtract(forward.clone().multiply(0.6))
					.add(side.clone().multiply(random.nextDouble(-1.2, 1.2))).add(up.clone().multiply(random.nextDouble(-1.5, 1)));
			GhastBreathPlugin.breath(viewers, puff, 2, 0.15, 0.005);
		}
	}
}
