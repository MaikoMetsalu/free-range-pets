/*
 * Copyright (c) 2026, maiko
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

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Overworld portal locations for each Player-Owned House location value from
 * {@code VarbitID.POH_HOUSE_LOCATION}.
 */
@Getter
@AllArgsConstructor
enum PohPortalLocation
{
	RIMMINGTON(1, new WorldPoint(2954, 3224, 0)),
	TAVERLEY(2, new WorldPoint(2894, 3465, 0)),
	POLLNIVNEACH(3, new WorldPoint(3340, 3004, 0)),
	RELLEKKA(4, new WorldPoint(2670, 3632, 0)),
	BRIMHAVEN(5, new WorldPoint(2758, 3178, 0)),
	YANILLE(6, new WorldPoint(2544, 3095, 0)),
	HOSIDIUS(8, new WorldPoint(1744, 3517, 0)),
	PRIFDDINAS(9, new WorldPoint(3239, 6076, 0)),
	ALDARIN(10, new WorldPoint(1420, 2962, 0));

	private final int varbitValue;
	private final WorldPoint portalTile;

	static PohPortalLocation fromVarbit(int value)
	{
		for (PohPortalLocation location : values())
		{
			if (location.varbitValue == value)
			{
				return location;
			}
		}
		return RIMMINGTON;
	}
}
