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

import java.util.ArrayList;
import java.util.List;

/**
 * Per-pet memory of recently used doors, ladders, and shortcuts so a pet does
 * not oscillate back and forth through the same doorway or staircase.
 */
final class TransportMemory
{
	private static final int HISTORY = 8;
	static final int COOLDOWN_TICKS = 200;
	static final int TRANSPORT_REST_TICKS = 240;

	private static final int SAME_OBSTACLE_TILES = 2;
	private static final int OBSTACLE_REACH = 8;

	private final long[] recent = new long[HISTORY];
	private final int[] expiry = new int[HISTORY];
	private int next;

	private final int[][] journeys = new int[HISTORY][];
	private final int[] journeyExpiry = new int[HISTORY];
	private int nextJourney;

	private final int[][] usedObstacles = new int[HISTORY][];
	private final int[] usedObstacleExpiry = new int[HISTORY];
	private int nextObstacle;

	private int restUntil = Integer.MIN_VALUE;

	boolean restingFromTransports(int tick)
	{
		return tick < restUntil;
	}

	void used(WorldTransport transport, int tick)
	{
		used(transport, tick, COOLDOWN_TICKS);
	}

	void used(WorldTransport transport, int tick, int cooldownTicks)
	{
		restUntil = tick + TRANSPORT_REST_TICKS;
		note(transport, tick, cooldownTicks);
	}

	void walkedThrough(WorldTransport transport, int tick)
	{
		note(transport, tick, COOLDOWN_TICKS);
	}

	List<int[]> recentOrigins(int tick)
	{
		List<int[]> out = new ArrayList<>(HISTORY);
		for (int i = 0; i < HISTORY; i++)
		{
			int[] j = journeys[i];
			if (j != null && journeyExpiry[i] > tick)
			{
				out.add(new int[]{j[0], j[1], j[2]});
			}
		}
		return out;
	}

	boolean onCooldown(WorldTransport transport, int tick)
	{
		long key = transport.endpointKey();
		for (int i = 0; i < HISTORY; i++)
		{
			if (recent[i] == key && expiry[i] > tick)
			{
				return true;
			}
		}
		if ((transport.getFromPlane() != transport.getToPlane()
			|| !isCrossing(transport.getFromX(), transport.getFromY(), transport.getToX(), transport.getToY()))
			&& undoesRecentJourney(transport, tick))
		{
			return true;
		}
		for (int i = 0; i < HISTORY; i++)
		{
			int[] used = usedObstacles[i];
			if (used == null || usedObstacleExpiry[i] <= tick || used[0] != transport.getObjectId()
				|| used[5] != transport.getFromPlane() || transport.getToPlane() != transport.getFromPlane()
				|| !isCrossing(transport.getFromX(), transport.getFromY(), transport.getToX(), transport.getToY()))
			{
				continue;
			}
			if (near(transport.getFromX(), transport.getFromY(), used[1], used[2])
				|| near(transport.getFromX(), transport.getFromY(), used[3], used[4]))
			{
				int lastX = used[3] - used[1];
				int lastY = used[4] - used[2];
				int nextX = transport.getToX() - transport.getFromX();
				int nextY = transport.getToY() - transport.getFromY();
				if (lastX * nextX + lastY * nextY > 0)
				{
					continue;
				}
				return true;
			}
		}
		return false;
	}

	private void note(WorldTransport transport, int tick, int cooldownTicks)
	{
		remember(transport.endpointKey(), tick, cooldownTicks);
		remember(transport.reverseKey(), tick, cooldownTicks);
		journeys[nextJourney] = new int[]{
			transport.getFromX(), transport.getFromY(), transport.getFromPlane(),
			transport.getToX(), transport.getToY(), transport.getToPlane()
		};
		journeyExpiry[nextJourney] = tick + cooldownTicks;
		nextJourney = (nextJourney + 1) % HISTORY;

		if (transport.getFromPlane() == transport.getToPlane()
			&& isCrossing(transport.getFromX(), transport.getFromY(), transport.getToX(), transport.getToY()))
		{
			usedObstacles[nextObstacle] = new int[]{
				transport.getObjectId(),
				transport.getFromX(), transport.getFromY(),
				transport.getToX(), transport.getToY(),
				transport.getFromPlane()
			};
			usedObstacleExpiry[nextObstacle] = tick + cooldownTicks;
			nextObstacle = (nextObstacle + 1) % HISTORY;
		}
	}

	private boolean undoesRecentJourney(WorldTransport transport, int tick)
	{
		for (int i = 0; i < HISTORY; i++)
		{
			int[] j = journeys[i];
			if (j != null && journeyExpiry[i] > tick
				&& transport.getFromPlane() == j[5] && transport.getToPlane() == j[2]
				&& near(transport.getFromX(), transport.getFromY(), j[3], j[4])
				&& near(transport.getToX(), transport.getToY(), j[0], j[1]))
			{
				return true;
			}
		}
		return false;
	}

	private void remember(long key, int tick, int cooldownTicks)
	{
		recent[next] = key;
		expiry[next] = tick + cooldownTicks;
		next = (next + 1) % HISTORY;
	}

	private static boolean isCrossing(int fromX, int fromY, int toX, int toY)
	{
		return WorldLayout.span(toX - fromX, toY - fromY) <= OBSTACLE_REACH;
	}

	private static boolean near(int x, int y, int otherX, int otherY)
	{
		return WorldLayout.span(x - otherX, y - otherY) <= SAME_OBSTACLE_TILES;
	}
}
