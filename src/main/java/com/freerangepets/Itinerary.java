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
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Pure-function time-interpolated route for an off-screen ({@code FAR}) pet.
 *
 * <p>Stores the walked waypoints, start tick, duration, and optional
 * {@link WorldTransport} taken at the end of the walk. Position at any tick is
 * computed directly from elapsed time with zero drift.
 */
final class Itinerary
{
	private final int[] xs;
	private final int[] ys;
	private final int plane;
	private final int[] reached;
	private final int length;

	@Getter
	private final int startTick;

	@Getter
	private final int duration;

	private final WorldPoint landing;
	private final WorldTransport transport;

	private int memoTick = Integer.MIN_VALUE;
	private int memoX;
	private int memoY;
	private WorldPoint walkEnd;

	private Itinerary(int[] xs, int[] ys, int[] reached, int length, int plane,
		int startTick, int duration, WorldPoint landing, WorldTransport transport)
	{
		this.xs = xs;
		this.ys = ys;
		this.reached = reached;
		this.length = length;
		this.plane = plane;
		this.startTick = startTick;
		this.duration = duration;
		this.landing = landing;
		this.transport = transport;
	}

	static Itinerary of(List<int[]> path, int plane, int startTick, int extraTicks)
	{
		if (path == null || path.isEmpty())
		{
			return null;
		}
		int[] xs = new int[path.size()];
		int[] ys = new int[path.size()];
		int[] reached = new int[path.size()];
		int travelled = 0;
		for (int i = 0; i < path.size(); i++)
		{
			xs[i] = path.get(i)[0];
			ys[i] = path.get(i)[1];
			if (i > 0)
			{
				travelled += WorldLayout.span(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
			}
			reached[i] = travelled;
		}
		return new Itinerary(xs, ys, reached, travelled, plane, startTick,
			Math.max(1, travelled + extraTicks), null, null);
	}

	static Itinerary thenTransport(List<int[]> walk, int plane, int startTick, WorldTransport transport)
	{
		Itinerary route = of(walk, plane, startTick, 0);
		if (route == null)
		{
			return null;
		}
		return new Itinerary(
			route.xs, route.ys, route.reached, route.length, plane, startTick,
			route.length + Math.max(1, transport.getDuration()),
			transport.destination(), transport
		);
	}

	int fineXAt(int tick)
	{
		memoise(tick);
		return memoX;
	}

	int fineYAt(int tick)
	{
		memoise(tick);
		return memoY;
	}

	int planeAt(int tick)
	{
		return landing != null && isFinished(tick) ? landing.getPlane() : plane;
	}

	int[] fineAt(int tick)
	{
		if (landing != null && isFinished(tick))
		{
			return new int[]{fine(landing.getX()), fine(landing.getY())};
		}
		return along(travelledAt(tick));
	}

	WorldPoint positionAt(int tick)
	{
		int[] at = fineAt(tick);
		return new WorldPoint(at[0] / RoamingPet.TILE, at[1] / RoamingPet.TILE, planeAt(tick));
	}

	int[] headingAt(int tick)
	{
		if (length <= 0)
		{
			return new int[]{0, 0};
		}
		int segment = segmentFor((int) Math.floor(travelledAt(tick)));
		if (segment >= xs.length - 1)
		{
			segment = Math.max(0, xs.length - 2);
		}
		return new int[]{
			(xs[segment + 1] - xs[segment]) * RoamingPet.TILE,
			(ys[segment + 1] - ys[segment]) * RoamingPet.TILE
		};
	}

	boolean isFinished(int tick)
	{
		return tick - startTick >= duration;
	}

	WorldTransport transport()
	{
		return transport;
	}

	WorldPoint destination()
	{
		return landing != null ? landing : walkEnd();
	}

	WorldPoint walkEnd()
	{
		if (walkEnd == null)
		{
			walkEnd = new WorldPoint(xs[xs.length - 1], ys[ys.length - 1], plane);
		}
		return walkEnd;
	}

	Itinerary reversed(int tick, int newStartTick)
	{
		int at = segmentFor((int) Math.floor(travelledAt(tick)));
		if (at < 1)
		{
			return null;
		}
		List<int[]> back = new ArrayList<>(at + 1);
		for (int i = at; i >= 0; i--)
		{
			back.add(new int[]{xs[i], ys[i]});
		}
		return of(back, plane, newStartTick, 0);
	}

	private void memoise(int tick)
	{
		if (tick != memoTick)
		{
			int[] at = fineAt(tick);
			memoX = at[0];
			memoY = at[1];
			memoTick = tick;
		}
	}

	private double travelledAt(double time)
	{
		double elapsed = Math.min(Math.max(0, time - startTick), duration);
		if (landing != null)
		{
			return Math.min(elapsed, length);
		}
		return length <= 0 || duration <= 0 ? 0 : elapsed * length / duration;
	}

	private int[] along(double travelled)
	{
		int segment = segmentFor((int) Math.floor(travelled));
		if (segment >= xs.length - 1)
		{
			return new int[]{fine(xs[xs.length - 1]), fine(ys[ys.length - 1])};
		}
		int from = reached[segment];
		int span = reached[segment + 1] - from;
		if (span <= 0)
		{
			return new int[]{fine(xs[segment]), fine(ys[segment])};
		}
		double into = travelled - from;
		int fromX = fine(xs[segment]);
		int fromY = fine(ys[segment]);
		int toX = fine(xs[segment + 1]);
		int toY = fine(ys[segment + 1]);
		return new int[]{
			fromX + (int) Math.round((toX - fromX) * into / span),
			fromY + (int) Math.round((toY - fromY) * into / span)
		};
	}

	private int segmentFor(int travelled)
	{
		int low = 0;
		int high = reached.length - 1;
		while (low < high)
		{
			int mid = (low + high + 1) >>> 1;
			if (reached[mid] <= travelled)
			{
				low = mid;
			}
			else
			{
				high = mid - 1;
			}
		}
		return low;
	}

	private static int fine(int tile)
	{
		return tile * RoamingPet.TILE + RoamingPet.TILE / 2;
	}
}
