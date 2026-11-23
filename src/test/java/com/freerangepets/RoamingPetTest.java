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

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class RoamingPetTest
{
	@Test
	public void farRoamingMovesPetOverRealWorldMesh() throws Exception
	{
		WorldMeshAndRoamingTest.WorldHarness world = WorldMeshAndRoamingTest.WorldHarness.create();
		PetSnapshot snap = sampleSnapshot();
		WorldPoint start = new WorldPoint(3222, 3218, 0); // Lumbridge courtyard
		RoamingPet pet = new RoamingPet(42L, snap, start, 0);

		for (int tick = 0; tick < 400; tick += 5)
		{
			world.context.setTick(tick);
			pet.advanceFar(world.context, world.planner);
		}

		WorldPoint end = pet.currentTile();
		assertNotEquals("Pet should roam away from Lumbridge over 400 ticks", start, end);
		assertFalse("Pet should never end up on water", world.mesh.isWater(end.getX(), end.getY(), end.getPlane()));
	}

	@Test
	public void pausedFollowerDoesNotMove() throws Exception
	{
		WorldMeshAndRoamingTest.WorldHarness world = WorldMeshAndRoamingTest.WorldHarness.create();
		WorldPoint start = new WorldPoint(3222, 3218, 0);
		RoamingPet pet = new RoamingPet(99L, sampleSnapshot(), start, 0);
		pet.setPausedByFollower(true);

		for (int tick = 0; tick < 200; tick += 5)
		{
			world.context.setTick(tick);
			pet.advanceFar(world.context, world.planner);
		}

		assertEquals(start, pet.currentTile());
	}

	@Test
	public void displayNamePrefersCustomNickname()
	{
		RoamingPet pet = new RoamingPet(1L, sampleSnapshot(), new WorldPoint(3222, 3218, 0), 0);
		assertEquals("Baby mole", pet.displayName());

		pet.setNickname("Sir Digs");
		assertEquals("Sir Digs", pet.displayName());

		pet.setNickname(null);
		assertEquals("Baby mole", pet.displayName());
	}

	private static PetSnapshot sampleSnapshot()
	{
		return new PetSnapshot(
			6635, "Baby mole", new int[]{1234}, new short[0], new short[0],
			128, 128, 1, 3310, 3311, -1
		);
	}
}
