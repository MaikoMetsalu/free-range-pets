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

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * Builds and caches unposed rest-pose {@link Model}s and {@link Animation}s for
 * roaming pets directly from the game cache.
 */
@Slf4j
@Singleton
class PetModelFactory
{
	private static final int BASE_AMBIENT = 64;
	private static final int BASE_CONTRAST = 850;
	private static final int LIGHT_X = -30;
	private static final int LIGHT_Y = -50;
	private static final int LIGHT_Z = -30;
	private static final int UNSCALED = 128;

	@Inject
	private Client client;

	private final Map<Integer, Model> models = new HashMap<>();
	private final Map<Integer, Animation> animations = new HashMap<>();

	Model modelFor(PetSnapshot snapshot)
	{
		if (snapshot == null)
		{
			return null;
		}
		Model cached = models.get(snapshot.getNpcId());
		if (cached != null)
		{
			return cached;
		}
		Model built = build(snapshot);
		if (built != null)
		{
			models.put(snapshot.getNpcId(), built);
		}
		return built;
	}

	Animation animationFor(int animationId)
	{
		if (animationId < 0)
		{
			return null;
		}
		if (animations.containsKey(animationId))
		{
			return animations.get(animationId);
		}
		Animation loaded = client.loadAnimation(animationId);
		animations.put(animationId, loaded);
		return loaded;
	}

	void clear()
	{
		models.clear();
		animations.clear();
	}

	private Model build(PetSnapshot snapshot)
	{
		try
		{
			int[] ids = snapshot.getModelIds();
			ModelData[] parts = new ModelData[ids.length];
			for (int i = 0; i < ids.length; i++)
			{
				ModelData part = client.loadModelData(ids[i]);
				if (part == null)
				{
					log.debug("Pet model part {} not yet loaded for npc {}", ids[i], snapshot.getNpcId());
					return null;
				}
				parts[i] = part;
			}

			ModelData merged = parts.length == 1 ? parts[0] : client.mergeModels(parts, parts.length);
			if (merged == null)
			{
				return null;
			}

			merged = merged.cloneColors().cloneVertices();

			short[] from = snapshot.getRecolourFrom();
			short[] to = snapshot.getRecolourTo();
			for (int i = 0; i < Math.min(from.length, to.length); i++)
			{
				merged.recolor(from[i], to[i]);
			}

			int width = snapshot.getWidthScale() <= 0 ? UNSCALED : snapshot.getWidthScale();
			int height = snapshot.getHeightScale() <= 0 ? UNSCALED : snapshot.getHeightScale();
			if (width != UNSCALED || height != UNSCALED)
			{
				merged.scale(width, height, width);
			}

			Model model = merged.light(BASE_AMBIENT, BASE_CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
			if (model != null)
			{
				model.calculateBoundsCylinder();
			}
			return model;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not build pet model for npc {}", snapshot.getNpcId(), e);
			return null;
		}
	}
}
