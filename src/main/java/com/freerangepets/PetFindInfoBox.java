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
import java.awt.image.BufferedImage;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * InfoBox displayed while tracking a roaming pet with Find, showing distance in
 * tiles and offering a right-click {@code Stop} option.
 */
class PetFindInfoBox extends InfoBox
{
	static final String STOP = "Stop";

	private String petName = "";
	private String location = "";
	private int distance = -1;

	PetFindInfoBox(BufferedImage image, FreeRangePetsPlugin plugin)
	{
		super(image, plugin);
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_INFOBOX, STOP, "Find pet"));
	}

	void update(String petName, String location, int distance)
	{
		this.petName = petName;
		this.location = location;
		this.distance = distance;
	}

	@Override
	public String getText()
	{
		return distance >= 0 ? distance + "t" : "?";
	}

	@Override
	public Color getTextColor()
	{
		return Color.WHITE;
	}

	@Override
	public String getTooltip()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Finding ").append(petName);
		if (location != null && !location.isEmpty())
		{
			sb.append("</br>").append(location);
		}
		if (distance >= 0)
		{
			sb.append(" (").append(distance).append(" tiles away)");
		}
		return sb.toString();
	}
}
