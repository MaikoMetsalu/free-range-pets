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
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PetStoreTest
{
	@Test
	public void serialisesAndDeserialisesPetsCleanly() throws Exception
	{
		PetStore store = createStore();

		PetSnapshot snap = new PetSnapshot(
			6635, "Baby mole", new int[]{1234}, new short[0], new short[0],
			128, 128, 1, 3310, 3311
		);
		RoamingPet pet = new RoamingPet(123456L, snap, new WorldPoint(3222, 3218, 0), 512);
		pet.setNickname("Moley");
		pet.setFavourite(true);

		String json = store.serialise(Collections.singletonList(pet));
		List<PetStore.SavedPet> decoded = store.deserialise(json);

		assertEquals(1, decoded.size());
		PetStore.SavedPet saved = decoded.get(0);
		assertEquals(123456L, saved.getId());
		assertEquals(6635, saved.getNpcId());
		assertEquals("Baby mole", saved.getName());
		assertEquals("Moley", saved.getNickname());
		assertTrue(saved.isFavourite());
		assertEquals(3222, saved.getWorldX());
		assertEquals(3218, saved.getWorldY());
		assertEquals(0, saved.getPlane());
		assertEquals(512, saved.getOrientation());
	}

	@Test
	public void ignoresUnknownFieldsAndMalformedJson() throws Exception
	{
		PetStore store = createStore();

		String unknownField = "[{\"id\":7,\"npcId\":6635,\"name\":\"Baby mole\",\"worldX\":2954,\"worldY\":3224,\"plane\":0,\"nodeId\":\"rimmington\"}]";
		List<PetStore.SavedPet> decoded = store.deserialise(unknownField);
		assertEquals(1, decoded.size());
		assertEquals(2954, decoded.get(0).getWorldX());

		assertTrue(store.deserialise(null).isEmpty());
		assertTrue(store.deserialise("").isEmpty());
		assertTrue(store.deserialise("{not valid json").isEmpty());
	}

	private static PetStore createStore() throws Exception
	{
		PetStore store = new PetStore();
		Field gsonField = PetStore.class.getDeclaredField("gson");
		gsonField.setAccessible(true);
		gsonField.set(store, new Gson());
		return store;
	}
}
