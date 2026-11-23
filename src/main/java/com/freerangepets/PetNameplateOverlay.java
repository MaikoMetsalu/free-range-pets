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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws optional nameplates above visible roaming pets and a bobbing yellow
 * arrow over whichever pet the player is currently tracking with Find.
 */
@Singleton
class PetNameplateOverlay extends Overlay
{
	private static final int NAME_GAP = 16;
	private static final int ARROW_GAP = 28;
	private static final int ARROW_BOB = 5;
	private static final int ARROW_HALF_WIDTH = 7;
	private static final int ARROW_HEIGHT = 12;
	private static final Color ARROW_COLOUR = new Color(0xFFE700);

	private final Client client;
	private final FreeRangePetsPlugin plugin;
	private final FreeRangePetsConfig config;

	@Inject
	PetNameplateOverlay(Client client, FreeRangePetsPlugin plugin, FreeRangePetsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}

		boolean showNames = config.showNameplates();
		Color colour = config.nameplateColor();
		RoamingPet finding = plugin.getFinding();

		graphics.setFont(FontManager.getRunescapeSmallFont());

		for (RoamingPet pet : plugin.getDrawnPets())
		{
			FakePet renderer = pet.getRenderer();
			if (renderer == null)
			{
				continue;
			}
			LocalPoint local = new LocalPoint(renderer.getX(), renderer.getY(), wv);
			int zOffset = renderer.getModelHeight() + NAME_GAP;

			if (showNames)
			{
				String label = pet.displayName();
				Point textPoint = Perspective.getCanvasTextLocation(client, graphics, local, label, zOffset);
				if (textPoint != null)
				{
					OverlayUtil.renderTextLocation(graphics, textPoint, label, colour);
				}
			}

			if (pet == finding)
			{
				drawFindArrow(graphics, local, renderer.getModelHeight() + ARROW_GAP);
			}
		}
		return null;
	}

	private void drawFindArrow(Graphics2D graphics, LocalPoint local, int zOffset)
	{
		Point anchor = Perspective.localToCanvas(client, local, client.getTopLevelWorldView().getPlane(), zOffset);
		if (anchor == null)
		{
			return;
		}
		int bob = (int) Math.round(Math.sin(client.getGameCycle() * 0.15) * ARROW_BOB);
		int tipY = anchor.getY() + bob;
		int x = anchor.getX();

		Polygon arrow = new Polygon(
			new int[]{x, x - ARROW_HALF_WIDTH, x + ARROW_HALF_WIDTH},
			new int[]{tipY, tipY - ARROW_HEIGHT, tipY - ARROW_HEIGHT},
			3
		);
		graphics.setColor(Color.BLACK);
		graphics.drawPolygon(arrow);
		graphics.setColor(ARROW_COLOUR);
		graphics.fillPolygon(arrow);
	}
}
