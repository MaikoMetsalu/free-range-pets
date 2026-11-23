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

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/**
 * Serialises and restores the account's roaming pet roster using RuneLite's
 * injected {@link Gson} instance. Only identity, position, and animation IDs
 * are stored; models and colours are re-read from the cache on login.
 */
@Slf4j
@Singleton
class PetStore
{
	private static final Type LIST_TYPE = new TypeToken<List<SavedPet>>()
	{
	}.getType();

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class SavedPet
	{
		long id;
		int npcId;
		String name;
		String nickname;
		boolean favourite;
		int worldX;
		int worldY;
		int plane;
		int orientation;
		int idleAnimation;
		int walkAnimation;
		int runAnimation;
	}

	String serialise(List<RoamingPet> pets)
	{
		List<SavedPet> saved = new ArrayList<>(pets.size());
		for (RoamingPet pet : pets)
		{
			WorldPoint tile = pet.saveTile();
			PetSnapshot snap = pet.getSnapshot();
			saved.add(new SavedPet(
				pet.getId(),
				snap.getNpcId(),
				snap.getName(),
				pet.getNickname(),
				pet.isFavourite(),
				tile.getX(),
				tile.getY(),
				tile.getPlane(),
				pet.getOrientation(),
				snap.getIdlePoseAnimation(),
				snap.getWalkAnimation(),
				snap.getRunAnimation()
			));
		}
		return gson.toJson(saved, LIST_TYPE);
	}

	List<SavedPet> deserialise(String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		try
		{
			List<SavedPet> parsed = gson.fromJson(json, LIST_TYPE);
			return parsed == null ? Collections.emptyList() : parsed;
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Could not parse saved pets; starting fresh", e);
			return Collections.emptyList();
		}
	}

	RoamingPet revive(SavedPet saved)
	{
		PetSnapshot snapshot = PetSnapshot.restore(
			client,
			saved.getNpcId(),
			saved.getName(),
			saved.getIdleAnimation(),
			saved.getWalkAnimation(),
			saved.getRunAnimation()
		);
		if (snapshot == null)
		{
			log.debug("Saved pet npc {} could not be restored from cache", saved.getNpcId());
			return null;
		}
		WorldPoint at = new WorldPoint(saved.getWorldX(), saved.getWorldY(), saved.getPlane());
		RoamingPet pet = new RoamingPet(saved.getId(), snapshot, at, saved.getOrientation());
		pet.setNickname(saved.getNickname());
		pet.setFavourite(saved.isFavourite());
		return pet;
	}
}
