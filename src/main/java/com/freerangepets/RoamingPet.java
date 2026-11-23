/*
 * Copyright (c) 2026, maiko
 * Copyright (c) 2026, Varzeki
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.freerangepets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Simulation state for a single free-range POH pet across Gielinor.
 *
 * <p>Uses a three-tier simulation model ({@link PetTier#SCENE},
 * {@link PetTier#NEAR}, {@link PetTier#FAR}): nearby pets walk tile-by-tile
 * over live collision and {@link WorldMesh}, traverse doors, ladders, stairs,
 * stiles, caves, and shortcuts using their own safe walk/idle animations, and
 * keep personal space from other pets; distant pets follow pure-function
 * {@link Itinerary} legs planned over the full world mesh and transport network.
 */
class RoamingPet
{
	static final int TILE = 128;
	static final int PENNED_TILES = 80;

	private static final int CYCLES_PER_TILE = 30;
	private static final int TURN_PER_CYCLE = 24;
	private static final int DWELL_MIN = 25;
	private static final int DWELL_SPREAD = 135;

	private static final int MIN_COOLDOWN_TICKS = 25;
	private static final int PENNED_COOLDOWN_TICKS = 8;
	private static final int OPEN_DOOR_SPAN = 2;
	private static final int TRANSPORT_SEEK_RADIUS = 12;
	private static final int WAY_OUT_REACH = 10;
	private static final float SCENE_TRANSPORT_CHANCE = 0.25f;
	private static final float STEP_TRANSPORT_CHANCE = 0.35f;

	private static final int STUCK_SEARCHES = 12;
	private static final int STUCK_TICKS = 500;
	private static final int PROGRESS_TILES = 3;

	@Getter
	private final long id;

	@Getter
	@Setter
	private PetSnapshot snapshot;

	private final Random random;
	private final TransportMemory transportMemory = new TransportMemory();

	@Getter
	@Setter
	private volatile String nickname;

	@Getter
	@Setter
	private volatile boolean favourite;

	@Getter
	private int fineX;

	@Getter
	private int fineY;

	@Getter
	private int plane;

	@Getter
	private int orientation;

	private int targetOrientation;

	@Getter
	private boolean walking;

	@Getter
	@Setter
	private volatile boolean pausedByFollower;

	@Getter
	@Setter
	private FakePet renderer;

	@Getter
	private PetTier tier = PetTier.FAR;

	/** Tile-by-tile path and step interpolation for SCENE and NEAR tiers. */
	private final Deque<int[]> path = new ArrayDeque<>();
	private int stepFromX;
	private int stepFromY;
	private int stepToX;
	private int stepToY;
	private int stepElapsed;
	private boolean stepping;
	private int dwellCycles;

	/** Smooth pet-safe crossing across a local transport (door, stile, bridge, etc.). */
	private WorldTransport activeTransport;
	private int transitionFromX;
	private int transitionFromY;
	private int transitionToX;
	private int transitionToY;
	private int transitionElapsed;
	private int transitionTotal;

	private WorldTransport queuedTransport;
	private WorldTransport wayOffDeadEnd;

	/** Off-screen itinerary and periodic wakeup schedule for FAR tier. */
	private Itinerary itinerary;
	private int nextFarCheck = Integer.MIN_VALUE;
	private int farFailures;

	/** Watchdog tracking stuck pets. */
	private int failedSearches;
	private int progressX;
	private int progressY;
	private int progressTick;

