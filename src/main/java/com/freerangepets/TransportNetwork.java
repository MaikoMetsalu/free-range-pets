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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Bundled network of OSRS doors, ladders, staircases, stiles, caves, ferries,
 * and shortcuts indexed by origin tile and 16x16 chunk.
 *
 * <p>Derived from Shortest Path (Skretzo) and Golems Don't Die (Varzeki),
 * both BSD 2-Clause.
 */
@Slf4j
@Singleton
class TransportNetwork
{
	private static final String RESOURCE = "transports.gz";

	static final int OP_EQ = 0;
	static final int OP_GT = 1;
	static final int OP_AND = 2;
	static final int OP_AT = 3;
	static final int OP_LT = 4;
	static final int OP_UNKNOWN = 5;
	static final int QUEST_UNKNOWN = -2;

	static final int CHAIN_HOPS = 6;
	private static final int STONE_HOP = 4;
	private static final int CHUNK_SHIFT = 4;

	private static final int MAGIC = -0x7472;
	private static final int VERSION = 2;
	private static final int FLAG_LEARN_FIRST = 1;

	private static final int[] NONE = new int[0];
	private static final Map<String, String> QUEST_ALIASES = Collections.singletonMap(
		"shadows of the storm", "shadow of the storm");

	private static Map<String, Quest> questsByName;

	private final List<WorldTransport> transports = new ArrayList<>();
	private final Map<Long, List<WorldTransport>> byOrigin = new HashMap<>();
	private final Map<Long, List<WorldTransport>> byChunk = new HashMap<>();
	private int revision;

	@FunctionalInterface
	interface Ground
	{
		boolean walkable(int x, int y, int plane);
	}

	void load()
	{
		if (!transports.isEmpty())
		{
			return;
		}

		try (InputStream raw = TransportNetwork.class.getResourceAsStream(RESOURCE))
		{
			if (raw == null)
			{
				log.warn("Bundled transport table missing from the jar");
				return;
			}
			try (GZIPInputStream gz = new GZIPInputStream(raw);
				DataInputStream data = new DataInputStream(gz))
			{
				int first = data.readInt();
				boolean flagged = first == MAGIC;
				if (flagged && data.readInt() != VERSION)
				{
					log.warn("Bundled transport table version unsupported");
					return;
				}
				int count = flagged ? data.readInt() : first;
				for (int i = 0; i < count; i++)
				{
					WorldTransport t = new WorldTransport(
						data.readShort() & 0xFFFF, data.readShort() & 0xFFFF, data.readByte(),
						data.readShort() & 0xFFFF, data.readShort() & 0xFFFF, data.readByte(),
						data.readShort() & 0xFFFF, data.readByte(), data.readInt(),
						parseSkills(data.readUTF()),
						parseQuests(data.readUTF()),
						parseConditions(data.readUTF()),
						parseConditions(data.readUTF())
					);
					if (flagged)
					{
						int flags = data.readByte();
						t.setLearnFirst((flags & FLAG_LEARN_FIRST) != 0);
					}
					transports.add(t);
				}
			}

			index();
			revision++;
			log.debug("Loaded {} transports across {} origin tiles", transports.size(), byOrigin.size());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Bundled transport table unreadable", e);
			transports.clear();
			byOrigin.clear();
			byChunk.clear();
		}
	}

	List<WorldTransport> all()
	{
		return Collections.unmodifiableList(transports);
	}

	List<WorldTransport> from(int x, int y)
	{
		List<WorldTransport> found = byOrigin.get(pack(x, y));
		return found == null ? Collections.emptyList() : found;
	}

	boolean hasOrigin(int x, int y)
	{
		return byOrigin.containsKey(pack(x, y));
	}

	boolean isStone(int x, int y, int plane)
	{
		for (WorldTransport t : from(x, y))
		{
			if (t.getFromPlane() == plane && t.getToPlane() == plane
				&& WorldLayout.span(t.getToX() - x, t.getToY() - y) <= STONE_HOP)
			{
				return true;
			}
		}
		return false;
	}

	boolean leadsToGround(int x, int y, int plane, int cameX, int cameY, int camePlane, int hops,
		Predicate<WorldTransport> usable, Ground ground)
	{
		if (ground.walkable(x, y, plane))
		{
			return true;
		}
		if (hops <= 0)
		{
			return false;
		}
		for (WorldTransport onward : from(x, y))
		{
			if (onward.getFromPlane() != plane || !usable.test(onward)
				|| (onward.getToX() == cameX && onward.getToY() == cameY && onward.getToPlane() == camePlane))
			{
				continue;
			}
			if (leadsToGround(onward.getToX(), onward.getToY(), onward.getToPlane(),
				x, y, plane, hops - 1, usable, ground))
			{
				return true;
			}
		}
		return false;
	}

