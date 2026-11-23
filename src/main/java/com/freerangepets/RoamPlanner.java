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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/**
 * Plans real-world {@link Itinerary} legs over {@link WorldMesh} and
 * {@link TransportNetwork} for off-screen pets, snaps tiles onto valid walkable
 * ground, avoids one-way traps, and scatters pets cleanly across Gielinor so
 * they never clump together.
 */
@Singleton
class RoamPlanner
{
	private static final int LEG_MIN = 16;
	private static final int LEG_SPREAD = 44;
	private static final int ATTEMPTS = 8;
	private static final int TRANSPORT_SEARCH = 48;
	private static final int FAR_SEARCH_BUDGET = 1000;
	private static final int IDLE_MIN = 8;
	private static final int IDLE_SPREAD = 16;
	private static final int UNKNOWN_REACH = 4;
	private static final int SHUT_IN = 8;

	static final float HOMEWARD_CHANCE = 0.25f;
	static final float DEAD_END_APPEAL = 0.6f;
	private static final float TRANSPORT_CHANCE = 0.45f;

	/**
	 * Diverse, walkable overworld starting points across Gielinor used when
	 * freeing pets from the POH Menagerie or scattering clumped pets so every pet
	 * starts in its own region rather than stacked on the POH portal.
	 */
	private static final WorldPoint[] SCATTER_HUBS = {
		new WorldPoint(3222, 3218, 0), // Lumbridge
		new WorldPoint(3212, 3428, 0), // Varrock Square
		new WorldPoint(3164, 3485, 0), // Grand Exchange
		new WorldPoint(2964, 3379, 0), // Falador
		new WorldPoint(3093, 3244, 0), // Draynor Village
		new WorldPoint(3087, 3495, 0), // Edgeville
		new WorldPoint(3081, 3421, 0), // Barbarian Village
		new WorldPoint(3293, 3180, 0), // Al Kharid
		new WorldPoint(3023, 3208, 0), // Port Sarim
		new WorldPoint(2954, 3224, 0), // Rimmington
		new WorldPoint(2894, 3456, 0), // Taverley
		new WorldPoint(2898, 3544, 0), // Burthorpe
		new WorldPoint(2808, 3436, 0), // Catherby
		new WorldPoint(2725, 3485, 0), // Seers' Village
		new WorldPoint(2662, 3305, 0), // East Ardougne
		new WorldPoint(2606, 3093, 0), // Yanille
		new WorldPoint(2545, 3113, 0), // Tree Gnome Village north
		new WorldPoint(2446, 3425, 0), // Tree Gnome Stronghold
		new WorldPoint(2441, 3090, 0), // Castle Wars
		new WorldPoint(2660, 3161, 0), // Port Khazard
		new WorldPoint(2660, 3655, 0), // Rellekka
		new WorldPoint(2760, 3178, 0), // Brimhaven
		new WorldPoint(2920, 3150, 0), // Musa Point
		new WorldPoint(2852, 2955, 0), // Shilo Village
		new WorldPoint(3493, 3488, 0), // Canifis
		new WorldPoint(3687, 3497, 0), // Port Phasmatys
		new WorldPoint(3495, 3220, 0), // Burgh de Rott
		new WorldPoint(3358, 2970, 0), // Pollnivneach
		new WorldPoint(3428, 2918, 0), // Nardah
		new WorldPoint(3314, 2796, 0), // Sophanem
		new WorldPoint(3142, 3313, 0), // Draynor Manor grounds
		new WorldPoint(2933, 3289, 0), // Crafting Guild
		new WorldPoint(2557, 2862, 0), // Corsair Cove
		new WorldPoint(2550, 2916, 0), // Feldip Hills
		new WorldPoint(1748, 3566, 0), // Hosidius
		new WorldPoint(1638, 3673, 0), // Kourend Castle
		new WorldPoint(1678, 3748, 0), // Arceuus
		new WorldPoint(1504, 3816, 0), // Lovakengj
		new WorldPoint(1514, 3574, 0), // Shayzien
		new WorldPoint(1802, 3756, 0), // Port Piscarilius
		new WorldPoint(1248, 3726, 0), // Farming Guild
		new WorldPoint(1312, 3684, 0), // Kebos Lowlands
		new WorldPoint(1681, 3106, 0), // Civitas illa Fortis
		new WorldPoint(1420, 2962, 0), // Aldarin
		new WorldPoint(1558, 3045, 0), // Hunter Guild
		new WorldPoint(3364, 3268, 0), // Emir's Arena
		new WorldPoint(2611, 3392, 0), // Fishing Guild
		new WorldPoint(3306, 3490, 0), // Lumber Yard
		new WorldPoint(3109, 3161, 0)  // Wizards' Tower bridge
	};

