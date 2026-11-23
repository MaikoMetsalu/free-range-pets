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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;

/**
 * Bundled read-only world walkability mesh covering Gielinor's surface, upper
 * floors, caves, and dungeons.
 *
 * <p>Provides per-tile edges, wall corners, water/ocean flags, isolated pocket
 * detection, and connected-component IDs so pets can walk accurately across the
 * entire world without ever entering walls, water, or sealed pockets.
 *
 * <p>World mesh data format and dataset derived from Golems Don't Die by
 * Varzeki (BSD 2-Clause).
 */
@Slf4j
@Singleton
class WorldMesh
{
	private static final String RESOURCE = "world-mesh.gz";

	private static final int REGION_SIZE = Constants.REGION_SIZE;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int TILES_PER_PLANE = REGION_SIZE * REGION_SIZE;
	private static final int COLLISION_BYTES = TILES_PER_PLANE;
	private static final int DERIVED_BYTES = TILES_PER_PLANE / 8;
	private static final int COMPONENT_BYTES = TILES_PER_PLANE * 2;
	private static final int MAGIC = -0x60137;
	private static final int VERSION = 4;

	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;
	private static final int FLAG_STANDABLE = 2;
	private static final int FLAG_GROUND = 3;
	private static final int FLAG_CORNER_NE = 4;
	private static final int FLAG_CORNER_NW = 5;

	private static final int FAST_REGIONS = 1 << 16;

	@AllArgsConstructor
	private static final class Region
	{
		final byte[] collision;
		final byte[] ocean;
		final byte[] isolated;
		final byte[] components;
		final int[] palette;
		final char[] wide;
		final byte[] land;
		final byte[] water;
	}

	private final Map<Long, Region> regions = new HashMap<>();
	private final Region[] byRegion = new Region[FAST_REGIONS << 2];
	private final Map<Long, byte[]> packed = new HashMap<>();
	private final boolean[] packedAt = new boolean[FAST_REGIONS << 2];

	private final boolean[] landComponents = new boolean[1 << 16];
	private int admittedFloors;

	private final Map<Integer, List<Integer>> transportLeads = new HashMap<>();
	private final Set<Integer> transportTouched = new HashSet<>();
	private final Map<Integer, List<Integer>> leadsTo = new HashMap<>();
	private final Set<Integer> touched = new HashSet<>();
	private final Map<Integer, List<Integer>> walkedTo = new HashMap<>();
	private final Map<Integer, Set<Long>> joinsByRegion = new HashMap<>();
	private final Map<Long, Integer> joinCount = new HashMap<>();
	private boolean[] fromHome;
	private int linkRevision;

	private boolean loaded;

	void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;