	void near(int x, int y, int radius, List<WorldTransport> out)
	{
		for (int cx = (x - radius) >> CHUNK_SHIFT; cx <= (x + radius) >> CHUNK_SHIFT; cx++)
		{
			for (int cy = (y - radius) >> CHUNK_SHIFT; cy <= (y + radius) >> CHUNK_SHIFT; cy++)
			{
				List<WorldTransport> chunk = byChunk.get(pack(cx, cy));
				if (chunk == null)
				{
					continue;
				}
				for (WorldTransport t : chunk)
				{
					if (Math.abs(t.getFromX() - x) <= radius && Math.abs(t.getFromY() - y) <= radius)
					{
						out.add(t);
					}
				}
			}
		}
	}

	int size()
	{
		return transports.size();
	}

	int revision()
	{
		return revision;
	}

	private void index()
	{
		byOrigin.clear();
		byChunk.clear();
		for (int i = 0; i < transports.size(); i++)
		{
			WorldTransport t = transports.get(i);
			t.setIndex(i);
			byOrigin.computeIfAbsent(pack(t.getFromX(), t.getFromY()), k -> new ArrayList<>()).add(t);
			byChunk.computeIfAbsent(pack(t.getFromX() >> CHUNK_SHIFT, t.getFromY() >> CHUNK_SHIFT),
				k -> new ArrayList<>()).add(t);
		}
		byOrigin.replaceAll((k, v) -> Collections.unmodifiableList(v));
	}

	private static int[] parseSkills(String spec)
	{
		if (spec.isEmpty())
		{
			return NONE;
		}
		List<Integer> out = new ArrayList<>();
		for (String clause : spec.split(";"))
		{
			String[] parts = clause.trim().split("\\s+", 2);
			if (parts.length != 2)
			{
				continue;
			}
			try
			{
				int level = Integer.parseInt(parts[0]);
				Skill skill = Skill.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
				out.add(level);
				out.add(skill.ordinal());
			}
			catch (IllegalArgumentException e)
			{
				log.debug("Unknown skill requirement: {}", clause);
			}
		}
		return toArray(out);
	}

	private static int[] parseQuests(String spec)
	{
		if (spec.isEmpty())
		{
			return NONE;
		}
		if (questsByName == null)
		{
			Map<String, Quest> map = new HashMap<>();
			for (Quest quest : Quest.values())
			{
				map.put(quest.getName().toLowerCase(Locale.ROOT), quest);
			}
			questsByName = map;
		}
		List<Integer> out = new ArrayList<>();
		for (String name : spec.split(";"))
		{
			String key = name.trim().toLowerCase(Locale.ROOT);
			if (key.isEmpty())
			{
				continue;
			}
			Quest quest = questsByName.get(QUEST_ALIASES.getOrDefault(key, key));
			if (quest == null)
			{
				out.add(QUEST_UNKNOWN);
			}
			else
			{
				out.add(quest.ordinal());
			}
		}
		return toArray(out);
	}

	private static int[] parseConditions(String spec)
	{
		if (spec.isEmpty())
		{
			return NONE;
		}
		List<Integer> out = new ArrayList<>();
		for (String clause : spec.split(";"))
		{
			String c = clause.trim();
			if (c.isEmpty())
			{
				continue;
			}
			int op;
			int at;
			if ((at = c.indexOf('>')) >= 0)
			{
				op = OP_GT;
			}
			else if ((at = c.indexOf('<')) >= 0)
			{
				op = OP_LT;
			}
			else if ((at = c.indexOf('&')) >= 0)
			{
				op = OP_AND;
			}
			else if ((at = c.indexOf('@')) >= 0)
			{
				op = OP_AT;
			}
			else if ((at = c.indexOf('=')) >= 0)
			{
				op = OP_EQ;
			}
			else
			{
				out.add(-1);
				out.add(OP_UNKNOWN);
				out.add(0);
				continue;
			}
			try
			{
				out.add(Integer.parseInt(c.substring(0, at).trim()));
				out.add(op);
				out.add(Integer.parseInt(c.substring(at + 1).trim()));
			}
			catch (NumberFormatException e)
			{
				out.add(-1);
				out.add(OP_UNKNOWN);
				out.add(0);
			}
		}
		return toArray(out);
	}

	private static int[] toArray(List<Integer> values)
	{
		if (values.isEmpty())
		{
			return NONE;
		}
		int[] out = new int[values.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = values.get(i);
		}
		return out;
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	static long endpoints(int fx, int fy, int fz, int tx, int ty, int tz)
	{
		return ((long) (fx & 0x7FFF) << 49) | ((long) (fy & 0x7FFF) << 34)
			| ((long) (fz & 3) << 32) | ((long) (tx & 0x7FFF) << 17)
			| ((long) (ty & 0x7FFF) << 2) | (tz & 3);
	}
}
