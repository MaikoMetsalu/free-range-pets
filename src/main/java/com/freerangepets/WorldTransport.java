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

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * A non-walking connection between two tiles: a door, gate, ladder, staircase,
 * stile, bridge, cave entrance, or shortcut.
 */
final class WorldTransport
{
	static final int ARCHETYPE_NONE = 0;
	static final int ARCHETYPE_DOOR = 1;
	static final int ARCHETYPE_LADDER = 2;
	static final int ARCHETYPE_CLIMB = 3;
	static final int ARCHETYPE_DITCH = 4;
	static final int ARCHETYPE_JUMP = 5;
	static final int ARCHETYPE_GANGPLANK = 6;
	static final int ARCHETYPE_CLIMB_OVER = 7;
	static final int ARCHETYPE_SQUEEZE = 8;
	static final int ARCHETYPE_BALANCE = 9;
	static final int ARCHETYPE_STILE = 10;
	static final int ARCHETYPE_TIGHTROPE = 11;
	static final int ARCHETYPE_STAIRS = 12;

	private static final int LOCAL_CROSSING_MAX_TILES = 8;

	@Getter
	private final int fromX;
	@Getter
	private final int fromY;
	@Getter
	private final int fromPlane;
	@Getter
	private final int toX;
	@Getter
	private final int toY;
	@Getter
	private final int toPlane;
	@Getter
	private final int duration;
	@Getter
	private final int archetype;
	@Getter
	private final int objectId;

	private final int[] skillRequirements;
	private final int[] questRequirements;
	private final int[] varbitRequirements;
	private final int[] varpRequirements;

	@Getter
	private boolean learnFirst;
	@Getter
	private int index = -1;

	WorldTransport(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane,
		int duration, int archetype, int objectId, int[] skills, int[] quests, int[] varbits, int[] varps)
	{
		this.fromX = fromX;
		this.fromY = fromY;
		this.fromPlane = fromPlane;
		this.toX = toX;
		this.toY = toY;
		this.toPlane = toPlane;
		this.duration = duration;
		this.archetype = archetype;
		this.objectId = objectId;
		this.skillRequirements = skills;
		this.questRequirements = quests;
		this.varbitRequirements = varbits;
		this.varpRequirements = varps;
	}

	void setLearnFirst(boolean learnFirst)
	{
		this.learnFirst = learnFirst;
	}

	void setIndex(int index)
	{
		this.index = index;
	}

	int[] skills()
	{
		return skillRequirements;
	}

	int[] quests()
	{
		return questRequirements;
	}

	int[] varbits()
	{
		return varbitRequirements;
	}

	int[] varps()
	{
		return varpRequirements;
	}

	boolean isUnconditional()
	{
		return skillRequirements.length == 0
			&& questRequirements.length == 0
			&& varbitRequirements.length == 0
			&& varpRequirements.length == 0;
	}

	/**
	 * True when this transport smoothly moves across a short visible gap on the
	 * same floor (doorway, stile, ditch, stepping stone, squeeze-through) rather
	 * than changing floors or entering a cave.
	 */
	boolean isLocalCrossing()
	{
		if (fromPlane != toPlane)
		{
			return false;
		}
		int span = WorldLayout.span(toX - fromX, toY - fromY);
		return span > 0 && span <= LOCAL_CROSSING_MAX_TILES;
	}

	WorldTransport backThrough()
	{
		WorldTransport back = new WorldTransport(
			toX, toY, toPlane, fromX, fromY, fromPlane,
			duration, archetype, objectId,
			new int[0], new int[0], new int[0], new int[0]
		);
		back.learnFirst = learnFirst;
		return back;
	}

	long endpointKey()
	{
		return TransportNetwork.endpoints(fromX, fromY, fromPlane, toX, toY, toPlane);
	}

	long reverseKey()
	{
		return TransportNetwork.endpoints(toX, toY, toPlane, fromX, fromY, fromPlane);
	}

	WorldPoint destination()
	{
		return new WorldPoint(toX, toY, toPlane);
	}
}