	@Inject
	private WorldMesh mesh;

	@Inject
	private LiveCollisionMap collision;

	@Inject
	private TransportNetwork transports;

	@Inject
	private PlayerAbilities abilities;

	private WaysBack waysBack;
	private int componentsRevision = -1;
	private int waysHomeBuild = -1;

	private final Map<Integer, List<WorldTransport>> exitsFrom = new HashMap<>();
	private final Map<Integer, List<WorldTransport>> waysBackFrom = new HashMap<>();
	private final Map<Integer, List<WorldTransport>> waysHome = new HashMap<>();

	/**
	 * Picks a walkable starting tile in an unoccupied region across Gielinor so
	 * newly freed or scattered pets spread out rather than clumping together.
	 */
	WorldPoint pickScatterTile(WorldPoint fallback, PetCensus census, Random random)
	{
		List<WorldPoint> empty = new ArrayList<>();
		List<WorldPoint> leastCrowded = new ArrayList<>();
		int minCount = Integer.MAX_VALUE;

		for (WorldPoint hub : SCATTER_HUBS)
		{
			WorldPoint snapped = snapToMesh(hub);
			if (!isSafe(snapped.getX(), snapped.getY(), snapped.getPlane()))
			{
				continue;
			}
			int count = census == null ? 0 : census.at(snapped.getX(), snapped.getY(), snapped.getPlane());
			if (count == 0)
			{
				empty.add(snapped);
			}
			if (count < minCount)
			{
				minCount = count;
				leastCrowded.clear();
				leastCrowded.add(snapped);
			}
			else if (count == minCount)
			{
				leastCrowded.add(snapped);
			}
		}

		List<WorldPoint> pool = !empty.isEmpty() ? empty : leastCrowded;
		WorldPoint chosen = pool.isEmpty() ? snapToMesh(fallback) : pool.get(random.nextInt(pool.size()));

		// Offset a few walkable tiles in the same region and connected component so pets never land on the exact hub tile.
		int chosenRegion = chosen.getRegionID();
		for (int tries = 0; tries < 10; tries++)
		{
			int nx = chosen.getX() + random.nextInt(19) - 9;
			int ny = chosen.getY() + random.nextInt(19) - 9;
			int nextRegion = ((nx >> 6) << 8) | (ny >> 6);
			if (nextRegion == chosenRegion
				&& isSafe(nx, ny, chosen.getPlane())
				&& mesh.sameComponent(chosen.getX(), chosen.getY(), nx, ny, chosen.getPlane()))
			{
				chosen = new WorldPoint(nx, ny, chosen.getPlane());
				break;
			}
		}

		if (census != null)
		{
			census.add(chosen.getX(), chosen.getY(), chosen.getPlane());
		}
		return chosen;
	}

	Itinerary plan(WorldPoint from, int tick, Random random, TransportMemory memory, RoamContext context)
	{
		if (!isSafe(from.getX(), from.getY(), from.getPlane()))
		{
			WorldPoint off = transports.isStone(from.getX(), from.getY(), from.getPlane()) ? null : safeNeighbour(from);
			if (off != null)
			{
				List<int[]> step = single(from);
				step.add(new int[]{off.getX(), off.getY()});
				return Itinerary.of(step, from.getPlane(), tick, 0);
			}
			WorldTransport onward = fromHere(from, tick, memory, random);
			if (onward != null)
			{
				noteTaken(onward, tick, memory);
				return Itinerary.thenTransport(single(from), from.getPlane(), tick, onward);
			}
		}

		boolean pressed = context != null && context.crowdedRegion(from.getX(), from.getY(), from.getPlane());
		Reach reach = new Reach(from, context, FAR_SEARCH_BUDGET);

		if (random.nextFloat() < HOMEWARD_CHANCE)
		{
			WorldTransport home = wayHome(from.getX(), from.getY(), from.getPlane(), tick, memory, random);
			List<int[]> path = home == null ? null : walk(from, home.getFromX(), home.getFromY(), reach);
			if (path != null)
			{
				noteTaken(home, tick + path.size() - 1, memory);
				return Itinerary.thenTransport(path, from.getPlane(), tick, home);
			}
		}

		boolean penned = context != null
			&& context.enclosedArea(from.getX(), from.getY(), from.getPlane()) < RoamingPet.PENNED_TILES;
		if (pressed || penned
			|| (random.nextFloat() < TRANSPORT_CHANCE && (memory == null || !memory.restingFromTransports(tick))))
		{
			Itinerary toTransport = toNearbyTransport(from, tick, random, memory, reach, penned || pressed);
			if (toTransport != null)
			{
				return toTransport;
			}
		}

		return wander(from, tick, random, reach, pressed);
	}