	RoamingPet(long id, PetSnapshot snapshot, WorldPoint startTile, int orientation)
	{
		this.id = id;
		this.snapshot = snapshot;
		this.random = new Random(id * 0x9E3779B97F4A7C15L);
		this.plane = startTile.getPlane();
		this.fineX = startTile.getX() * TILE + TILE / 2;
		this.fineY = startTile.getY() * TILE + TILE / 2;
		this.orientation = orientation & 2047;
		this.targetOrientation = this.orientation;
		this.dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	String displayName()
	{
		String nick = nickname;
		return nick != null && !nick.isEmpty() ? nick : snapshot.getName();
	}

	boolean isCustomNamed()
	{
		String nick = nickname;
		return nick != null && !nick.isEmpty();
	}

	WorldPoint currentTile()
	{
		return new WorldPoint(fineX / TILE, fineY / TILE, plane);
	}

	/**
	 * Safe tile for persistence: the end of a FAR itinerary (verified walkable
	 * ground) or the current tile when simulated locally.
	 */
	WorldPoint saveTile()
	{
		return itinerary != null ? itinerary.destination() : currentTile();
	}

	boolean inTransition()
	{
		return activeTransport != null;
	}

	void relocate(WorldPoint tile)
	{
		this.plane = tile.getPlane();
		this.fineX = tile.getX() * TILE + TILE / 2;
		this.fineY = tile.getY() * TILE + TILE / 2;
		this.path.clear();
		this.stepping = false;
		this.stepElapsed = 0;
		this.walking = false;
		this.activeTransport = null;
		this.queuedTransport = null;
		this.wayOffDeadEnd = null;
		this.itinerary = null;
		this.dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	boolean farCheckDue(int tick)
	{
		return tick >= nextFarCheck;
	}

	void scheduleFarCheck(int tick, int interval)
	{
		if (itinerary == null || itinerary.isFinished(tick))
		{
			nextFarCheck = tick;
			return;
		}
		int slot = (int) ((id ^ (id >>> 32)) & 0x7FFFFFFF) % Math.max(1, interval);
		int next = tick + interval - (tick + slot) % Math.max(1, interval);
		nextFarCheck = Math.min(next, itinerary.getStartTick() + itinerary.getDuration());
	}

	void catchUpFar(int tick)
	{
		if (itinerary != null && !itinerary.isFinished(tick))
		{
			this.fineX = itinerary.fineXAt(tick);
			this.fineY = itinerary.fineYAt(tick);
			this.plane = itinerary.planeAt(tick);
			int[] heading = itinerary.headingAt(tick);
			if (heading[0] != 0 || heading[1] != 0)
			{
				this.orientation = headingFor(heading[0], heading[1]);
				this.targetOrientation = this.orientation;
			}
		}
	}

	boolean setTier(PetTier now, RoamContext context, RoamPlanner planner)
	{
		if (now == tier)
		{
			return false;
		}
		boolean wasFar = tier == PetTier.FAR;
		tier = now;

		if (now == PetTier.FAR)
		{
			if (inTransition())
			{
				finishTransition();
			}
			queuedTransport = null;
			wayOffDeadEnd = null;
			path.clear();
			stepping = false;
			walking = false;
			planFar(context, planner);
			return true;
		}

		if (wasFar)
		{
			WorldPoint resolved = itinerary != null
				? itinerary.positionAt(context.getTick())
				: currentTile();
			itinerary = null;
			WorldPoint snapped = planner == null ? resolved : planner.snapToMesh(resolved);
			relocate(snapped);
			noteUnstuck(context.getTick());
		}
		return true;
	}

	/**
	 * Advances a {@link PetTier#FAR} pet along its current {@link Itinerary},
	 * planning a fresh leg once the previous one finishes.
	 */
	void advanceFar(RoamContext context, RoamPlanner planner)
	{
		if (pausedByFollower)
		{
			return;
		}
		int tick = context.getTick();
		if (itinerary != null)
		{
			if (tick < itinerary.getStartTick())
			{
				// Tick counter reset (e.g. world hop): replan cleanly from current tile.
				itinerary = null;
			}
			else if (!itinerary.isFinished(tick))
			{
				catchUpFar(tick);
				return;
			}
			else
			{
				WorldPoint end = itinerary.destination();
				this.fineX = end.getX() * TILE + TILE / 2;
				this.fineY = end.getY() * TILE + TILE / 2;
				this.plane = end.getPlane();
				itinerary = null;
			}
		}
		planFar(context, planner);
	}

	private void planFar(RoamContext context, RoamPlanner planner)
	{
		if (planner == null)
		{
			return;
		}
		int tick = context.getTick();
		Itinerary next = planner.plan(currentTile(), tick, random, transportMemory, context);
		if (next != null)
		{
			itinerary = next;
			farFailures = 0;
		}
		else
		{
			itinerary = RoamPlanner.idle(currentTile(), tick, random, farFailures++);
			failedSearches++;
		}
	}

	/**
	 * Advances a {@link PetTier#SCENE} or {@link PetTier#NEAR} pet by
	 * {@code cycles} client cycles (20ms each).
	 *
	 * @return true if this pet consumed a BFS path search on this frame
	 */
	boolean advanceLocal(int cycles, RoamContext context)
	{
		if (cycles <= 0 || pausedByFollower)
		{
			walking = false;
			return false;
		}

		turnToward(cycles);
		boolean searched = false;
		int remaining = cycles;

		while (remaining > 0)
		{
			if (inTransition())
			{
				int left = transitionTotal - transitionElapsed;
				int step = Math.min(remaining, left);
				transitionElapsed += step;
				remaining -= step;

				if (activeTransport.isLocalCrossing())
				{
					fineX = transitionFromX + (transitionToX - transitionFromX) * transitionElapsed / transitionTotal;
					fineY = transitionFromY + (transitionToY - transitionFromY) * transitionElapsed / transitionTotal;
					walking = true;
				}
				else
				{
					walking = false;
				}

				if (transitionElapsed >= transitionTotal)
				{
					finishTransition();
				}
				continue;
			}

			if (!stepping)
			{
				if (takeQueuedTransport(context))
				{
					return searched;
				}

				if (path.isEmpty())
				{
					walking = false;
					dwellCycles -= remaining;
					if (dwellCycles > 0 || !context.isMayPath())
					{
						return searched;
					}
					searched = true;
					remaining = 0;
					chooseLocalGoal(context);
					continue;
				}

				int[] next = path.peek();
				int curX = fineX / TILE;
				int curY = fineY / TILE;
				int dx = next[0] - curX;
				int dy = next[1] - curY;
				if (Math.abs(dx) > 1 || Math.abs(dy) > 1
					|| !context.getCollision().canStep(curX, curY, plane, dx, dy))
				{
					path.clear();
					queuedTransport = null;
					wayOffDeadEnd = null;
					walking = false;
					dwellCycles = DWELL_MIN;
					return searched;
				}
				beginStep(path.poll());
			}

			int left = CYCLES_PER_TILE - stepElapsed;
			int advance = Math.min(remaining, left);
			stepElapsed += advance;
			remaining -= advance;

			fineX = stepFromX + (stepToX - stepFromX) * stepElapsed / CYCLES_PER_TILE;
			fineY = stepFromY + (stepToY - stepFromY) * stepElapsed / CYCLES_PER_TILE;

			if (stepElapsed >= CYCLES_PER_TILE)
			{
				stepping = false;
				if (takeQueuedTransport(context))
				{
					return searched;
				}
				if (queuedTransport == null && tryTransportOnCurrentTile(context))
				{
					return searched;
				}
				if (path.isEmpty())
				{
					walking = false;
					dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
					return searched;
				}
			}
		}
		return searched;
	}

	boolean isStuck(int tick)
	{
		if (pausedByFollower || inTransition())
		{
			return false;
		}
		if (itinerary != null && !itinerary.isFinished(tick))
		{
			return false;
		}
		if (failedSearches >= STUCK_SEARCHES)
		{
			return true;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		if (progressTick == 0 || tick < progressTick
			|| Math.abs(tileX - progressX) + Math.abs(tileY - progressY) >= PROGRESS_TILES)
		{
			progressX = tileX;
			progressY = tileY;
			progressTick = tick;
			return false;
		}
		return tick - progressTick > STUCK_TICKS;
	}

	void noteUnstuck(int tick)
	{
		failedSearches = 0;
		farFailures = 0;
		progressTick = tick;
		progressX = fineX / TILE;
		progressY = fineY / TILE;
	}

	boolean workFree(RoamContext context, RoamPlanner planner)
	{
		int tick = context.getTick();
		if (tier == PetTier.FAR)
		{
			Itinerary replanned = planner.plan(currentTile(), tick, random, transportMemory, context);
			if (replanned != null)
			{
				itinerary = replanned;
				noteUnstuck(tick);
				return true;
			}
			return false;
		}
		if (takeWayOut(context, true) || walkToWayOut(context))
		{
			noteUnstuck(tick);
			return true;
		}
		chooseLocalGoal(context);
		if (!path.isEmpty() || inTransition())
		{
			noteUnstuck(tick);
			return true;
		}
		return false;
	}

	int currentPoseAnimation()
	{
		if (walking && snapshot.getWalkAnimation() >= 0)
		{
			return snapshot.getWalkAnimation();
		}
		return snapshot.getIdlePoseAnimation();
	}

	private void chooseLocalGoal(RoamContext context)
	{
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;

		// If standing on a tile we cannot step off (stepping stone / ladder landing), take a way out.
		if (!context.getCollision().isKnownWalkable(tileX, tileY, plane))
		{
			if (takeWayOut(context, true))
			{
				failedSearches = 0;
				return;
			}
		}

		// Escape dead-end pockets back out the way we came if needed.
		if (headHome(context))
		{
			failedSearches = 0;
			return;
		}

		boolean pressed = context.crowdedRegion(tileX, tileY, plane);
		boolean penned = context.enclosedArea(tileX, tileY, plane) < PENNED_TILES;
		if (pressed || penned
			|| (!transportMemory.restingFromTransports(context.getTick()) && random.nextFloat() < SCENE_TRANSPORT_CHANCE))
		{
			if (seekNearbyTransport(context, pressed || penned))
			{
				failedSearches = 0;
				return;
			}
		}

		RoamBounds bounds = new RoamBounds(
			context.getCollision(),
			plane,
			tileX,
			tileY,
			(x, y) -> context.nearAnotherPet(x, y, plane, tileX, tileY)
		);
		Deque<int[]> found = context.getPathfinder().wanderPath(tileX, tileY, plane, bounds, random);
		if (!found.isEmpty())
		{
			path.addAll(found);
			failedSearches = 0;
			return;
		}

		// Small-room fallback: try walking to a nearby exit transport.
		if (walkToWayOut(context))
		{
			failedSearches = 0;
			return;
		}

		failedSearches++;
		dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	private boolean seekNearbyTransport(RoamContext context, boolean mayGoBack)
	{
		TransportNetwork network = context.getTransports();
		if (network == null)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		int tick = context.getTick();
		Set<Integer> cameFrom = mayGoBack ? Collections.emptySet()
			: context.spacesLeft(tileX, tileY, plane, transportMemory);

		List<WorldTransport> near = new ArrayList<>();
		network.near(tileX, tileY, TRANSPORT_SEEK_RADIUS, near);
		Collections.shuffle(near, random);

		int tries = 0;
		for (WorldTransport transport : near)
		{
			if (transport.getFromPlane() != plane
				|| transportMemory.onCooldown(transport, tick)
				|| !context.getAbilities().canUse(transport)
				|| !arrivesSomewhereKnown(transport, context)
				|| context.leadsInto(cameFrom, transport)
				|| !context.mayEnter(transport))
			{
				continue;
			}
			float onward = context.wayOnAppeal(transport);
			if (onward < 1f && random.nextFloat() >= onward)
			{
				continue;
			}
			float appeal = context.appeal(tileX, tileY, plane,
				transport.getToX(), transport.getToY(), transport.getToPlane());
			if (appeal < 1f && random.nextFloat() >= appeal)
			{
				continue;
			}
			if (tileX == transport.getFromX() && tileY == transport.getFromY())
			{
				take(transport, context);
				return true;
			}
			if (walkTo(transport.getFromX(), transport.getFromY(), context))
			{
				queuedTransport = transport;
				return true;
			}
			if (++tries >= 4)
			{
				break;
			}
		}
		return false;
	}

	private boolean tryTransportOnCurrentTile(RoamContext context)
	{
		TransportNetwork network = context.getTransports();
		if (network == null)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		List<WorldTransport> here = network.from(tileX, tileY);
		if (here.isEmpty())
		{
			return false;
		}
		int tick = context.getTick();
		boolean pressed = context.crowdedRegion(tileX, tileY, plane);
		if (!pressed && transportMemory.restingFromTransports(tick))
		{
			return false;
		}
		if (!pressed && random.nextFloat() >= STEP_TRANSPORT_CHANCE)
		{
			return false;
		}
		Set<Integer> cameFrom = pressed ? Collections.emptySet()
			: context.spacesLeft(tileX, tileY, plane, transportMemory);

		for (WorldTransport transport : here)
		{
			if (transport.getFromPlane() != plane
				|| transportMemory.onCooldown(transport, tick)
				|| !context.getAbilities().canUse(transport)
				|| !arrivesSomewhereKnown(transport, context)
				|| context.leadsInto(cameFrom, transport)
				|| !context.mayEnter(transport))
			{
				continue;
			}
			float appeal = context.appeal(tileX, tileY, plane,
				transport.getToX(), transport.getToY(), transport.getToPlane())
				* context.wayOnAppeal(transport);
			if (appeal < 1f && random.nextFloat() >= appeal)
			{
				continue;
			}
			take(transport, context);
			return true;
		}
		return false;
	}

	private boolean headHome(RoamContext context)
	{
		RoamPlanner planner = context.getPlanner();
		if (planner == null || random.nextFloat() >= RoamPlanner.HOMEWARD_CHANCE)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		WorldTransport home = planner.wayHome(tileX, tileY, plane, context.getTick(), transportMemory, random);
		if (home == null)
		{
			return false;
		}
		if (tileX == home.getFromX() && tileY == home.getFromY())
		{
			take(home, context);
			return true;
		}
		if (walkTo(home.getFromX(), home.getFromY(), context))
		{
			queuedTransport = home;
			wayOffDeadEnd = home;
			return true;
		}
		return false;
	}

	private boolean takeWayOut(RoamContext context, boolean evenBack)
	{
		TransportNetwork network = context.getTransports();
		if (network == null)
		{
			return false;
		}
		WorldTransport fresh = null;
		WorldTransport back = null;
		for (WorldTransport transport : network.from(fineX / TILE, fineY / TILE))
		{
			if (transport.getFromPlane() != plane
				|| !arrivesSomewhereKnown(transport, context)
				|| !context.getAbilities().canUse(transport))
			{
				continue;
			}
			if (!transportMemory.onCooldown(transport, context.getTick()))
			{
				if (context.mayEnter(transport))
				{
					take(transport, context);
					return true;
				}
				if (fresh == null)
				{
					fresh = transport;
				}
			}
			else if (back == null)
			{
				back = transport;
			}
		}
		if (fresh != null)
		{
			take(fresh, context);
			return true;
		}
		if (back != null && evenBack)
		{
			take(back, context);
			return true;
		}
		return false;
	}

	private boolean walkToWayOut(RoamContext context)
	{
		TransportNetwork network = context.getTransports();
		if (network == null)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		WorldTransport best = null;
		int bestSpan = Integer.MAX_VALUE;
		WorldTransport fallback = null;
		int fallbackSpan = Integer.MAX_VALUE;

		for (int dx = -WAY_OUT_REACH; dx <= WAY_OUT_REACH; dx++)
		{
			for (int dy = -WAY_OUT_REACH; dy <= WAY_OUT_REACH; dy++)
			{
				int dist = WorldLayout.span(dx, dy);
				if (dist >= bestSpan)
				{
					continue;
				}
				for (WorldTransport transport : network.from(tileX + dx, tileY + dy))
				{
					if (transport.getFromPlane() != plane
						|| !arrivesSomewhereKnown(transport, context)
						|| !context.getAbilities().canUse(transport))
					{
						continue;
					}
					if (!transportMemory.onCooldown(transport, context.getTick()) && context.mayEnter(transport))
					{
						best = transport;
						bestSpan = dist;
						break;
					}
					if (dist < fallbackSpan)
					{
						fallback = transport;
						fallbackSpan = dist;
					}
				}
			}
		}
		if (best == null)
		{
			best = fallback;
			bestSpan = fallbackSpan;
		}
		if (best == null)
		{
			return false;
		}
		if (bestSpan == 0)
		{
			wayOffDeadEnd = null;
			take(best, context);
			return true;
		}
		if (walkTo(best.getFromX(), best.getFromY(), context))
		{
			queuedTransport = best;
			wayOffDeadEnd = best;
			return true;
		}
		return false;
	}

	private boolean takeQueuedTransport(RoamContext context)
	{
		if (queuedTransport == null)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		if (tileX != queuedTransport.getFromX() || tileY != queuedTransport.getFromY())
		{
			if (path.isEmpty() && !stepping)
			{
				queuedTransport = null;
				wayOffDeadEnd = null;
			}
			return false;
		}
		WorldTransport transport = queuedTransport;
		queuedTransport = null;
		boolean escape = transport == wayOffDeadEnd;
		wayOffDeadEnd = null;

		if (!escape && (transportMemory.onCooldown(transport, context.getTick())
			|| !context.getAbilities().canUse(transport)))
		{
			return false;
		}
		take(transport, context);
		return true;
	}

	private void take(WorldTransport transport, RoamContext context)
	{
		if (standsOpen(transport, context) && walkTo(transport.getToX(), transport.getToY(), context))
		{
			transportMemory.walkedThrough(transport, context.getTick());
			return;
		}

		transportMemory.used(transport, context.getTick(), cooldownFor(transport, context));
		path.clear();
		stepping = false;

		if (tier != PetTier.SCENE)
		{
			relocate(transport.destination());
			return;
		}

		// Visible pets smoothly walk across short same-plane obstacles (doors, stiles, bridges)
		// using their own safe walk animation, and pause briefly before ascending/descending stairs or caves.
		activeTransport = transport;
		transitionFromX = fineX;
		transitionFromY = fineY;
		transitionToX = transport.getToX() * TILE + TILE / 2;
		transitionToY = transport.getToY() * TILE + TILE / 2;
		transitionElapsed = 0;

		if (transport.isLocalCrossing())
		{
			int span = Math.max(1, WorldLayout.span(
				transport.getToX() - transport.getFromX(),
				transport.getToY() - transport.getFromY()));
			transitionTotal = Math.max(CYCLES_PER_TILE, Math.min(120, Math.max(span, transport.getDuration()) * CYCLES_PER_TILE));
			targetOrientation = headingFor(transitionToX - transitionFromX, transitionToY - transitionFromY);
			orientation = targetOrientation;
			walking = true;
		}
		else
		{
			transitionTotal = Math.max(15, Math.min(45, Math.max(1, transport.getDuration()) * 15));
			walking = false;
		}
	}

	private void finishTransition()
	{
		WorldTransport done = activeTransport;
		activeTransport = null;
		walking = false;
		if (done != null)
		{
			plane = done.getToPlane();
			fineX = done.getToX() * TILE + TILE / 2;
			fineY = done.getToY() * TILE + TILE / 2;
		}
		dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD / 2);
	}

	private boolean standsOpen(WorldTransport transport, RoamContext context)
	{
		int x = transport.getFromX();
		int y = transport.getFromY();
		int toX = transport.getToX();
		int toY = transport.getToY();
		if (transport.getArchetype() != WorldTransport.ARCHETYPE_DOOR
			|| transport.getFromPlane() != transport.getToPlane()
			|| WorldLayout.span(toX - x, toY - y) > OPEN_DOOR_SPAN
			|| (x == toX && y == toY))
		{
			return false;
		}
		while (x != toX || y != toY)
		{
			int dx = Integer.signum(toX - x);
			int dy = Integer.signum(toY - y);
			if (!context.getCollision().canStep(x, y, transport.getFromPlane(), dx, dy))
			{
				return false;
			}
			x += dx;
			y += dy;
		}
		return true;
	}

	private int cooldownFor(WorldTransport transport, RoamContext context)
	{
		int x = transport.getToX();
		int y = transport.getToY();
		int toPlane = transport.getToPlane();
		if (!context.getCollision().isKnownWalkable(x, y, toPlane))
		{
			return TransportMemory.COOLDOWN_TICKS;
		}
		int room = Math.min(context.enclosedArea(x, y, toPlane),
			context.enclosedArea(fineX / TILE, fineY / TILE, plane));
		float share = Math.min(1f, room / (float) RoamContext.OPEN_AREA);
		int floor = room < PENNED_TILES ? PENNED_COOLDOWN_TICKS : MIN_COOLDOWN_TICKS;
		return Math.max(floor, Math.round(TransportMemory.COOLDOWN_TICKS * share));
	}

	private boolean arrivesSomewhereKnown(WorldTransport transport, RoamContext context)
	{
		if (context.getCollision().isKnownWalkable(transport.getToX(), transport.getToY(), transport.getToPlane()))
		{
			return true;
		}
		return context.getTransports().leadsToGround(
			transport.getToX(), transport.getToY(), transport.getToPlane(),
			transport.getFromX(), transport.getFromY(), transport.getFromPlane(),
			TransportNetwork.CHAIN_HOPS,
			context.getAbilities()::canUse,
			context.getCollision()::isKnownWalkable
		);
	}

	private boolean walkTo(int goalX, int goalY, RoamContext context)
	{
		int tileX = (stepping ? stepToX : fineX) / TILE;
		int tileY = (stepping ? stepToY : fineY) / TILE;
		if (tileX == goalX && tileY == goalY)
		{
			path.clear();
			return true;
		}
		path.clear();
		path.addAll(context.getPathfinder().findPath(tileX, tileY, plane, goalX, goalY,
			new RoamBounds(context.getCollision(), plane, tileX, tileY)));
		return !path.isEmpty();
	}

	private void beginStep(int[] tile)
	{
		stepFromX = fineX;
		stepFromY = fineY;
		stepToX = tile[0] * TILE + TILE / 2;
		stepToY = tile[1] * TILE + TILE / 2;
		stepElapsed = 0;
		stepping = true;
		walking = true;
		targetOrientation = headingFor(stepToX - stepFromX, stepToY - stepFromY);
	}

	private void turnToward(int cycles)
	{
		if (orientation == targetOrientation)
		{
			return;
		}
		int delta = ((targetOrientation - orientation + 1024) & 2047) - 1024;
		int maxTurn = TURN_PER_CYCLE * cycles;
		if (Math.abs(delta) <= maxTurn)
		{
			orientation = targetOrientation;
		}
		else
		{
			orientation = (orientation + Integer.signum(delta) * maxTurn) & 2047;
		}
	}

	static int headingFor(int dx, int dy)
	{
		if (dx == 0 && dy == 0)
		{
			return 0;
		}
		return (int) (1024 + Math.round(Math.atan2(dx, dy) / Math.PI * 1024)) & 2047;
	}

	static boolean isInScene(WorldView wv, int localX, int localY)
	{
		return localX >= 0 && localY >= 0
			&& localX < wv.getSizeX() * TILE
			&& localY < wv.getSizeY() * TILE;
	}
}
