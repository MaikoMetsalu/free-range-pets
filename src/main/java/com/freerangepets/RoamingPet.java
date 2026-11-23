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
	private static final int WAY_OUT_REACH = 10;

	/** How far from the tile just entered a transport is noticed, and the base chance of taking it. */
	private static final int TRANSPORT_REACH = 3;
	private static final float TRANSPORT_CHANCE = 0.3f;

	/** How far a pet in a crowded region looks for a transport to leave by. */
	private static final int TRANSPORT_SEEK_RADIUS = 12;
	private static final int SEEK_TRIES = 4;

	/** Path search costs against the per-frame budget; see {@link RoamContext#spendSearch}. */
	private static final int FIND_PATH_COST = 1;
	private static final int WANDER_COST = 3;

	/** How long after a same-plane hop the next one still belongs to the same crossing, in ticks. */
	private static final int CROSSING_TICKS = 20;

	/** Turning to face an obstacle before using it: furthest far side, how far off, longest turn. */
	private static final int FACE_REACH = 12;
	private static final int FACE_FIRST_OFF = 256;
	private static final int FACE_MOST_CYCLES = 50;

	/** Peak height of a hop across stepping stones or a gap, in local units. */
	private static final int JUMP_ARC_HEIGHT = 26;

	/** Calls of {@link #workFree} spent spinning on the spot before backing out. */
	private static final int EDDY_CYCLES = 3;

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

	/** A transport being used by a visible pet: a walk or hop across, or a pause before a climb. */
	private WorldTransport activeTransport;
	private int transitionFromX;
	private int transitionFromY;
	private int transitionToX;
	private int transitionToY;
	private int transitionElapsed;
	private int transitionTotal;
	private boolean transitionLeap;

	/** An obstacle the pet is turning to face before it uses it. */
	private WorldTransport facingObstacle;
	private int facingCycles;

	/** Direction and tick of the last same-plane hop; see {@link #reversesLastHop}. */
	private int lastHopX;
	private int lastHopY;
	private int lastHopTick = Integer.MIN_VALUE;

	private WorldTransport queuedTransport;

	/** The way off a dead end the pet is walking to, taken whatever its cooldown. */
	private WorldTransport wayOffDeadEnd;

	/** Until this tick the pet stands and looks at the player. */
	private int watchUntil = Integer.MIN_VALUE;

	/** Off-screen itinerary and periodic wakeup schedule for FAR tier. */
	private Itinerary itinerary;
	private int nextFarCheck = Integer.MIN_VALUE;
	private int farFailures;

	/** Watchdog tracking stuck pets. */
	private int failedSearches;
	private int progressX;
	private int progressY;
	private int progressTick;
	private int eddyCycles;
	private boolean reversed;

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

	/** True while using a transport, or turning to face one: the pet may be standing on nothing. */
	boolean inTransition()
	{
		return activeTransport != null || facingObstacle != null;
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
		this.facingObstacle = null;
		this.queuedTransport = null;
		this.wayOffDeadEnd = null;
		this.itinerary = null;
		this.reversed = false;
		this.dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	/**
	 * Called when the client's tick counter goes backwards: everything remembered
	 * by tick would otherwise be in the far future and never expire.
	 */
	void timelineReset()
	{
		itinerary = null;
		nextFarCheck = Integer.MIN_VALUE;
		lastHopTick = Integer.MIN_VALUE;
		watchUntil = Integer.MIN_VALUE;
		progressTick = 0;
		transportMemory.clear();
	}

	boolean farCheckDue(int tick)
	{
		return tick >= nextFarCheck;
	}

	/** True when a FAR pet has no leg to follow and must plan one, which is rationed. */
	boolean needsFarPlan(int tick)
	{
		return itinerary == null || itinerary.isFinished(tick);
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

	void setTier(PetTier now, RoamContext context, RoamPlanner planner)
	{
		if (now == tier)
		{
			return;
		}
		boolean wasFar = tier == PetTier.FAR;
		tier = now;

		if (now == PetTier.FAR)
		{
			if (activeTransport != null)
			{
				finishTransition();
			}
			else if (facingObstacle != null)
			{
				// Out of sight there is nothing to turn for: it goes straight over.
				WorldTransport facing = facingObstacle;
				facingObstacle = null;
				relocate(facing.destination());
			}
			queuedTransport = null;
			wayOffDeadEnd = null;
			path.clear();
			stepping = false;
			walking = false;
			// Planned by the FAR loop, which rations planning: a whole roster going out of
			// view at once (entering an instance, say) must not plan in one frame.
			itinerary = null;
			nextFarCheck = Integer.MIN_VALUE;
			return;
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
	}

	/**
	 * Advances a {@link PetTier#FAR} pet along its current {@link Itinerary},
	 * planning a fresh leg once the previous one finishes.
	 *
	 * @return true if it planned a new leg, which the caller rations
	 */
	boolean advanceFar(RoamContext context, RoamPlanner planner)
	{
		if (pausedByFollower)
		{
			return false;
		}
		int tick = context.getTick();
		if (itinerary != null)
		{
			if (!itinerary.isFinished(tick))
			{
				catchUpFar(tick);
				return false;
			}
			WorldPoint end = itinerary.destination();
			this.fineX = end.getX() * TILE + TILE / 2;
			this.fineY = end.getY() * TILE + TILE / 2;
			this.plane = end.getPlane();
			itinerary = null;
		}
		planFar(context, planner);
		return true;
	}

	private void planFar(RoamContext context, RoamPlanner planner)
	{
		if (planner == null)
		{
			return;
		}
		int tick = context.getTick();
		reversed = false;
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
	 * {@code cycles} client cycles (20ms each). Path searches are paid for from
	 * the context's per-frame search budget.
	 */
	void advanceLocal(int cycles, RoamContext context)
	{
		if (cycles <= 0 || pausedByFollower)
		{
			walking = false;
			return;
		}

		turnToward(cycles);

		if (facingObstacle != null)
		{
			facingCycles -= cycles;
			if (orientation != targetOrientation && facingCycles > 0)
			{
				return;
			}
			WorldTransport transport = facingObstacle;
			facingObstacle = null;
			startObstacle(transport, context);
			return;
		}

		int remaining = cycles;
		while (remaining > 0)
		{
			if (activeTransport != null)
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
				// Standing to look at the player: the path waits until it has looked.
				if (context.getTick() < watchUntil)
				{
					walking = false;
					return;
				}

				if (takeQueuedTransport(context))
				{
					return;
				}

				if (path.isEmpty())
				{
					walking = false;
					dwellCycles -= remaining;
					if (dwellCycles > 0)
					{
						return;
					}
					// Overshoot is discarded rather than carried forward.
					remaining = 0;

					// Not a search, so not held up by the budget: a pet halfway across
					// stepping stones has nowhere to walk and must hop on.
					if (considerTransport(context))
					{
						return;
					}
					int tileX = fineX / TILE;
					int tileY = fineY / TILE;
					if (!context.getCollision().isKnownWalkable(tileX, tileY, plane) && takeWayOut(context, true))
					{
						return;
					}
					if (!context.hasSearchBudget())
					{
						// This frame's searches are spent: stand still and try next frame.
						return;
					}
					chooseDestination(context);
					continue;
				}

				int[] next = path.peek();
				int curX = fineX / TILE;
				int curY = fineY / TILE;
				int dx = next[0] - curX;
				int dy = next[1] - curY;
				if (dx == 0 && dy == 0)
				{
					path.poll();
					continue;
				}
				if (Math.abs(dx) > 1 || Math.abs(dy) > 1
					|| !context.getCollision().canStep(curX, curY, plane, dx, dy))
				{
					// A door open when the route was planned can be shut by now.
					path.clear();
					queuedTransport = null;
					wayOffDeadEnd = null;
					walking = false;
					dwellCycles = DWELL_MIN;
					return;
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
					return;
				}
				if (queuedTransport == null && considerTransport(context))
				{
					return;
				}
				if (path.isEmpty())
				{
					walking = false;
					dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
					return;
				}
			}
		}
	}

	/** True when the pet could stop to look at something without looking broken. */
	boolean mayWatch()
	{
		return !pausedByFollower && !inTransition() && tier == PetTier.SCENE;
	}

	/** Stops and turns to look along {@code (dx, dy)} until {@code untilTick}. */
	void watch(int untilTick, int dx, int dy)
	{
		watchUntil = untilTick;
		if (dx != 0 || dy != 0)
		{
			targetOrientation = headingFor(dx, dy);
		}
	}

	/** Extra height for the drawn model this frame: a parabola over a hop, zero otherwise. */
	int jumpArc()
	{
		if (activeTransport == null || !transitionLeap || transitionTotal <= 0)
		{
			return 0;
		}
		float done = transitionElapsed / (float) transitionTotal;
		if (done <= 0f || done >= 1f)
		{
			return 0;
		}
		return Math.round(JUMP_ARC_HEIGHT * 4f * done * (1f - done));
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
		eddyCycles = 0;
		progressTick = tick;
		progressX = fineX / TILE;
		progressY = fineY / TILE;
	}

	/**
	 * The cheap, invisible ways out of being stuck before the pet is picked up
	 * and moved: spin on the spot, back out the way it came, then re-plan.
	 *
	 * @return true if something was done about it
	 */
	boolean workFree(RoamContext context, RoamPlanner planner)
	{
		int tick = context.getTick();

		if (eddyCycles > 0)
		{
			eddyCycles--;
			targetOrientation = (orientation + 512) & 2047;
			return true;
		}

		if (!reversed && itinerary != null && !(itinerary.transport() != null && itinerary.isFinished(tick)))
		{
			Itinerary back = itinerary.reversed(tick, tick);
			if (back != null)
			{
				reversed = true;
				itinerary = back;
				eddyCycles = EDDY_CYCLES;
				return true;
			}
		}

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
		chooseDestination(context);
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

	/**
	 * Picks somewhere to go after a pause: now and then back out of a dead end,
	 * out of a crowded region by transport, otherwise a walk nearby.
	 */
	private void chooseDestination(RoamContext context)
	{
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		context.clearSearchRefused();

		if (headHome(context))
		{
			failedSearches = 0;
			return;
		}

		if (context.crowdedRegion(tileX, tileY, plane) && seekNearbyTransport(context))
		{
			failedSearches = 0;
			return;
		}

		Deque<int[]> found = context.spendSearch(WANDER_COST)
			? context.getPathfinder().wanderPath(tileX, tileY, plane, new RoamBounds(
				context.getCollision(),
				plane,
				tileX,
				tileY,
				(x, y) -> context.nearAnotherPet(x, y, plane, tileX, tileY)
			), random)
			: new ArrayDeque<>();
		if (!found.isEmpty())
		{
			path.addAll(found);
			failedSearches = 0;
			return;
		}

		if (context.isSearchRefused())
		{
			// Out of budget, not out of places to go: try again shortly.
			dwellCycles = DWELL_MIN;
			return;
		}

		// Nowhere to walk. Twice in a row on a floor too small to wander is a dead end
		// rather than bad luck, and only then is the way back taken: going back
		// whenever a search failed had pets bouncing up and down ladders.
		failedSearches++;
		if (failedSearches >= 2
			&& context.enclosedArea(tileX, tileY, plane) < PENNED_TILES
			&& (takeWayOut(context, false) || walkToWayOut(context)))
		{
			failedSearches = 0;
			return;
		}
		dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
	}

	/** Heads for a transport out of a region it shares with other pets. */
	private boolean seekNearbyTransport(RoamContext context)
	{
		TransportNetwork network = context.getTransports();
		if (network == null)
		{
			return false;
		}
		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		int tick = context.getTick();

		List<WorldTransport> near = new ArrayList<>();
		network.near(tileX, tileY, TRANSPORT_SEEK_RADIUS, near);
		Collections.shuffle(near, random);

		int tries = 0;
		for (WorldTransport transport : near)
		{
			if (transport.getFromPlane() != plane
				|| transportMemory.onCooldown(transport, tick)
				|| !context.getAbilities().canUse(transport)
				|| !arrivesSomewhereKnown(transport, context))
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
			// Asked after the rolls, which turn most choices down for less: it floods.
			if (!context.mayEnter(transport))
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
				wayOffDeadEnd = null;
				return true;
			}
			if (++tries >= SEEK_TRIES || !context.hasSearchBudget())
			{
				break;
			}
		}
		return false;
	}

	/**
	 * Rolls for a transport near the tile just entered and takes it if the roll
	 * wins: a quarter less likely for each tile away, and more or less likely by
	 * where it leads. On a stepping stone the next hop is taken without a roll.
	 *
	 * @return true if it used or set off for one, stopping this frame's movement
	 */
	private boolean considerTransport(RoamContext context)
	{
		TransportNetwork network = context.getTransports();
		if (network == null || network.size() == 0)
		{
			return false;
		}

		int tileX = fineX / TILE;
		int tileY = fineY / TILE;
		int tick = context.getTick();

		// Somewhere it cannot walk off but the network can: mid-river on a stone.
		boolean stranded = !context.getCollision().isKnownWalkable(tileX, tileY, plane)
			&& (network.isStone(tileX, tileY, plane) || !walkableBeside(tileX, tileY, context));

		WorldTransport wayBack = null;
		Set<Integer> cameFrom = null;
		int mayGoBack = -1;

		for (int dx = -TRANSPORT_REACH; dx <= TRANSPORT_REACH; dx++)
		{
			for (int dy = -TRANSPORT_REACH; dy <= TRANSPORT_REACH; dy++)
			{
				int x = tileX + dx;
				int y = tileY + dy;
				int distance = Math.abs(dx) + Math.abs(dy);
				if (distance > TRANSPORT_REACH || !network.hasOrigin(x, y))
				{
					continue;
				}

				for (WorldTransport transport : network.from(x, y))
				{
					if (transport.getFromPlane() != plane
						|| transportMemory.onCooldown(transport, tick)
						|| !arrivesSomewhereKnown(transport, context)
						|| !context.getAbilities().canUse(transport))
					{
						continue;
					}

					if (!stranded)
					{
						if (mayGoBack < 0)
						{
							mayGoBack = mayGoBack(tileX, tileY, context) ? 1 : 0;
						}
						if (transportMemory.restingFromTransports(tick) && mayGoBack == 0)
						{
							continue;
						}
						float chance = TRANSPORT_CHANCE
							* (TRANSPORT_REACH + 1 - distance) / (TRANSPORT_REACH + 1)
							* context.appeal(tileX, tileY, plane,
								transport.getToX(), transport.getToY(), transport.getToPlane());
						if (random.nextFloat() >= chance)
						{
							continue;
						}
						if (!context.mayEnter(transport))
						{
							continue;
						}
						if (mayGoBack == 0)
						{
							if (cameFrom == null)
							{
								cameFrom = context.spacesLeft(tileX, tileY, plane, transportMemory);
							}
							if (context.leadsInto(cameFrom, transport))
							{
								continue;
							}
						}
						float onward = context.wayOnAppeal(transport);
						if (onward < 1f && random.nextFloat() >= onward)
						{
							continue;
						}
					}

					// Only ever taken from its own starting tile: the reach is for noticing
					// a shortcut walked past, not for using one from across the clearing.
					if (tileX != transport.getFromX() || tileY != transport.getFromY())
					{
						if (stranded)
						{
							continue;
						}
						if (walkTo(transport.getFromX(), transport.getFromY(), context))
						{
							queuedTransport = transport;
							wayOffDeadEnd = null;
							return true;
						}
						continue;
					}

					// Mid-crossing, the hop back is a last resort, or pets turn round on the
					// middle stone and hop back where they began.
					if (stranded && reversesLastHop(transport, tick))
					{
						if (wayBack == null)
						{
							wayBack = transport;
						}
						continue;
					}

					take(transport, context);
					return true;
				}
			}
		}
		if (wayBack != null)
		{
			take(wayBack, context);
			return true;
		}
		return false;
	}

	/** Whether a pet here may go back the way it came: in a crowd, or penned in. */
	private boolean mayGoBack(int tileX, int tileY, RoamContext context)
	{
		return context.crowdedRegion(tileX, tileY, plane)
			|| context.enclosedArea(tileX, tileY, plane) < PENNED_TILES;
	}

	private boolean walkableBeside(int tileX, int tileY, RoamContext context)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if ((dx != 0 || dy != 0) && context.getCollision().isKnownWalkable(tileX + dx, tileY + dy, plane))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** True if this transport would take the pet back against the hop it has just made. */
	private boolean reversesLastHop(WorldTransport transport, int tick)
	{
		if (lastHopTick == Integer.MIN_VALUE || tick - lastHopTick > CROSSING_TICKS
			|| transport.getFromPlane() != transport.getToPlane())
		{
			return false;
		}
		int dx = transport.getToX() - transport.getFromX();
		int dy = transport.getToY() - transport.getFromY();
		return dx * lastHopX + dy * lastHopY < 0;
	}

	/**
	 * Now and then, from somewhere it knows no way out of, heads back the way it
	 * came in.
	 *
	 * @return true if this frame's decision was made (set off, or searched and paused)
	 */
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
			// Taken on arrival whatever its cooldown, as the way off a dead end is.
			wayOffDeadEnd = home;
		}
		else
		{
			// Searched and found no way: look again after a pause.
			dwellCycles = DWELL_MIN + random.nextInt(DWELL_SPREAD);
		}
		return true;
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
			leaveDeadEnd(back, context);
			return true;
		}
		return false;
	}

	/**
	 * Walks to the nearest way off a floor it can find nowhere to walk on, and
	 * takes it, even the way it came. That way is then on its long cooldown both
	 * ways, so the pet does not climb straight back.
	 */
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
			leaveDeadEnd(best, context);
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

	/** Takes the way off a dead end and puts it on the long cooldown, so it is not climbed straight back. */
	private void leaveDeadEnd(WorldTransport transport, RoamContext context)
	{
		wayOffDeadEnd = null;
		take(transport, context);
		transportMemory.used(transport, context.getTick());
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

		if (transport == wayOffDeadEnd)
		{
			leaveDeadEnd(transport, context);
			return true;
		}
		wayOffDeadEnd = null;
		if (transportMemory.onCooldown(transport, context.getTick())
			|| !context.getAbilities().canUse(transport))
		{
			return false;
		}
		take(transport, context);
		return true;
	}

	/**
	 * Uses a transport. An open door is simply walked through; anything else is
	 * turned to face first when it is a crossing on the same floor, so a pet
	 * arriving at a stile the other way round does not cross it backwards.
	 */
	private void take(WorldTransport transport, RoamContext context)
	{
		if (standsOpen(transport, context) && walkTo(transport.getToX(), transport.getToY(), context))
		{
			transportMemory.walkedThrough(transport, context.getTick());
			return;
		}

		int dx = transport.getToX() - transport.getFromX();
		int dy = transport.getToY() - transport.getFromY();
		int tick = context.getTick();
		boolean hopping = lastHopTick != Integer.MIN_VALUE && tick >= lastHopTick && tick - lastHopTick <= CROSSING_TICKS;
		boolean crossing = transport.getFromPlane() == transport.getToPlane() && WorldLayout.span(dx, dy) <= FACE_REACH;
		if (tier == PetTier.SCENE && (dx != 0 || dy != 0) && crossing && !hopping)
		{
			int heading = headingFor(dx, dy);
			if (Math.abs(((heading - orientation + 1024) & 2047) - 1024) > FACE_FIRST_OFF)
			{
				facingObstacle = transport;
				targetOrientation = heading;
				facingCycles = FACE_MOST_CYCLES;
				path.clear();
				stepping = false;
				walking = false;
				return;
			}
		}
		startObstacle(transport, context);
	}

	private void startObstacle(WorldTransport transport, RoamContext context)
	{
		int tick = context.getTick();
		if (transport.getFromPlane() == transport.getToPlane())
		{
			lastHopX = transport.getToX() - transport.getFromX();
			lastHopY = transport.getToY() - transport.getFromY();
			lastHopTick = tick;
		}
		transportMemory.used(transport, tick, cooldownFor(transport, context));
		path.clear();
		stepping = false;

		if (tier != PetTier.SCENE)
		{
			relocate(transport.destination());
			return;
		}

		activeTransport = transport;
		transitionFromX = fineX;
		transitionFromY = fineY;
		transitionToX = transport.getToX() * TILE + TILE / 2;
		transitionToY = transport.getToY() * TILE + TILE / 2;
		transitionElapsed = 0;
		transitionLeap = false;

		if (transport.isLocalCrossing())
		{
			// At walking pace or slower, never faster: a long log or bridge takes as long
			// as walking it would.
			int span = Math.max(1, WorldLayout.span(
				transport.getToX() - transport.getFromX(),
				transport.getToY() - transport.getFromY()));
			transitionTotal = Math.max(span, transport.getDuration()) * CYCLES_PER_TILE;
			targetOrientation = headingFor(transitionToX - transitionFromX, transitionToY - transitionFromY);
			orientation = targetOrientation;
			walking = true;
			// A hop over water or a gap is an arc, not a slide along the ground.
			int archetype = transport.getArchetype();
			transitionLeap = span >= 2 && (archetype == WorldTransport.ARCHETYPE_JUMP
				|| archetype == WorldTransport.ARCHETYPE_DITCH
				|| (archetype == WorldTransport.ARCHETYPE_NONE
					&& context.getTransports().isStone(transport.getFromX(), transport.getFromY(), plane)));
		}
		else
		{
			// Pets have no climbing animation: a short pause at the foot, then up or down.
			transitionTotal = Math.max(15, Math.min(45, Math.max(1, transport.getDuration()) * 15));
			walking = false;
		}
	}

	private void finishTransition()
	{
		WorldTransport done = activeTransport;
		activeTransport = null;
		transitionLeap = false;
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
		if (!context.spendSearch(FIND_PATH_COST))
		{
			return false;
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
