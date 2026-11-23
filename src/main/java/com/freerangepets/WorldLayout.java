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

/**
 * Coordinate layout helpers distinguishing surface tiles, caves offset north of
 * the surface, and separate map regions (such as Prifddinas or Zanaris).
 */
final class WorldLayout
{
	static final int SURFACE_TOP = 4160;
	static final int CAVE_OFFSET = 6400;

	private WorldLayout()
	{
	}

	static boolean isSurface(int y)
	{
		return y < SURFACE_TOP;
	}

	static boolean isCave(int y)
	{
		return y >= CAVE_OFFSET;
	}

	static int groundAbove(int y)
	{
		return isSurface(y) ? y : isCave(y) ? y - CAVE_OFFSET : -1;
	}

	/** Chebyshev tile distance (diagonals cost 1 tick just like cardinals). */
	static int span(int dx, int dy)
	{
		return Math.max(Math.abs(dx), Math.abs(dy));
	}
}
