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

import java.awt.Shape;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Client-side renderer for a {@link RoamingPet} while it is inside the loaded
 * scene. Position and movement live on {@link RoamingPet}; this class only syncs
 * transforms and animates the model.
 */
class FakePet extends RuneLiteObjectController
{
	private final Client client;
	private final RoamingPet pet;
	private final Model baseModel;
	private final PetModelFactory modelFactory;
	private final AnimationController animation;

	private int loadedAnimationId = Integer.MIN_VALUE;

	FakePet(Client client, RoamingPet pet, Model baseModel, PetModelFactory modelFactory)
	{
		this.client = client;
		this.pet = pet;
		this.baseModel = baseModel;
		this.modelFactory = modelFactory;
		this.animation = new AnimationController(client, -1);
		this.animation.setOnFinished(AnimationController::loop);

		syncTransform();
		applyPose();
	}

	int getModelHeight()
	{
		return baseModel.getModelHeight();
	}

	@Override
	public void tick(int ticksSinceLastFrame)
	{
		syncTransform();
		applyPose();
		animation.tick(ticksSinceLastFrame);
	}

	@Override
	public Model getModel()
	{
		if (animation.getAnimation() == null)
		{
			return baseModel;
		}
		Model posed = animation.animate(baseModel);
		return posed == null ? baseModel : posed;
	}

	boolean isUnder(int mouseX, int mouseY)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return false;
		}
		try
		{
			Shape box = Perspective.getClickbox(client, wv, getModel(), getOrientation(), getX(), getY(), getZ());
			return box != null && box.contains(mouseX, mouseY);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private void syncTransform()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		int localX = pet.getFineX() - wv.getBaseX() * RoamingPet.TILE;
		int localY = pet.getFineY() - wv.getBaseY() * RoamingPet.TILE;
		// Multi-tile NPCs are centred over their size x size footprint.
		int sizeOffset = (pet.getSnapshot().getSize() - 1) * (RoamingPet.TILE / 2);
		int drawX = localX + sizeOffset;
		int drawY = localY + sizeOffset;

		setX(drawX);
		setY(drawY);
		setWorldView(wv.getId());
		setLevel(pet.getPlane());
		setOrientation(pet.getOrientation());

		if (RoamingPet.isInScene(wv, drawX, drawY))
		{
			// Heights grow downwards, so a hop's lift is taken off the ground height.
			setZ(Perspective.getTileHeight(client, new LocalPoint(drawX, drawY, wv), pet.getPlane()) - pet.jumpArc());
		}
	}

	private void applyPose()
	{
		int wanted = pet.currentPoseAnimation();
		if (wanted != loadedAnimationId || animation.getAnimation() == null)
		{
			loadedAnimationId = wanted;
			animation.setAnimation(modelFactory.animationFor(wanted));
		}
	}
}
