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

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FreeRangePetsConfig.GROUP)
public interface FreeRangePetsConfig extends Config
{
	String GROUP = "freerangepets";
	String SAVED_PETS_KEY = "savedPets";

	enum MapMarkerMode
	{
		OFF("Off"),
		NAMED("Named & Starred"),
		ALL("All");

		private final String label;

		MapMarkerMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "hidePetsInPoh",
		name = "Hide pets in POH",
		description = "Hides the original roaming pets inside your POH Menagerie while they are out exploring Gielinor.",
		position = 0
	)
	default boolean hidePetsInPoh()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNameplates",
		name = "Show pet names",
		description = "Displays a pet's name above its head when it is in view.",
		position = 1
	)
	default boolean showNameplates()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "nameplateColor",
		name = "Name colour",
		description = "Colour and opacity of pet names displayed above their heads.",
		position = 2
	)
	default Color nameplateColor()
	{
		return new Color(0xFFE700);
	}

	@ConfigItem(
		keyName = "mapMarkers",
		name = "World map markers",
		description = "Shows your roaming pets on the world map.",
		position = 3
	)
	default MapMarkerMode mapMarkers()
	{
		return MapMarkerMode.ALL;
	}

	@ConfigItem(
		keyName = "showMinimapDots",
		name = "Show minimap dots",
		description = "Shows nearby roaming pets as yellow NPC dots on the minimap.",
		position = 4
	)
	default boolean showMinimapDots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shortestPathOnFind",
		name = "Path when finding pet",
		description = "Draws a route with the Shortest Path plugin when you click Find on a pet.",
		position = 5
	)
	default boolean shortestPathOnFind()
	{
		return true;
	}
}
