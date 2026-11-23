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

import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;

/**
 * Immutable appearance and animation descriptor captured from a live POH
 * Menagerie pet NPC and rebuilt from the cache on subsequent logins.
 */
@Value
class PetSnapshot
{
	int npcId;
	String name;
	int[] modelIds;
	short[] recolourFrom;
	short[] recolourTo;
	int widthScale;
	int heightScale;
	int size;
	int idlePoseAnimation;
	int walkAnimation;

	static PetSnapshot fromNpc(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		if (comp == null)
		{
			return null;
		}

		String npcName = npc.getName() != null && !npc.getName().isEmpty()
			? npc.getName()
			: comp.getName();
		return build(comp, npcName, npc.getIdlePoseAnimation(), npc.getWalkAnimation());
	}

	static PetSnapshot restore(Client client, int npcId, String fallbackName,
		int idlePoseAnimation, int walkAnimation)
	{
		if (client == null || npcId < 0)
		{
			return null;
		}
		NPCComposition comp = client.getNpcDefinition(npcId);
		if (comp == null)
		{
			return null;
		}
		if (comp.getConfigs() != null)
		{
			NPCComposition transformed = comp.transform();
			if (transformed != null)
			{
				comp = transformed;
			}
		}

		String name = comp.getName();
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			name = fallbackName != null && !fallbackName.isEmpty() ? fallbackName : "Pet";
		}
		return build(comp, name, idlePoseAnimation, walkAnimation);
	}

	private static PetSnapshot build(NPCComposition comp, String name,
		int idlePoseAnimation, int walkAnimation)
	{
		int[] models = comp.getModels();
		if (models == null || models.length == 0)
		{
			return null;
		}
		short[] from = comp.getColorToReplace();
		short[] to = comp.getColorToReplaceWith();

		return new PetSnapshot(
			comp.getId(),
			name == null ? "Pet" : name,
			models.clone(),
			from == null ? new short[0] : from.clone(),
			to == null ? new short[0] : to.clone(),
			comp.getWidthScale(),
			comp.getHeightScale(),
			Math.max(1, comp.getSize()),
			idlePoseAnimation,
			walkAnimation
		);
	}
}
