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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Live scene collision overlay backed by {@link WorldMesh}.
 *
 * <p>As the player moves around Gielinor, loaded overworld regions are harvested
 * into an in-memory two-bit north/east passability map so open doors, closed
 * gates, and recent game updates override the bundled mesh. Everywhere the
 * player has not visited this session falls through transparently to
 * {@link WorldMesh}.
 */
@Singleton
class LiveCollisionMap
{
	private static final int REGION_SIZE = Constants.REGION_SIZE;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int FLAGS_PER_TILE = 2;
	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;
	private static final int WORDS_PER_REGION = (REGION_SIZE * REGION_SIZE * FLAGS_PER_TILE) / Long.SIZE;
	private static final int TILE_WORDS = (REGION_SIZE * REGION_SIZE) / Long.SIZE;

	/** Border tiles of the 104x104 scene that the client pads as blocked. */
	private static final int SCENE_MARGIN = 6;

	/** Client flag for unloaded map squares. */
	private static final int NOT_LOADED = 0x1000000;

	private static final int UNWALKABLE = CollisionDataFlag.BLOCK_MOVEMENT_FULL
		| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION
		| NOT_LOADED;

	private final Map<Long, long[]> regions = new HashMap<>();
	private final Map<Long, long[]> seen = new HashMap<>();
	private final boolean[] mayHold = new boolean[1 << 16];
	private final Set<Long> harvested = new HashSet<>();
	private final Set<Integer> fresh = new LinkedHashSet<>();

	private boolean pendingHarvest = true;

	@Inject
	private Client client;

	@Inject
	private WorldMesh worldMesh;

	void sceneChanged()
	{
		pendingHarvest = true;
	}

	void passabilityChanged(WorldPoint at)
	{
		if (at == null)
		{
			return;
		}
		harvested.remove(key(at.getRegionID(), at.getPlane()));
		pendingHarvest = true;
	}

	void clear()
	{
		regions.clear();
		seen.clear();
		Arrays.fill(mayHold, false);
		harvested.clear();
		fresh.clear();
		pendingHarvest = true;
	}

	boolean holds(int regionId, int plane)
	{
		return regionId >= 0 && regionId < mayHold.length && mayHold[regionId]
			&& regions.containsKey(key(regionId, plane));
	}

	int[] takeFreshRegions()
	{
		int[] out = new int[fresh.size()];
		int i = 0;
		for (int regionId : fresh)
		{
			out[i++] = regionId;
		}
		fresh.clear();
		return out;
	}

	/**
	 * Copies live collision flags for every loaded overworld region on the
	 * current plane when the scene changes or a door opens/closes.
	 */
	void harvestLoadedRegions()
	{
		if (!pendingHarvest || client == null)
		{
			return;
		}
		pendingHarvest = false;

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.isInstance())
		{
			return;
		}

		CollisionData[] maps = wv.getCollisionMaps();
		int plane = wv.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
		{
			return;
		}
		int[][] live = maps[plane].getFlags();
		if (live == null)
		{
			return;
		}

		int[][] flags = new int[live.length][];
		for (int x = 0; x < live.length; x++)
		{
			flags[x] = live[x] == null ? new int[0] : live[x].clone();
		}
		markVoid(wv, plane, flags);

		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();

