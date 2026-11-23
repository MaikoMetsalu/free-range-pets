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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * Shared world view passed to pets each frame: live collision, pathfinder,
 * transports, abilities, anti-crowding census, personal-space occupancy, and
 * tick timing.
 */
@Getter
@RequiredArgsConstructor
final class RoamContext
{
	static final int OPEN_AREA = 400;
	static final int PERSONAL_SPACE_TILES = 3;

	private static final float STAYING_PUT = 0.08f;
	private static final int AREA_REFRESH_TICKS = 500;
	private static final int MOST_AREAS = 8192;

	private final LiveCollisionMap collision;
	private final PetPathfinder pathfinder;
	private final TransportNetwork transports;
	private final PlayerAbilities abilities;

	@Setter
	private PetCensus census;

	@Setter
	private RoamPlanner planner;

	@Setter
	private TileMap occupancy;

	@Setter
	private int tick;

	@Setter
	private boolean mayPath;

	private final Map<Long, int[]> areas = new LinkedHashMap<Long, int[]>(1024, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest)
		{
			return size() > MOST_AREAS;
		}
	};

	boolean crowdedRegion(int x, int y, int plane)
	{
		return census != null && census.isSharedRegion(x, y, plane);
	}

	/**
	 * How appealing a transport is (0..1) with respect to keeping pets dispersed:
	 * pets strongly prefer entering empty regions and leaving shared ones.
	 */
	float appeal(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane)
	{
		if (census == null)
		{
			return 1f;
		}
		boolean sharedHere = census.isSharedRegion(fromX, fromY, fromPlane);
		float there = census.roominess(toX, toY, toPlane);
		if (!sharedHere)
		{
			return there;
		}
		if (fromPlane == toPlane && (fromX >> 6) == (toX >> 6) && (fromY >> 6) == (toY >> 6))
		{
			return STAYING_PUT;
		}
		return Math.min(1f, there * 3f);
	}

	boolean mayEnter(WorldTransport transport)
	{
		return planner == null || planner.mayEnter(transport, tick);
	}

	float wayOnAppeal(WorldTransport transport)
	{
		return planner == null ? 1f : planner.wayOnAppeal(transport, tick);
	}

	Set<Integer> spacesLeft(int x, int y, int plane, TransportMemory memory)
	{
		return planner == null ? Collections.emptySet() : planner.spacesLeft(x, y, plane, tick, memory);
	}

	boolean leadsInto(Set<Integer> spaces, WorldTransport transport)
	{
		return planner != null && planner.leadsInto(spaces, transport);
	}

	/**
	 * True when another pet already occupies a tile within {@link #PERSONAL_SPACE_TILES}
	 * of {@code (x, y)}, keeping visible pets comfortably spaced apart.
	 */
	boolean nearAnotherPet(int x, int y, int plane, int selfX, int selfY)
	{
		if (occupancy == null || occupancy.size() == 0)
		{
			return false;
		}
		for (int dx = -PERSONAL_SPACE_TILES; dx <= PERSONAL_SPACE_TILES; dx++)
		{
			int tx = x + dx;
			for (int dy = -PERSONAL_SPACE_TILES; dy <= PERSONAL_SPACE_TILES; dy++)
			{
				int ty = y + dy;
				long count = occupancy.getOrDefault(tileKey(tx, ty, plane), 0L);
				if (count <= 0)
				{
					continue;
				}
				if (tx == selfX && ty == selfY)
				{
					if (count > 1)
					{
						return true;
					}
				}
				else
				{
					return true;
				}
			}
		}
		return false;
	}

	int enclosedArea(int x, int y, int plane)
	{
		long key = tileKey(x, y, plane);
		int[] cached = areas.get(key);
		if (cached != null && tick - cached[1] < AREA_REFRESH_TICKS)
		{
			return cached[0];
		}
		int size = pathfinder.areaSize(x, y, plane, OPEN_AREA);
		areas.put(key, new int[]{size, tick});
		return size;
	}

	static long tileKey(int x, int y, int plane)
	{
		return ((long) plane << 40) | ((long) (x & 0xFFFFF) << 20) | (y & 0xFFFFF);
	}
}