	static Itinerary idle(WorldPoint at, int tick, Random random, int failures)
	{
		int ticks = (IDLE_MIN + random.nextInt(IDLE_SPREAD)) << Math.min(Math.max(0, failures), 3);
		return Itinerary.of(single(at), at.getPlane(), tick, ticks);
	}

	boolean mayEnter(WorldTransport transport, int tick)
	{
		if (mesh == null || transports == null || abilities == null)
		{
			return true;
		}
		waysBack(tick);
		List<Integer> from = waysBack.setOff(transport);
		if (from.isEmpty())
		{
			return true;
		}
		List<Integer> to = waysBack.landing(transport);
		if (to.isEmpty())
		{
			return true;
		}
		return waysBack.comesBack(from, to);
	}

	float wayOnAppeal(WorldTransport transport, int tick)
	{
		if (mesh == null || transports == null || abilities == null)
		{
			return 1f;
		}
		waysBack(tick);
		return waysBack.isDeadEnd(waysBack.setOff(transport), waysBack.landing(transport)) ? DEAD_END_APPEAL : 1f;
	}

	void forgetWaysBack()
	{
		if (waysBack != null)
		{
			waysBack.forget();
		}
	}

	WorldTransport wayHome(int x, int y, int plane, int tick, TransportMemory memory, Random random)
	{
		if (mesh == null || transports == null || abilities == null)
		{
			return null;
		}
		indexComponents();
		WaysBack back = waysBack(tick);
		if (waysHomeBuild != back.builds())
		{
			waysHome.clear();
			waysHomeBuild = back.builds();
		}
		int component = componentNear(x, y, plane);
		if (component == 0)
		{
			return null;
		}
		List<WorldTransport> ways = waysHome.computeIfAbsent(component, this::findWaysHome);
		if (ways.isEmpty())
		{
			return null;
		}
		WorldTransport way = ways.get(random.nextInt(ways.size()));
		return memory != null && memory.onCooldown(way, tick) ? null : way;
	}

	Set<Integer> spacesLeft(int x, int y, int plane, int tick, TransportMemory memory)
	{
		if (memory == null)
		{
			return Collections.emptySet();
		}
		Set<Integer> left = new HashSet<>();
		List<Integer> here = mesh.spacesAt(x, y, plane);
		for (int[] origin : memory.recentOrigins(tick))
		{
			List<Integer> spaces = mesh.spacesAt(origin[0], origin[1], origin[2]);
			if (spaces.size() == 1 && !here.contains(spaces.get(0)))
			{
				left.add(spaces.get(0));
			}
		}
		return left;
	}

	boolean leadsInto(Set<Integer> spaces, WorldTransport transport)
	{
		if (spaces.isEmpty())
		{
			return false;
		}
		for (int space : mesh.spacesAt(transport.getToX(), transport.getToY(), transport.getToPlane()))
		{
			if (spaces.contains(space))
			{
				return true;
			}
		}
		return false;
	}

	WorldPoint snapToMesh(WorldPoint at)
	{
		if (at == null)
		{
			return new WorldPoint(WaysBack.MAINLAND_X, WaysBack.MAINLAND_Y, 0);
		}
		if (isSafe(at.getX(), at.getY(), at.getPlane()))
		{
			return at;
		}
		if (mesh.isInlandWater(at.getX(), at.getY(), at.getPlane()))
		{
			WorldPoint shore = nearestSafe(at, false);
			return shore != null ? shore : at;
		}
		WorldPoint near = nearestSafe(at, true);
		return near != null ? near : at;
	}