		for (int regionId : wv.getMapRegions())
		{
			long key = key(regionId, plane);
			if (harvested.contains(key))
			{
				continue;
			}
			if (!regions.containsKey(key))
			{
				seen.put(key, new long[TILE_WORDS]);
			}
			long[] bits = regions.computeIfAbsent(key, k -> new long[WORDS_PER_REGION]);
			held(key);
			long[] seenHere = seen.get(key);

			int regionBaseX = (regionId >> 8) << 6;
			int regionBaseY = (regionId & 0xFF) << 6;
			int tilesSeen = 0;

			for (int rx = 0; rx < REGION_SIZE; rx++)
			{
				for (int ry = 0; ry < REGION_SIZE; ry++)
				{
					int sceneX = regionBaseX + rx - baseX;
					int sceneY = regionBaseY + ry - baseY;
					if (sceneX < SCENE_MARGIN || sceneY < SCENE_MARGIN
						|| sceneX >= Constants.SCENE_SIZE - SCENE_MARGIN
						|| sceneY >= Constants.SCENE_SIZE - SCENE_MARGIN
						|| sceneX >= flags.length || sceneY >= flags[sceneX].length)
					{
						continue;
					}
					tilesSeen++;

					boolean northKnown = sceneY + 1 < Constants.SCENE_SIZE - SCENE_MARGIN;
					boolean eastKnown = sceneX + 1 < Constants.SCENE_SIZE - SCENE_MARGIN;
					if (seenHere != null && northKnown && eastKnown)
					{
						markSeen(seenHere, rx, ry);
					}

					int here = flags[sceneX][sceneY];
					boolean blocked = (here & UNWALKABLE) != 0;
					if (northKnown || blocked)
					{
						clearBit(bits, rx, ry, FLAG_NORTH);
					}
					if (eastKnown || blocked)
					{
						clearBit(bits, rx, ry, FLAG_EAST);
					}
					if (blocked)
					{
						continue;
					}

					if (northKnown && (here & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0
						&& standable(flags, sceneX, sceneY + 1))
					{
						setBit(bits, rx, ry, FLAG_NORTH);
					}
					if (eastKnown && (here & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0
						&& standable(flags, sceneX + 1, sceneY))
					{
						setBit(bits, rx, ry, FLAG_EAST);
					}
				}
			}

			if (tilesSeen == REGION_SIZE * REGION_SIZE)
			{
				harvested.add(key);
				seen.remove(key);
			}
		}
	}

	boolean isKnownWalkable(int worldX, int worldY, int plane)
	{
		return north(worldX, worldY, plane) || east(worldX, worldY, plane)
			|| north(worldX, worldY - 1, plane) || east(worldX - 1, worldY, plane);
	}

	boolean north(int x, int y, int plane)
	{
		return get(x, y, plane, FLAG_NORTH);
	}

	boolean east(int x, int y, int plane)
	{
		return get(x, y, plane, FLAG_EAST);
	}

	private boolean south(int x, int y, int plane)
	{
		return north(x, y - 1, plane);
	}

	private boolean west(int x, int y, int plane)
	{
		return east(x - 1, y, plane);
	}

	/**
	 * Cardinal or diagonal step check following OSRS corner rules so pets never
	 * clip diagonally through wall corners or water.
	 */
	boolean canStep(int x, int y, int plane, int dx, int dy)
	{
		if (dx == 0 && dy == 1)
		{
			return north(x, y, plane);
		}
		if (dx == 0 && dy == -1)
		{
			return south(x, y, plane);
		}
		if (dx == 1 && dy == 0)
		{
			return east(x, y, plane);
		}
		if (dx == -1 && dy == 0)
		{
			return west(x, y, plane);
		}
		if (dx == 1 && dy == 1)
		{
			return north(x, y, plane) && east(x, y + 1, plane)
				&& east(x, y, plane) && north(x + 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, 1, 1);
		}
		if (dx == -1 && dy == 1)
		{
			return north(x, y, plane) && west(x, y + 1, plane)
				&& west(x, y, plane) && north(x - 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, -1, 1);
		}
		if (dx == 1 && dy == -1)
		{
			return south(x, y, plane) && east(x, y - 1, plane)
				&& east(x, y, plane) && south(x + 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, 1, -1);
		}
		if (dx == -1 && dy == -1)
		{
			return south(x, y, plane) && west(x, y - 1, plane)
				&& west(x, y, plane) && south(x - 1, y, plane)
				&& !worldMesh.cornerBlocks(x, y, plane, -1, -1);
		}
		return false;
	}

	private boolean get(int x, int y, int plane, int flag)
	{
		int toX = flag == FLAG_EAST ? x + 1 : x;
		int toY = flag == FLAG_NORTH ? y + 1 : y;
		if (worldMesh.isWater(x, y, plane) || worldMesh.isWater(toX, toY, plane))
		{
			return false;
		}

		int regionId = regionIdOf(x, y);
		long key = key(regionId, plane);
		long[] bits = regionId >= 0 && regionId < mayHold.length && !mayHold[regionId] ? null : regions.get(key);
		long[] mask = bits == null ? null : seen.get(key);
		if (bits != null && (mask == null || isSeen(mask, x & REGION_MASK, y & REGION_MASK)))
		{
			int bit = bitIndex(x & REGION_MASK, y & REGION_MASK, flag);
			return (bits[bit >> 6] >>> (bit & 63) & 1L) != 0L;
		}

		if (flag == FLAG_NORTH)
		{
			return worldMesh.isLandWalkable(x, y, plane)
				&& worldMesh.isLandWalkable(x, y + 1, plane)
				&& worldMesh.north(x, y, plane);
		}
		return worldMesh.isLandWalkable(x, y, plane)
			&& worldMesh.isLandWalkable(x + 1, y, plane)
			&& worldMesh.east(x, y, plane);
	}

	private void held(long key)
	{
		int regionId = (int) (key >> 8);
		fresh.add(regionId);
		if (regionId >= 0 && regionId < mayHold.length)
		{
			mayHold[regionId] = true;
		}
	}

	private static void markVoid(WorldView wv, int plane, int[][] flags)
	{
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || plane >= tiles.length)
		{
			return;
		}
		for (int x = 0; x < flags.length && x < tiles[plane].length; x++)
		{
			for (int y = 0; y < flags[x].length && y < tiles[plane][x].length; y++)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null || (tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null))
				{
					flags[x][y] |= NOT_LOADED;
				}
			}
		}
	}

	private static boolean standable(int[][] flags, int sceneX, int sceneY)
	{
		if (sceneX < 0 || sceneY < 0 || sceneX >= flags.length || flags[sceneX] == null || sceneY >= flags[sceneX].length)
		{
			return false;
		}
		return (flags[sceneX][sceneY] & UNWALKABLE) == 0;
	}

	private static void markSeen(long[] mask, int rx, int ry)
	{
		int tile = ry * REGION_SIZE + rx;
		mask[tile >> 6] |= 1L << (tile & 63);
	}

	private static boolean isSeen(long[] mask, int rx, int ry)
	{
		int tile = ry * REGION_SIZE + rx;
		return (mask[tile >> 6] >>> (tile & 63) & 1L) != 0L;
	}

	private static void setBit(long[] bits, int rx, int ry, int flag)
	{
		int bit = bitIndex(rx, ry, flag);
		bits[bit >> 6] |= 1L << (bit & 63);
	}

	private static void clearBit(long[] bits, int rx, int ry, int flag)
	{
		int bit = bitIndex(rx, ry, flag);
		bits[bit >> 6] &= ~(1L << (bit & 63));
	}

	private static int bitIndex(int rx, int ry, int flag)
	{
		return (ry * REGION_SIZE + rx) * FLAGS_PER_TILE + flag;
	}

	private static int regionIdOf(int worldX, int worldY)
	{
		return ((worldX >> 6) << 8) | (worldY >> 6);
	}

	private static long key(int regionId, int plane)
	{
		return ((long) regionId << 8) | (plane & 0xFF);
	}
}
