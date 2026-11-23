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

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.ObjectID;

/**
 * Detects when the player is inside their own Player-Owned House Menagerie and
 * identifies menagerie pet NPCs as well as the player's currently active follower.
 */
@Singleton
class PetDetector
{
	private static final Set<Integer> MENAGERIE_OBJECTS = ImmutableSet.of(
		ObjectID.POH_MENAGERIE_PETHOUSE_1,
		ObjectID.POH_MENAGERIE_PETHOUSE_2,
		ObjectID.POH_MENAGERIE_PETHOUSE_3,
		ObjectID.POH_MENAGERIE_PETHOUSE_4,
		ObjectID.POH_MENAGERIE_PETHOUSE_5,
		ObjectID.POH_MENAGERIE_PETHOUSE_6,
		ObjectID.POH_MENAGERIE_PETLIST_1,
		ObjectID.POH_MENAGERIE_PETFEEDER_1,
		ObjectID.POH_MENAGERIE_PETFEEDER_2,
		ObjectID.POH_MENAGERIE_PETFEEDER_3
	);

	private boolean visitedOtherHouse;
	private boolean menageriePresent;

	void onSceneLoading()
	{
		menageriePresent = false;
	}

	void onLeftInstance()
	{
		visitedOtherHouse = false;
		menageriePresent = false;
	}

	void onChatMessage(String message)
	{
		if (message == null)
		{
			return;
		}
		String lower = message.toLowerCase();
		if (lower.contains("friend's house") || lower.contains("other player's house"))
		{
			visitedOtherHouse = true;
		}
		else if (lower.contains("welcome to your house"))
		{
			visitedOtherHouse = false;
		}
	}

	void onGameObjectSpawned(GameObject object)
	{
		if (object != null && MENAGERIE_OBJECTS.contains(object.getId()))
		{
			menageriePresent = true;
		}
	}

	/**
	 * Scans the loaded instance scene for a POH Menagerie pet house / pet list so
	 * enabling the plugin while already standing inside a POH still detects it.
	 */
	void scanScene(WorldView worldView)
	{
		if (worldView == null || !worldView.isInstance())
		{
			menageriePresent = false;
			return;
		}
		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return;
		}
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null)
		{
			return;
		}
		for (int p = 0; p < tiles.length; p++)
		{
			for (int x = 0; x < tiles[p].length; x++)
			{
				for (int y = 0; y < tiles[p][x].length; y++)
				{
					Tile tile = tiles[p][x][y];
					if (tile == null)
					{
						continue;
					}
					GameObject[] objects = tile.getGameObjects();
					if (objects == null)
					{
						continue;
					}
					for (GameObject obj : objects)
					{
						if (obj != null && MENAGERIE_OBJECTS.contains(obj.getId()))
						{
							menageriePresent = true;
							return;
						}
					}
				}
			}
		}
	}

	/**
	 * True when the player is inside their own POH containing a Menagerie.
	 */
	boolean isInOwnMenagerie(Client client)
	{
		WorldView wv = client.getTopLevelWorldView();
		return wv != null && wv.isInstance() && menageriePresent && !visitedOtherHouse;
	}

	/**
	 * True if {@code npc} is a roaming pet inside the POH Menagerie (excluding the
	 * player's currently following pet and any servants).
	 */
	boolean isMenageriePet(Client client, NPC npc)
	{
		if (npc == null || npc == client.getFollower())
		{
			return false;
		}
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		if (comp == null || comp.getCombatLevel() > 0)
		{
			return false;
		}
		return comp.isFollower() || hasPickUpAction(comp);
	}

	/**
	 * True when the player's currently following pet matches the given roaming pet,
	 * so the roaming copy pauses rather than duplicating the active follower.
	 */
	boolean isActiveFollower(Client client, RoamingPet pet)
	{
		NPC follower = client.getFollower();
		if (follower == null)
		{
			return false;
		}
		if (follower.getId() == pet.getSnapshot().getNpcId())
		{
			return true;
		}
		String followerName = follower.getName();
		String petName = pet.getSnapshot().getName();
		return followerName != null && petName != null && followerName.equalsIgnoreCase(petName);
	}

	private static boolean hasPickUpAction(NPCComposition comp)
	{
		String[] actions = comp.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Pick-up".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}
}