	boolean isSafe(int x, int y, int plane)
	{
		if (collision != null && collision.isKnownWalkable(x, y, plane))
		{
			return !mesh.isWater(x, y, plane);
		}
		return mesh != null && mesh.isLandWalkable(x, y, plane) && !mesh.isIsolated(x, y, plane);
	}

	private Itinerary wander(WorldPoint from, int tick, Random random, Reach reach, boolean pressed)
	{
		PetCensus census = reach.context == null ? null : reach.context.getCensus();
		List<int[]> fallbackLeg = null;

		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			int distance = LEG_MIN + random.nextInt(pressed ? LEG_SPREAD * 2 : LEG_SPREAD);
			double angle = random.nextDouble() * Math.PI * 2;
			int toX = from.getX() + (int) Math.round(Math.cos(angle) * distance);
			int toY = from.getY() + (int) Math.round(Math.sin(angle) * distance);

			if (!isSafe(toX, toY, from.getPlane())
				|| !mesh.sameComponent(from.getX(), from.getY(), toX, toY, from.getPlane()))
			{
				continue;
			}
			List<int[]> leg = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
			if (leg == null)
			{
				continue;
			}
			// Prefer walking into an empty region rather than staying in or entering a shared one.
			if (census != null)
			{
				boolean sameRegion = (toX >> 6) == (from.getX() >> 6) && (toY >> 6) == (from.getY() >> 6);
				int destCount = census.at(toX, toY, from.getPlane());
				if ((!sameRegion && destCount == 0) || (!pressed && sameRegion))
				{
					return Itinerary.of(leg, from.getPlane(), tick, 0);
				}
				if (fallbackLeg == null)
				{
					fallbackLeg = leg;
				}
				continue;
			}
			return Itinerary.of(leg, from.getPlane(), tick, 0);
		}
		if (fallbackLeg != null)
		{
			return Itinerary.of(fallbackLeg, from.getPlane(), tick, 0);
		}

