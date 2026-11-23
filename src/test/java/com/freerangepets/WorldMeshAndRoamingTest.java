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

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WorldMeshAndRoamingTest
{
	static final class WorldHarness
	{
		final WorldMesh mesh = new WorldMesh();
		final LiveCollisionMap collision = new LiveCollisionMap();
		final TransportNetwork transports = new TransportNetwork();
		final PlayerAbilities abilities = new PlayerAbilities();
		final PetPathfinder pathfinder = new PetPathfinder();
		final PetCensus census = new PetCensus();
		final RoamPlanner planner = new RoamPlanner();
		final PlaceNames places = new PlaceNames();
		final RoamContext context;

		private WorldHarness() throws Exception
		{
			mesh.load();
			transports.load();
			places.load();
			mesh.admitTransportEnds(transports.all());
			mesh.linkSpaces(WaysBack.MAINLAND_X, WaysBack.MAINLAND_Y, 0);

			setField(collision, "worldMesh", mesh);
			setField(pathfinder, "collision", collision);
			setField(planner, "mesh", mesh);
			setField(planner, "collision", collision);
			setField(planner, "transports", transports);
			setField(planner, "abilities", abilities);

			context = new RoamContext(collision, pathfinder, transports, abilities);
			context.setCensus(census);
			context.setPlanner(planner);
			context.setOccupancy(new TileMap(32));
			context.setMayPath(true);
		}

		static WorldHarness create() throws Exception
		{
			return new WorldHarness();
		}

		private static void setField(Object target, String name, Object value) throws Exception
		{
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		}
	}

	@Test
	public void loadsBundledMeshTransportsAndPlaceNames() throws Exception
	{
		WorldHarness world = WorldHarness.create();

		assertTrue("WorldMesh should load bundled regions", world.mesh.holdsAnySquare());
		assertTrue("Lumbridge courtyard should be land-walkable", world.mesh.isLandWalkable(3222, 3218, 0));
		assertTrue("TransportNetwork should load thousands of transports", world.transports.size() > 5000);

		String lumbridge = world.places.describe(new WorldPoint(3222, 3218, 0));
		assertNotNull(lumbridge);
		assertTrue("Expected Lumbridge in description, got: " + lumbridge, lumbridge.contains("Lumbridge"));

		String upstairs = world.places.describe(new WorldPoint(3209, 3218, 1));
		assertTrue("Expected upstairs suffix, got: " + upstairs, upstairs.contains("upstairs"));
	}

	@Test
	public void scatterPlacesThirtyPetsInDistinctRegionsWithZeroClumping() throws Exception
	{
		WorldHarness world = WorldHarness.create();
		Random rng = new Random(1337L);
		Set<Integer> usedRegions = new HashSet<>();

		for (int i = 0; i < 30; i++)
		{
			WorldPoint spot = world.planner.pickScatterTile(new WorldPoint(2954, 3224, 0), world.census, rng);
			assertTrue("Scatter spot must be safe walkable land: " + spot,
				world.planner.isSafe(spot.getX(), spot.getY(), spot.getPlane()));
			assertFalse("Scatter spot must not be water: " + spot,
				world.mesh.isWater(spot.getX(), spot.getY(), spot.getPlane()));
			assertTrue("Each of the first 30 scattered pets must occupy its own region, duplicate at " + spot,
				usedRegions.add(spot.getRegionID()));
		}
	}

	@Test
	public void censusAndPersonalSpaceKeepPetsApart() throws Exception
	{
		WorldHarness world = WorldHarness.create();
		world.census.add(3222, 3218, 0);

		assertEquals(1, world.census.at(3222, 3218, 0));
		assertFalse(world.census.isSharedRegion(3222, 3218, 0));
		assertTrue("Occupied region should strongly discourage another pet entering",
			world.census.roominess(3222, 3218, 0) < 0.25f);

		world.census.add(3225, 3220, 0);
		assertTrue("Two pets in the same region should mark it shared",
			world.census.isSharedRegion(3222, 3218, 0));

		// Personal-space check around another pet at (3220, 3218)
		world.context.getOccupancy().addTo(RoamContext.tileKey(3220, 3218, 0), 1L);
		assertTrue("Tile 2 away from another pet should be avoided",
			world.context.nearAnotherPet(3222, 3218, 0, 3230, 3230));
		assertFalse("Tile 6 away from another pet should be free",
			world.context.nearAnotherPet(3226, 3218, 0, 3230, 3230));
	}

	@Test
	public void plannerGeneratesValidWalkableRoutes() throws Exception
	{
		WorldHarness world = WorldHarness.create();
		Random rng = new Random(7L);
		TransportMemory memory = new TransportMemory();

		WorldPoint at = new WorldPoint(3212, 3428, 0); // Varrock Square
		Itinerary plan = world.planner.plan(at, 100, rng, memory, world.context);
		assertNotNull("Planner should produce a route from Varrock Square", plan);
		WorldPoint dest = plan.destination();
		assertFalse("Destination must not be water", world.mesh.isWater(dest.getX(), dest.getY(), dest.getPlane()));
	}
}
