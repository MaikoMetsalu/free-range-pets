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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Resolves any Gielinor tile to a human-readable place name using curated
 * region names (from Location Display by trinhc2, BSD 2-Clause) and world map
 * labels (bundled by Golems Don't Die by Varzeki, BSD 2-Clause).
 */
@Slf4j
@Singleton
class PlaceNames
{
	private static final String RESOURCE = "places.gz";
	private static final int VERSION = 2;
	private static final int LABEL_REACH = 48;
	private static final int SIZE_WORTH = 24;

	private int[][] labels = new int[0][];
	private String[] labelNames = new String[0];
	private final Map<Integer, String> regions = new HashMap<>();
	private boolean loaded;

	void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;

		try (InputStream in = PlaceNames.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("Bundled place names missing from the jar");
				return;
			}
			try (DataInputStream data = new DataInputStream(new GZIPInputStream(in)))
			{
				int version = data.readInt();
				if (version != VERSION)
				{
					log.warn("Place names version {} unsupported (expected {})", version, VERSION);
					return;
				}
				int count = data.readInt();
				labels = new int[count][];
				labelNames = new String[count];
				for (int i = 0; i < count; i++)
				{
					labelNames[i] = data.readUTF();
					labels[i] = new int[]{
						data.readUnsignedShort(),
						data.readUnsignedShort(),
						data.readByte(),
						data.readUnsignedByte()
					};
				}
				int named = data.readInt();
				for (int i = 0; i < named; i++)
				{
					String name = data.readUTF();
					regions.put(data.readUnsignedByte() << 8 | data.readUnsignedByte(), name);
				}
				log.debug("Loaded {} map labels and {} named regions", count, named);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Place names unreadable", e);
		}
	}

	/**
	 * Formats a tile's whereabouts in plain English, e.g. {@code "Varrock"},
	 * {@code "Falador, upstairs"}, {@code "Beneath Edgeville"}, or
	 * {@code "Near Catherby"}.
	 */
	String describe(WorldPoint at)
	{
		if (at == null)
		{
			return "Gielinor";
		}
		String place = nameFor(at.getX(), at.getY(), at.getPlane());
		if (place != null)
		{
			return at.getPlane() > 0 ? place + ", upstairs" : place;
		}

		String upstairs = at.getPlane() > 0 ? ", upstairs" : "";
		if (WorldLayout.isCave(at.getY()))
		{
			int above = WorldLayout.groundAbove(at.getY());
			String over = above < 0 ? null : nameFor(at.getX(), above, 0);
			if (over == null && above >= 0)
			{
				over = nearest(at.getX(), above, 0);
			}
			if (over != null)
			{
				return "Beneath " + over;
			}
		}

		String near = nearest(at.getX(), at.getY(), at.getPlane());
		if (near != null)
		{
			return "Near " + near + upstairs;
		}
		return at.getPlane() > 0 ? "Somewhere upstairs" : "Gielinor";
	}

	String nameFor(int x, int y, int plane)
	{
		String region = regions.get((x >> 6) << 8 | (y >> 6));
		if (region != null)
		{
			return region;
		}

		String nearest = null;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < labels.length; i++)
		{
			int[] label = labels[i];
			int distance = WorldLayout.span(label[0] - x, label[1] - y);
			if (distance > LABEL_REACH)
			{
				continue;
			}
			int score = distance - label[3] * SIZE_WORTH + (label[2] == plane ? 0 : LABEL_REACH);
			if (score < best)
			{
				best = score;
				nearest = labelNames[i];
			}
		}
		return nearest;
	}

	String nearest(int x, int y, int plane)
	{
		String nearest = null;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < labels.length; i++)
		{
			int[] label = labels[i];
			int score = WorldLayout.span(label[0] - x, label[1] - y) + (label[2] == plane ? 0 : LABEL_REACH);
			if (score < best)
			{
				best = score;
				nearest = labelNames[i];
			}
		}
		return nearest;
	}
}