		TileMap tiles = reach.tiles();
		if (tiles.size() < 2)
		{
			return null;
		}
		int reached = tiles.size();
		int half = reached / 2;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++)
		{
			long tile = tiles.keyAt(half + random.nextInt(reached - half));
			int x = PetPathfinder.unpackX(tile);
			int y = PetPathfinder.unpackY(tile);
			if ((x == from.getX() && y == from.getY()) || !isSafe(x, y, from.getPlane())
				|| !mesh.sameComponent(from.getX(), from.getY(), x, y, from.getPlane()))
			{
				continue;
			}
			List<int[]> path = reach.pathTo(x, y);
			if (path != null)
			{
				return Itinerary.of(path, from.getPlane(), tick, 0);
			}
		}
		return null;
	}

	private Itinerary toNearbyTransport(WorldPoint from, int tick, Random random, TransportMemory memory,
		Reach reach, boolean mayGoBack)
	{
		Set<Integer> cameFrom = mayGoBack ? Collections.emptySet()
			: spacesLeft(from.getX(), from.getY(), from.getPlane(), tick, memory);
		List<WorldTransport> near = new ArrayList<>();
		transports.near(from.getX(), from.getY(), TRANSPORT_SEARCH, near);
		Collections.shuffle(near, random);

		int walksTried = 0;
		WorldTransport crowdedChoice = null;
		for (WorldTransport transport : near)
		{
			if (transport.getFromPlane() != from.getPlane()
				|| (memory != null && memory.onCooldown(transport, tick))
				|| !mesh.sameComponent(from.getX(), from.getY(), transport.getFromX(), transport.getFromY(),
					from.getPlane())
				|| !abilities.canUse(transport)
				|| !landsSomewhereUseful(transport)
				|| leadsInto(cameFrom, transport)
				|| !mayEnter(transport, tick))
			{
				continue;
			}
			float onward = wayOnAppeal(transport, tick);
			if (onward < 1f && random.nextFloat() >= onward)
			{
				continue;
			}
			float appeal = reach.context == null ? 1f
				: reach.context.appeal(from.getX(), from.getY(), from.getPlane(),
					transport.getToX(), transport.getToY(), transport.getToPlane());
			if (appeal < 1f && random.nextFloat() >= appeal)
			{
				if (crowdedChoice == null)
				{
					crowdedChoice = transport;
				}
				continue;
			}
			List<int[]> path = walk(from, transport.getFromX(), transport.getFromY(), reach);
			if (path != null)
			{
				noteTaken(transport, tick + path.size() - 1, memory);
				return Itinerary.thenTransport(path, from.getPlane(), tick, transport);
			}
			if (++walksTried >= ATTEMPTS * 4)
			{
				break;
			}
		}
		if (crowdedChoice != null)
		{
			List<int[]> path = walk(from, crowdedChoice.getFromX(), crowdedChoice.getFromY(), reach);
			if (path != null)
			{
				noteTaken(crowdedChoice, tick + path.size() - 1, memory);
				return Itinerary.thenTransport(path, from.getPlane(), tick, crowdedChoice);
			}
		}
		return null;
	}

	private WorldTransport fromHere(WorldPoint at, int tick, TransportMemory memory, Random random)
	{
		WorldTransport back = null;
		List<WorldTransport> onward = new ArrayList<>();
		for (WorldTransport transport : transports.from(at.getX(), at.getY()))
		{
			if (transport.getFromPlane() != at.getPlane() || !abilities.canUse(transport)
				|| !landsSomewhereUseful(transport))
			{
				continue;
			}
			if (memory == null || !memory.onCooldown(transport, tick))
			{
				onward.add(transport);
			}
			else if (back == null)
			{
				back = transport;
			}
		}
		return onward.isEmpty() ? back : onward.get(random.nextInt(onward.size()));
	}

	private WorldPoint safeNeighbour(WorldPoint at)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if ((dx != 0 || dy != 0) && isSafe(at.getX() + dx, at.getY() + dy, at.getPlane()))
				{
					return new WorldPoint(at.getX() + dx, at.getY() + dy, at.getPlane());
				}
			}
		}
		return null;
	}

	private boolean landsSomewhereUseful(WorldTransport transport)
	{
		return transports.leadsToGround(
			transport.getToX(), transport.getToY(), transport.getToPlane(),
			transport.getFromX(), transport.getFromY(), transport.getFromPlane(),
			TransportNetwork.CHAIN_HOPS, abilities::canUse, this::isSafe
		);
	}

	private List<int[]> walk(WorldPoint from, int toX, int toY, Reach reach)
	{
		List<int[]> line = straightLine(from.getX(), from.getY(), toX, toY, from.getPlane());
		return line != null ? line : reach.pathTo(toX, toY);
	}

	private List<int[]> straightLine(int fromX, int fromY, int toX, int toY, int plane)
	{
		List<int[]> path = new ArrayList<>();
		int x = fromX;
		int y = fromY;
		path.add(new int[]{x, y});

		while (x != toX || y != toY)
		{
			int stepX = Integer.signum(toX - x);
			int stepY = Integer.signum(toY - y);
			if (!collision.canStep(x, y, plane, stepX, stepY))
			{
				return null;
			}
			x += stepX;
			y += stepY;
			if (!mesh.isLandWalkable(x, y, plane) && !collision.isKnownWalkable(x, y, plane))
			{
				return null;
			}
			path.add(new int[]{x, y});
			if (path.size() > 512)
			{
				return null;
			}
		}
		return path;
	}

	private WorldPoint nearestSafe(WorldPoint at, boolean sameSpace)
	{
		int origin = sameSpace ? mesh.componentAt(at.getX(), at.getY(), at.getPlane()) : 0;
		if (sameSpace && origin == 0)
		{
			origin = beside(at);
		}
		int reach = sameSpace && origin == 0 ? UNKNOWN_REACH : 32;
		for (int radius = 1; radius <= reach; radius++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dy = -radius; dy <= radius; dy++)
				{
					if (Math.abs(dx) != radius && Math.abs(dy) != radius)
					{
						continue;
					}
					int x = at.getX() + dx;
					int y = at.getY() + dy;
					if (origin != 0)
					{
						int here = mesh.componentAt(x, y, at.getPlane());
						if (here != 0 && here != origin)
						{
							continue;
						}
					}
					if (isSafe(x, y, at.getPlane()))
					{
						return new WorldPoint(x, y, at.getPlane());
					}
				}
			}
		}
		return null;
	}

	private int beside(WorldPoint at)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int space = mesh.componentAt(at.getX() + dx, at.getY() + dy, at.getPlane());
				if (space != 0)
				{
					return space;
				}
			}
		}
		return 0;
	}

	private WaysBack waysBack(int tick)
	{
		if (waysBack == null)
		{
			waysBack = new WaysBack(mesh, transports, abilities::canUse);
		}
		waysBack.refresh(tick);
		return waysBack;
	}

	private void indexComponents()
	{
		if (componentsRevision == transports.revision())
		{
			return;
		}
		componentsRevision = transports.revision();
		exitsFrom.clear();
		waysBackFrom.clear();
		waysHome.clear();
		for (WorldTransport t : transports.all())
		{
			int from = componentNear(t.getFromX(), t.getFromY(), t.getFromPlane());
			int to = componentNear(t.getToX(), t.getToY(), t.getToPlane());
			boolean elsewhere = from == 0 || to == 0 || from != to || t.getFromPlane() != t.getToPlane();
			if (!elsewhere)
			{
				continue;
			}
			if (from != 0)
			{
				exitsFrom.computeIfAbsent(from, k -> new ArrayList<>()).add(t);
			}
			if (to != 0)
			{
				waysBackFrom.computeIfAbsent(to, k -> new ArrayList<>()).add(t.backThrough());
			}
		}
	}

	private List<WorldTransport> findWaysHome(int start)
	{
		if (waysBack != null && waysBack.reachesMainland(start))
		{
			return Collections.emptyList();
		}
		Set<Integer> reached = new HashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		reached.add(start);
		queue.add(start);
		while (!queue.isEmpty())
		{
			List<WorldTransport> exits = exitsFrom.get(queue.poll());
			if (exits == null)
			{
				continue;
			}
			for (WorldTransport exit : exits)
			{
				if (!abilities.canUse(exit) || !landsSomewhereUseful(exit))
				{
					continue;
				}
				int to = componentNear(exit.getToX(), exit.getToY(), exit.getToPlane());
				if (to == 0)
				{
					return Collections.emptyList();
				}
				if (reached.add(to))
				{
					if (reached.size() > SHUT_IN)
					{
						return Collections.emptyList();
					}
					queue.add(to);
				}
			}
		}
		List<WorldTransport> ways = new ArrayList<>();
		for (WorldTransport way : waysBackFrom.getOrDefault(start, Collections.emptyList()))
		{
			int to = componentNear(way.getToX(), way.getToY(), way.getToPlane());
			if (!reached.contains(to) && !mesh.isCutOff(way.getToX(), way.getToY(), way.getToPlane()))
			{
				ways.add(way);
			}
		}
		return ways;
	}

	private int componentNear(int x, int y, int plane)
	{
		int here = mesh.componentAt(x, y, plane);
		for (int dx = -1; dx <= 1 && here == 0; dx++)
		{
			for (int dy = -1; dy <= 1 && here == 0; dy++)
			{
				here = mesh.componentAt(x + dx, y + dy, plane);
			}
		}
		return here;
	}

	private static void noteTaken(WorldTransport transport, int whenUsed, TransportMemory memory)
	{
		if (memory != null)
		{
			memory.used(transport, whenUsed);
		}
	}

	private static List<int[]> single(WorldPoint at)
	{
		List<int[]> path = new ArrayList<>(1);
		path.add(new int[]{at.getX(), at.getY()});
		return path;
	}

	@RequiredArgsConstructor
	private static final class Reach
	{
		private final WorldPoint from;
		private final RoamContext context;
		private final int budget;
		private TileMap cameFrom;

		TileMap tiles()
		{
			if (cameFrom == null)
			{
				cameFrom = context == null || context.getPathfinder() == null
					? new TileMap(4)
					: context.getPathfinder().flood(from.getX(), from.getY(), from.getPlane(), budget);
			}
			return cameFrom;
		}

		List<int[]> pathTo(int x, int y)
		{
			Deque<int[]> found = PetPathfinder.pathFrom(tiles(), from.getX(), from.getY(), x, y);
			if (found == null)
			{
				return null;
			}
			List<int[]> path = new ArrayList<>(found.size() + 1);
			path.add(new int[]{from.getX(), from.getY()});
			path.addAll(found);
			return path;
		}
	}
}