		try (InputStream raw = WorldMesh.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("Bundled world mesh missing from the jar");
				return;
			}
			try (GZIPInputStream gz = new GZIPInputStream(raw);
				DataInputStream data = new DataInputStream(gz))
			{
				int first = data.readInt();
				if (first != MAGIC)
				{
					log.warn("World mesh format magic mismatch");
					return;
				}
				int version = data.readInt();
				if (version != VERSION)
				{
					log.warn("World mesh version {} unsupported (expected {})", version, VERSION);
					return;
				}

				int entries = data.readInt();
				for (int i = 0; i < entries; i++)
				{
					int regionId = data.readInt();
					int plane = data.readByte();
					put(regionId, plane, readRecord(data));
				}

				int packedEntries = data.readInt();
				for (int i = 0; i < packedEntries; i++)
				{
					int regionId = data.readInt();
					int plane = data.readByte();
					byte[] blob = new byte[data.readInt()];
					data.readFully(blob);
					packed.put(key(regionId, plane), blob);
					if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
					{
						packedAt[regionId << 2 | (plane & 0xF)] = true;
					}
				}
				log.debug("Loaded world mesh: {} region-planes ({} more packed)", entries, packedEntries);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Bundled world mesh unreadable", e);
			regions.clear();
			Arrays.fill(byRegion, null);
			packed.clear();
			Arrays.fill(packedAt, false);
		}
	}

	boolean holdsAnySquare()
	{
		return !regions.isEmpty() || !packed.isEmpty();
	}

	boolean north(int x, int y, int plane)
	{
		return flag(x, y, plane, FLAG_NORTH);
	}

	boolean east(int x, int y, int plane)
	{
		return flag(x, y, plane, FLAG_EAST);
	}

	boolean isOcean(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.ocean, x, y);
	}

	boolean isWater(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.water, x, y);
	}

	boolean isInlandWater(int x, int y, int plane)
	{
		return isWater(x, y, plane) && !isOcean(x, y, plane);
	}

	boolean isIsolated(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.isolated, x, y);
	}

	int componentAt(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region == null ? 0 : componentOf(region, x, y);
	}

	boolean sameComponent(int fromX, int fromY, int toX, int toY, int plane)
	{
		int from = componentAt(fromX, fromY, plane);
		int to = componentAt(toX, toY, plane);
		return from == 0 || to == 0 || from == to;
	}

	boolean cornerBlocks(int x, int y, int plane, int dx, int dy)
	{
		if (dy == 1)
		{
			return flag(x, y, plane, dx == 1 ? FLAG_CORNER_NE : FLAG_CORNER_NW);
		}
		return flag(x + dx, y + dy, plane, dx == 1 ? FLAG_CORNER_NW : FLAG_CORNER_NE);
	}

	boolean isWalkable(int x, int y, int plane)
	{
		return flag(x, y, plane, FLAG_STANDABLE)
			&& flag(x, y, plane, FLAG_GROUND)
			&& (north(x, y, plane) || east(x, y, plane) || north(x, y - 1, plane) || east(x - 1, y, plane));
	}

	boolean isLandWalkable(int x, int y, int plane)
	{
		if (isInlandWater(x, y, plane))
		{
			return false;
		}
		Region region = region(regionIdOf(x, y), plane);
		if (region == null)
		{
			return false;
		}
		if (!bit(region.land, x, y))
		{
			if (admittedFloors == 0)
			{
				return false;
			}
			int component = componentOf(region, x, y);
			if (!landComponents[component])
			{
				return false;
			}
		}
		return isWalkable(x, y, plane);
	}

	int linkRevision()
	{
		return linkRevision;
	}

	Map<Integer, List<Integer>> walkedTo()
	{
		return Collections.unmodifiableMap(walkedTo);
	}

	boolean isSealedPocket(int x, int y, int plane)
	{
		int component = componentAt(x, y, plane);
		return component != 0 && isIsolated(x, y, plane) && !touched.contains(component);
	}

	boolean isCutOff(int x, int y, int plane)
	{
		int component = componentAt(x, y, plane);
		if (component == 0 || fromHome == null)
		{
			return false;
		}
		return !fromHome[component];
	}

	void linkSpaces(int homeX, int homeY, int homePlane)
	{
		int home = componentAt(homeX, homeY, homePlane);
		if (home == 0)
		{
			return;
		}
		boolean[] reached = new boolean[1 << 16];
		Deque<Integer> queue = new ArrayDeque<>();
		reached[home] = true;
		queue.add(home);

		while (!queue.isEmpty())
		{
			int at = queue.poll();
			List<Integer> next = leadsTo.get(at);
			if (next == null)
			{
				continue;
			}
			for (int to : next)
			{
				if (!reached[to])
				{
					reached[to] = true;
					queue.add(to);
				}
			}
		}
		fromHome = reached;
	}

	void admitTransportEnds(List<WorldTransport> transports)
	{
		Arrays.fill(landComponents, false);
		transportLeads.clear();
		transportTouched.clear();

		Map<Long, List<WorldTransport>> starting = new HashMap<>();
		for (WorldTransport t : transports)
		{
			starting.computeIfAbsent(tileKey(t.getFromX(), t.getFromY(), t.getFromPlane()),
				k -> new ArrayList<>()).add(t);
		}
		for (WorldTransport t : transports)
		{
			for (int from : spacesAt(t.getFromX(), t.getFromY(), t.getFromPlane()))
			{
				transportTouched.add(from);
				for (int to : spacesOnward(t.getToX(), t.getToY(), t.getToPlane(),
					t.getFromX(), t.getFromY(), t.getFromPlane(), TransportNetwork.CHAIN_HOPS, starting))
				{
					transportTouched.add(to);
					if (from != to)
					{
						transportLeads.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
					}
				}
			}
		}
		admittedFloors = 0;
		for (WorldTransport t : transports)
		{
			admit(t.getFromX(), t.getFromY(), t.getFromPlane());
			admit(t.getToX(), t.getToY(), t.getToPlane());
		}
		composeLinks();
	}

	int joinWhereWalked(LiveCollisionMap memory, int[] regionIds)
	{
		int changed = 0;
		for (int regionId : regionIds)
		{
			int baseX = (regionId >> 8) << 6;
			int baseY = (regionId & 0xFF) << 6;
			Set<Long> now = new HashSet<>();
			for (int plane = 0; plane < 4; plane++)
			{
				if (!memory.holds(regionId, plane))
				{
					continue;
				}
				for (int x = baseX; x < baseX + 64; x++)
				{
					for (int y = baseY; y < baseY + 64; y++)
					{
						int here = componentAt(x, y, plane);
						if (here == 0)
						{
							continue;
						}
						int north = memory.north(x, y, plane) ? componentAt(x, y + 1, plane) : 0;
						if (north != 0 && north != here)
						{
							now.add(pair(here, north));
						}
						int east = memory.east(x, y, plane) ? componentAt(x + 1, y, plane) : 0;
						if (east != 0 && east != here)
						{
							now.add(pair(here, east));
						}
					}
				}
			}

			Set<Long> before = joinsByRegion.getOrDefault(regionId, Collections.emptySet());
			for (long joined : now)
			{
				if (!before.contains(joined) && joinCount.merge(joined, 1, Integer::sum) == 1)
				{
					changed++;
				}
			}
			for (long joined : before)
			{
				if (!now.contains(joined) && joinCount.merge(joined, -1, Integer::sum) == 0)
				{
					joinCount.remove(joined);
					changed++;
				}
			}
			if (now.isEmpty())
			{
				joinsByRegion.remove(regionId);
			}
			else
			{
				joinsByRegion.put(regionId, now);
			}
		}
		if (changed > 0)
		{
			composeLinks();
		}
		return changed;
	}

	List<Integer> spacesAt(int x, int y, int plane)
	{
		int own = componentAt(x, y, plane);
		if (own != 0)
		{
			return Collections.singletonList(own);
		}
		List<Integer> around = new ArrayList<>(4);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int space = componentAt(x + dx, y + dy, plane);
				if (space != 0 && !around.contains(space))
				{
					around.add(space);
				}
			}
		}
		return around;
	}

	List<Integer> spacesOnward(int x, int y, int plane, int cameX, int cameY, int camePlane,
		int hops, Map<Long, List<WorldTransport>> starting)
	{
		List<Integer> here = spacesAt(x, y, plane);
		if (!here.isEmpty() || hops <= 0)
		{
			return here;
		}
		List<Integer> found = new ArrayList<>(2);
		for (WorldTransport onward : starting.getOrDefault(tileKey(x, y, plane), Collections.emptyList()))
		{
			if (onward.getToX() == cameX && onward.getToY() == cameY && onward.getToPlane() == camePlane)
			{
				continue;
			}
			for (int space : spacesOnward(onward.getToX(), onward.getToY(), onward.getToPlane(),
				x, y, plane, hops - 1, starting))
			{
				if (!found.contains(space))
				{
					found.add(space);
				}
			}
		}
		return found;
	}

	static long tileKey(int x, int y, int plane)
	{
		return ((long) plane << 32) | ((long) x << 16) | (y & 0xFFFFL);
	}

	private void composeLinks()
	{
		walkedTo.clear();
		for (long joined : joinCount.keySet())
		{
			int one = (int) (joined >> 16);
			int other = (int) (joined & 0xFFFF);
			walkedTo.computeIfAbsent(one, k -> new ArrayList<>()).add(other);
			walkedTo.computeIfAbsent(other, k -> new ArrayList<>()).add(one);
		}
		leadsTo.clear();
		for (Map.Entry<Integer, List<Integer>> entry : transportLeads.entrySet())
		{
			leadsTo.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		touched.clear();
		touched.addAll(transportTouched);
		for (Map.Entry<Integer, List<Integer>> walked : walkedTo.entrySet())
		{
			touched.add(walked.getKey());
			List<Integer> list = leadsTo.computeIfAbsent(walked.getKey(), k -> new ArrayList<>());
			for (int to : walked.getValue())
			{
				if (!list.contains(to))
				{
					list.add(to);
				}
			}
		}
		linkRevision++;
	}

	private void admit(int x, int y, int plane)
	{
		if (x >= 6400 || isLand(x, y, plane) || isOcean(x, y, plane))
		{
			return;
		}
		int component = componentAt(x, y, plane);
		if (component != 0 && !landComponents[component])
		{
			landComponents[component] = true;
			admittedFloors++;
		}
	}

	private boolean isLand(int x, int y, int plane)
	{
		Region region = region(regionIdOf(x, y), plane);
		return region != null && bit(region.land, x, y);
	}

	private boolean flag(int x, int y, int plane, int which)
	{
		Region region = region(regionIdOf(x, y), plane);
		if (region == null)
		{
			return false;
		}
		return (region.collision[(y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)] >>> which & 1) != 0;
	}

	private Region region(int regionId, int plane)
	{
		if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
		{
			int at = regionId << 2 | (plane & 0xF);
			Region region = byRegion[at];
			return region != null || !packedAt[at] ? region : unpack(regionId, plane);
		}
		Region region = regions.get(key(regionId, plane));
		return region != null || !packed.containsKey(key(regionId, plane)) ? region : unpack(regionId, plane);
	}

	private synchronized Region unpack(int regionId, int plane)
	{
		long key = key(regionId, plane);
		Region region = regions.get(key);
		byte[] blob = packed.remove(key);
		if (region != null || blob == null)
		{
			return region;
		}
		try (DataInputStream data = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(blob))))
		{
			region = readRecord(data);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("World mesh region {} plane {} unreadable", regionId, plane, e);
			region = null;
		}
		if (region != null)
		{
			put(regionId, plane, region);
		}
		if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
		{
			packedAt[regionId << 2 | (plane & 0xF)] = false;
		}
		return region;
	}

	private static Region readRecord(DataInputStream data) throws IOException
	{
		byte[] flags = new byte[COLLISION_BYTES];
		data.readFully(flags);
		byte[] sea = new byte[DERIVED_BYTES];
		data.readFully(sea);
		byte[] alone = new byte[DERIVED_BYTES];
		data.readFully(alone);
		byte[] parts = new byte[COMPONENT_BYTES];
		data.readFully(parts);
		byte[] ground = new byte[DERIVED_BYTES];
		data.readFully(ground);
		byte[] wet = new byte[DERIVED_BYTES];
		data.readFully(wet);

		int[] palette = palette(parts);
		if (palette == null)
		{
			char[] wide = new char[TILES_PER_PLANE];
			for (int tile = 0; tile < TILES_PER_PLANE; tile++)
			{
				wide[tile] = (char) (((parts[tile * 2] & 0xFF) << 8) | (parts[tile * 2 + 1] & 0xFF));
			}
			return new Region(flags, sea, alone, null, new int[1], wide, ground, wet);
		}
		return new Region(flags, sea, alone, indexInto(parts, palette), palette, null, ground, wet);
	}

	private void put(int regionId, int plane, Region region)
	{
		regions.put(key(regionId, plane), region);
		if (regionId >= 0 && regionId < FAST_REGIONS && (plane & 0xF) < 4)
		{
			byRegion[regionId << 2 | (plane & 0xF)] = region;
		}
	}

	private static boolean bit(byte[] bits, int x, int y)
	{
		int bit = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		return (bits[bit >> 3] >>> (bit & 7) & 1) != 0;
	}

	private static int componentOf(Region region, int x, int y)
	{
		int tile = (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK);
		if (region.wide != null)
		{
			return region.wide[tile];
		}
		return region.palette[region.components == null ? 0 : region.components[tile] & 0xFF];
	}

	private static int[] palette(byte[] parts)
	{
		int[] ids = new int[256];
		int count = 0;
		int last = -1;
		for (int tile = 0; tile < TILES_PER_PLANE; tile++)
		{
			int id = ((parts[tile * 2] & 0xFF) << 8) | (parts[tile * 2 + 1] & 0xFF);
			if (id == last)
			{
				continue;
			}
			last = id;
			int i = 0;
			while (i < count && ids[i] != id)
			{
				i++;
			}
			if (i == count)
			{
				if (count == ids.length)
				{
					return null;
				}
				ids[count++] = id;
			}
		}
		return Arrays.copyOf(ids, count);
	}

	private static byte[] indexInto(byte[] parts, int[] palette)
	{
		if (palette.length == 1)
		{
			return null;
		}
		byte[] index = new byte[TILES_PER_PLANE];
		int last = palette[0];
		int at = 0;
		for (int tile = 0; tile < TILES_PER_PLANE; tile++)
		{
			int id = ((parts[tile * 2] & 0xFF) << 8) | (parts[tile * 2 + 1] & 0xFF);
			if (id != last)
			{
				at = 0;
				while (palette[at] != id)
				{
					at++;
				}
				last = id;
			}
			index[tile] = (byte) at;
		}
		return index;
	}

	private static long pair(int one, int other)
	{
		return (long) Math.min(one, other) << 16 | Math.max(one, other);
	}

	private static int regionIdOf(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	private static long key(int regionId, int plane)
	{
		return ((long) regionId << 4) | (plane & 0xF);
	}
}
