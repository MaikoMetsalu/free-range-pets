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

import java.awt.image.BufferedImage;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

/**
 * Manages {@link WorldMapPoint} markers for roaming pets on the world map.
 */
@Singleton
class PetMapManager
{
	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private PlaceNames placeNames;

	private final Map<RoamingPet, WorldMapPoint> points = new IdentityHashMap<>();
	private BufferedImage mapIcon;

	void startUp()
	{
		mapIcon = ImageUtil.loadImageResource(FreeRangePetsPlugin.class, "pet-map-icon.png");
	}

	void clear()
	{
		for (WorldMapPoint point : points.values())
		{
			worldMapPointManager.remove(point);
		}
		points.clear();
	}

	void update(List<RoamingPet> pets, FreeRangePetsConfig.MapMarkerMode mode, RoamingPet finding)
	{
		if (mapIcon == null)
		{
			return;
		}
		if (mode == FreeRangePetsConfig.MapMarkerMode.OFF && finding == null)
		{
			if (!points.isEmpty())
			{
				clear();
			}
			return;
		}

		Iterator<Map.Entry<RoamingPet, WorldMapPoint>> it = points.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<RoamingPet, WorldMapPoint> entry = it.next();
			RoamingPet pet = entry.getKey();
			if (!pets.contains(pet) || !shouldShow(pet, mode, finding))
			{
				worldMapPointManager.remove(entry.getValue());
				it.remove();
			}
		}

		for (RoamingPet pet : pets)
		{
			if (!shouldShow(pet, mode, finding))
			{
				continue;
			}
			WorldMapPoint marker = points.get(pet);
			String tooltip = pet.displayName() + " — " + placeNames.describe(pet.currentTile());
			if (marker == null)
			{
				marker = WorldMapPoint.builder()
					.worldPoint(pet.currentTile())
					.image(mapIcon)
					.tooltip(tooltip)
					.snapToEdge(pet == finding)
					.jumpOnClick(true)
					.build();
				if (pet.isCustomNamed() || pet == finding)
				{
					marker.setName(pet.displayName());
				}
				worldMapPointManager.add(marker);
				points.put(pet, marker);
			}
			else
			{
				marker.setWorldPoint(pet.currentTile());
				marker.setTooltip(tooltip);
				marker.setSnapToEdge(pet == finding);
				marker.setName(pet.isCustomNamed() || pet == finding ? pet.displayName() : null);
			}
		}
	}

	private static boolean shouldShow(RoamingPet pet, FreeRangePetsConfig.MapMarkerMode mode, RoamingPet finding)
	{
		if (pet.isPausedByFollower())
		{
			return false;
		}
		if (pet == finding)
		{
			return true;
		}
		if (mode == FreeRangePetsConfig.MapMarkerMode.ALL)
		{
			return true;
		}
		if (mode == FreeRangePetsConfig.MapMarkerMode.NAMED)
		{
			return pet.isCustomNamed() || pet.isFavourite();
		}
		return false;
	}
}
