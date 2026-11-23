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

import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * Checks whether a pet may use a {@link WorldTransport} based on the player's
 * real skill levels, finished quests, varbits, and varplayers.
 */
@Singleton
class PlayerAbilities
{
	private static final Skill[] SKILLS = Skill.values();
	private static final Quest[] QUESTS = Quest.values();
	private static final long QUEST_RECHECK_MILLIS = 5 * 60 * 1000L;

	@Inject
	private Client client;

	private final long[] questAskedAt = new long[QUESTS.length];
	private final boolean[] questDone = new boolean[QUESTS.length];

	boolean canUse(WorldTransport transport)
	{
		// Skip unverified inferred rows and instance-coordinate rows.
		if (transport == null || transport.isLearnFirst()
			|| transport.getFromX() >= 6400 || transport.getToX() >= 6400)
		{
			return false;
		}
		if (transport.isUnconditional())
		{
			return true;
		}
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		return hasSkills(transport.skills())
			&& hasQuests(transport.quests())
			&& conditionsMet(transport.varbits(), true)
			&& conditionsMet(transport.varps(), false);
	}

	void forgetQuests()
	{
		Arrays.fill(questAskedAt, 0L);
	}

	private boolean hasSkills(int[] requirements)
	{
		for (int i = 0; i + 1 < requirements.length; i += 2)
		{
			int level = requirements[i];
			int ordinal = requirements[i + 1];
			if (ordinal < 0 || ordinal >= SKILLS.length)
			{
				continue;
			}
			if (client.getRealSkillLevel(SKILLS[ordinal]) < level)
			{
				return false;
			}
		}
		return true;
	}

	private boolean hasQuests(int[] requirements)
	{
		for (int ordinal : requirements)
		{
			if (ordinal == TransportNetwork.QUEST_UNKNOWN)
			{
				return false;
			}
			if (ordinal < 0 || ordinal >= QUESTS.length)
			{
				continue;
			}
			if (!questFinished(ordinal))
			{
				return false;
			}
		}
		return true;
	}

	private boolean questFinished(int ordinal)
	{
		long now = System.currentTimeMillis();
		if (questAskedAt[ordinal] == 0 || now - questAskedAt[ordinal] >= QUEST_RECHECK_MILLIS)
		{
			questDone[ordinal] = QUESTS[ordinal].getState(client) == QuestState.FINISHED;
			questAskedAt[ordinal] = now;
		}
		return questDone[ordinal];
	}

	private boolean conditionsMet(int[] conditions, boolean varbit)
	{
		for (int i = 0; i + 2 < conditions.length; i += 3)
		{
			int id = conditions[i];
			int op = conditions[i + 1];
			int wanted = conditions[i + 2];
			if (op == TransportNetwork.OP_UNKNOWN || op == TransportNetwork.OP_AT)
			{
				return false;
			}
			int actual = varbit ? client.getVarbitValue(id) : client.getVarpValue(id);
			switch (op)
			{
				case TransportNetwork.OP_EQ:
					if (actual != wanted)
					{
						return false;
					}
					break;
				case TransportNetwork.OP_GT:
					if (actual <= wanted)
					{
						return false;
					}
					break;
				case TransportNetwork.OP_LT:
					if (actual >= wanted)
					{
						return false;
					}
					break;
				case TransportNetwork.OP_AND:
					if ((actual & wanted) == 0)
					{
						return false;
					}
					break;
				default:
					return false;
			}
		}
		return true;
	}
}
