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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * Adds right-click {@code Examine} and Shift-right-click {@code Set Name} menu
 * entries when hovering a visible roaming pet.
 */
@Singleton
class PetMenu
{
	private static final String NPC_COLOUR = "<col=ffff00>";
	private static final int MENU_LIMIT = 500;

	/** The entries the client offers only with the mouse over the scene. */
	private static final Set<MenuAction> SCENE_ENTRIES = EnumSet.of(MenuAction.WALK, MenuAction.SET_HEADING,
		MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_NPC, MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
		MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION,
		MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION,
		MenuAction.WIDGET_TARGET_ON_PLAYER, MenuAction.GROUND_ITEM_FIRST_OPTION,
		MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
		MenuAction.GROUND_ITEM_FIFTH_OPTION, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
		MenuAction.WORLD_ENTITY_FIRST_OPTION, MenuAction.WORLD_ENTITY_SECOND_OPTION, MenuAction.WORLD_ENTITY_THIRD_OPTION,
		MenuAction.WORLD_ENTITY_FOURTH_OPTION, MenuAction.WORLD_ENTITY_FIFTH_OPTION, MenuAction.EXAMINE_OBJECT,
		MenuAction.EXAMINE_NPC, MenuAction.EXAMINE_ITEM_GROUND, MenuAction.EXAMINE_WORLD_ENTITY);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	private Consumer<RoamingPet> onRenamed = pet ->
	{
	};

	void setOnRenamed(Consumer<RoamingPet> onRenamed)
	{
		this.onRenamed = onRenamed;
	}

	void addEntries(List<RoamingPet> drawnPets)
	{
		if (client.isMenuOpen() || drawnPets.isEmpty())
		{
			return;
		}
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0)
		{
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		boolean overScene = false;
		for (MenuEntry entry : entries)
		{
			if (SCENE_ENTRIES.contains(entry.getType()))
			{
				overScene = true;
				break;
			}
		}
		if (!overScene)
		{
			return;
		}

		boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		int room = MENU_LIMIT - entries.length;
		for (RoamingPet pet : drawnPets)
		{
			FakePet renderer = pet.getRenderer();
			if (renderer == null || !renderer.isUnder(mouse.getX(), mouse.getY()))
			{
				continue;
			}
			int needed = shift ? 2 : 1;
			if (room < needed)
			{
				break;
			}
			room -= needed;
			addEntry(pet, shift);
		}
	}

	private void addEntry(RoamingPet pet, boolean shift)
	{
		String target = NPC_COLOUR + pet.displayName() + "</col>";
		client.getMenu().createMenuEntry(1)
			.setOption("Examine")
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> client.addChatMessage(
				ChatMessageType.NPC_EXAMINE,
				"",
				"Your free-range " + pet.getSnapshot().getName() + ", out exploring Gielinor.",
				null
			));

		if (shift)
		{
			client.getMenu().createMenuEntry(1)
				.setOption("Set Name")
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> promptForName(pet));
		}
	}

	private void promptForName(RoamingPet pet)
	{
		String current = pet.getNickname();
		chatboxPanelManager.openTextInput("Name this pet")
			.value(current == null ? "" : current)
			.onDone((String text) -> clientThread.invoke(() ->
			{
				String trimmed = text.trim();
				pet.setNickname(trimmed.isEmpty() ? null : trimmed);
				onRenamed.accept(pet);
			}))
			.build();
	}
}
