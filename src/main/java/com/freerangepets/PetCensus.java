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
import javax.inject.Singleton;

/**
 * Counts how many roaming pets occupy each 64x64 map region and plane so pets
 * actively disperse across Gielinor rather than clumping in the same towns.
 */
@Singleton
class PetCensus
{
	/**
	 * A pet strongly prefers having its own region: entering a region that
	 * already holds another pet is heavily penalised, and sharing a region with
	 * another pet encourages walking or taking a transport elsewhere.
	 */
	static final int IDEAL_PER_REGION = 1;

	private final int[] counts = new int[1 << 18];

	void recount(Iterable<RoamingPet> pets)
	{
		begin();
		for (RoamingPet pet : pets)
		{
			if (!pet.isPausedByFollower())
			{
				add(pet.getFineX() / RoamingPet.TILE, pet.getFineY() / RoamingPet.TILE, pet.getPlane());
			}
		}
	}

	void begin()
	{
		Arrays.fill(counts, 0);
	}

	void add(int x, int y, int plane)
	{
		int slot = key(x, y, plane);
		if (slot >= 0 && slot < counts.length)
		{
			counts[slot]++;
		}
	}

	void clear()
	{
		Arrays.fill(counts, 0);
	}

	int at(int x, int y, int plane)
	{
		int slot = key(x, y, plane);
		return slot >= 0 && slot < counts.length ? counts[slot] : 0;
	}

	/**
	 * True when another pet already shares this 64x64 region and plane, prompting
	 * this pet to wander onward rather than settle down together.
	 */
	boolean isSharedRegion(int x, int y, int plane)
	{
		return at(x, y, plane) > IDEAL_PER_REGION;
	}

	/**
	 * Desirability of entering this tile's region (0..1): 1.0 when empty, steeply
	 * lower when another pet is already there.
	 */
	float roominess(int x, int y, int plane)
	{
		int existing = at(x, y, plane);
		if (existing <= 0)
		{
			return 1f;
		}
		if (existing == 1)
		{
			return 0.15f;
		}
		return 0.15f / (float) existing;
	}

	private static int key(int x, int y, int plane)
	{
		return (((x >> 6) & 0xFF) << 10) | (((y >> 6) & 0xFF) << 2) | (plane & 3);
	}
}
