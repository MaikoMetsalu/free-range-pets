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
import java.util.Deque;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * World-space 8-directional BFS pathfinder over {@link LiveCollisionMap} (which
 * combines live scene collision with the bundled {@link WorldMesh}), working
 * identically whether a pet is on screen or several regions away.
 */
@Singleton
class PetPathfinder
{
	private static final int NODE_BUDGET = 2500;
	private static final int DESTINATION_ATTEMPTS = 10;
	private static final int SMALL_FLOOR = 200;

	/** Cardinals first so straight routes beat diagonal zigzags of equal cost. */
	private static final int[] DX = {1, -1, 0, 0, 1, -1, 1, -1};
	private static final int[] DY = {0, 0, 1, -1, 1, 1, -1, -1};

	@Inject
	private LiveCollisionMap collision;

	Deque<int[]> wanderPath(int startX, int startY, int plane, RoamBounds bounds, Random random)
	{
		int searched = 0;
		for (int sampled = 0; sampled < DESTINATION_ATTEMPTS * 8 && searched < DESTINATION_ATTEMPTS; sampled++)
		{
			int[] target = bounds.sample(random);
			if (target == null || (target[0] == startX && target[1] == startY))
			{
				continue;
			}
			if (!bounds.contains(target[0], target[1])
				|| !collision.isKnownWalkable(target[0], target[1], plane)
				|| bounds.avoids(target[0], target[1]))
			{
				continue;
			}
			searched++;
			Deque<int[]> path = findPath(startX, startY, plane, target[0], target[1], bounds);
			if (!path.isEmpty())
			{
				return path;
			}
		}
		return smallFloorPath(startX, startY, plane, bounds, random);
	}

	Deque<int[]> findPath(int startX, int startY, int plane, int goalX, int goalY, RoamBounds bounds)
	{
		Deque<int[]> result = new ArrayDeque<>();
		if (startX == goalX && startY == goalY)
		{
			return result;
		}

		TileMap cameFrom = new TileMap(1024);
		int head = 0;
		long start = pack(startX, startY);
		long goal = pack(goalX, goalY);
		cameFrom.add(start, start);

		int visited = 0;
		boolean found = false;

		while (head < cameFrom.size() && visited < NODE_BUDGET)
		{
			long current = cameFrom.keyAt(head++);
			visited++;
			if (current == goal)
			{
				found = true;
				break;
			}
			int cx = unpackX(current);
			int cy = unpackY(current);
			for (int d = 0; d < DX.length; d++)
			{
				int nx = cx + DX[d];
				int ny = cy + DY[d];
				long next = pack(nx, ny);
				if (cameFrom.containsKey(next)
					|| !bounds.contains(nx, ny)
					|| !collision.canStep(cx, cy, plane, DX[d], DY[d]))
				{
					continue;
				}
				cameFrom.add(next, current);
			}
		}

		if (!found)
		{
			return result;
		}
		for (long at = goal; at != start; at = cameFrom.get(at))
		{
			result.addFirst(new int[]{unpackX(at), unpackY(at)});
		}
		return result;
	}

	TileMap flood(int startX, int startY, int plane, int budget)
	{
		TileMap cameFrom = new TileMap(budget + 8);
		int head = 0;
		long start = pack(startX, startY);
		cameFrom.add(start, start);

		while (head < cameFrom.size() && cameFrom.size() < budget)
		{
			long current = cameFrom.keyAt(head++);
			int cx = unpackX(current);
			int cy = unpackY(current);
			for (int d = 0; d < DX.length; d++)
			{
				int nx = cx + DX[d];
				int ny = cy + DY[d];
				long next = pack(nx, ny);
				if (cameFrom.containsKey(next)
					|| !collision.isKnownWalkable(nx, ny, plane)
					|| !collision.canStep(cx, cy, plane, DX[d], DY[d]))
				{
					continue;
				}
				cameFrom.add(next, current);
			}
		}
		return cameFrom;
	}

	static Deque<int[]> pathFrom(TileMap cameFrom, int startX, int startY, int goalX, int goalY)
	{
		long start = pack(startX, startY);
		long goal = pack(goalX, goalY);
		if (!cameFrom.containsKey(goal))
		{
			return null;
		}
		Deque<int[]> result = new ArrayDeque<>();
		for (long at = goal; at != start; at = cameFrom.get(at))
		{
			result.addFirst(new int[]{unpackX(at), unpackY(at)});
		}
		return result;
	}

	int areaSize(int x, int y, int plane, int limit)
	{
		TileMap seen = new TileMap(limit + 8);
		int head = 0;
		seen.add(pack(x, y), 0);
		while (head < seen.size() && seen.size() < limit)
		{
			long current = seen.keyAt(head++);
			int cx = unpackX(current);
			int cy = unpackY(current);
			for (int d = 0; d < DX.length; d++)
			{
				long next = pack(cx + DX[d], cy + DY[d]);
				if (!seen.containsKey(next) && collision.canStep(cx, cy, plane, DX[d], DY[d]))
				{
					seen.add(next, 0);
				}
			}
		}
		return seen.size();
	}

	private Deque<int[]> smallFloorPath(int startX, int startY, int plane, RoamBounds bounds, Random random)
	{
		TileMap reach = flood(startX, startY, plane, SMALL_FLOOR);
		for (int tries = 0; tries < 8 && reach.size() > 1; tries++)
		{
			long pick = reach.keyAt(1 + random.nextInt(reach.size() - 1));
			int x = unpackX(pick);
			int y = unpackY(pick);
			if (bounds.contains(x, y) && !bounds.avoids(x, y))
			{
				Deque<int[]> found = pathFrom(reach, startX, startY, x, y);
				if (found != null)
				{
					return found;
				}
			}
		}
		return new ArrayDeque<>();
	}

	static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	static int unpackX(long packed)
	{
		return (int) (packed >> 32);
	}

	static int unpackY(long packed)
	{
		return (int) packed;
	}
}
