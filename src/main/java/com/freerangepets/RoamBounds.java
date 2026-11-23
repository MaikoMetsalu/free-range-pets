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

import java.util.Random;
import java.util.function.BiPredicate;

/**
 * Local wander window and personal-space avoidance predicate for
 * {@link PetPathfinder}.
 */
final class RoamBounds
{
	private static final int WANDER_WINDOW = 14;

	private final LiveCollisionMap collision;
	private final int plane;
	private final int centreX;
	private final int centreY;
	private final BiPredicate<Integer, Integer> avoid;

	RoamBounds(LiveCollisionMap collision, int plane, int centreX, int centreY)
	{
		this(collision, plane, centreX, centreY, null);
	}

	RoamBounds(LiveCollisionMap collision, int plane, int centreX, int centreY,
		BiPredicate<Integer, Integer> avoid)
	{
		this.collision = collision;
		this.plane = plane;
		this.centreX = centreX;
		this.centreY = centreY;
		this.avoid = avoid;
	}

	boolean avoids(int worldX, int worldY)
	{
		return avoid != null && avoid.test(worldX, worldY);
	}

	boolean contains(int worldX, int worldY)
	{
		return collision.isKnownWalkable(worldX, worldY, plane);
	}

	int[] sample(Random random)
	{
		return new int[]{
			centreX - WANDER_WINDOW + random.nextInt(WANDER_WINDOW * 2 + 1),
			centreY - WANDER_WINDOW + random.nextInt(WANDER_WINDOW * 2 + 1)
		};
	}
}
