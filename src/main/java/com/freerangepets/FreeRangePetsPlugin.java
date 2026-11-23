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

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HintArrowType;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.InfoBoxMenuClicked;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

/**
 * Lets your Player-Owned House Menagerie pets roam freely across Gielinor as
 * purely cosmetic client-side companions using tile-accurate world mesh
 * navigation, real doors/ladders/stairs/shortcuts, anti-crowding dispersion,
 * and a sidebar tracker.
 */
@Slf4j
@PluginDescriptor(
	name = "Free Range Pets",
	description = "Let your POH menagerie pets roam freely across Gielinor.",
	tags = {"pets", "poh", "menagerie", "cosmetic", "roaming", "exploration"}
)
public class FreeRangePetsPlugin extends Plugin
{
	private static final int SAVE_INTERVAL_TICKS = 200;
	private static final int SAVE_SOON_TICKS = 2;
	private static final int SIDEBAR_REFRESH_TICKS = 3;
	private static final int CENSUS_REFRESH_TICKS = 10;
	private static final int FAR_CHECK_INTERVAL_TICKS = 5;
	private static final int NEAR_DISTANCE_TILES = 128;

	/** Off-screen legs planned per tick: a whole roster going out of view at once must not plan in one frame. */
	private static final int FAR_PLANS_PER_TICK = 6;

	/** Path search cost allowed per frame for visible pets, and per tick for the watchdog. */
	private static final int SEARCH_BUDGET_PER_FRAME = 8;
	private static final int WATCHDOG_SEARCH_BUDGET = 8;

	/** A pet near the player now and then stops to look at them, unless there are many pets about. */
	private static final int WATCHING_CROWD = 5;
	private static final int WATCH_TILES = 12;
	private static final float WATCH_CHANCE = 0.01f;
	private static final int WATCH_TICKS = 8;
	private static final int FOUND_DISTANCE_TILES = 6;
	private static final int REROUTE_TILES = 12;
	private static final int REROUTE_TICKS = 10;
	private static final int CLUMP_DETECT_TILES = 4;

	private static final String SHORTEST_PATH = "shortestpath";
	private static final String SHORTEST_PATH_ROUTE = "path";
	private static final String SHORTEST_PATH_CLEAR = "clear";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FreeRangePetsConfig config;

	@Inject
	private RenderCallbackManager renderCallbacks;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private PetDetector detector;

	@Inject
	private PetModelFactory modelFactory;

	@Inject
	private WorldMesh worldMesh;

	@Inject
	private LiveCollisionMap collisionMap;

	@Inject
	private TransportNetwork transportNetwork;

	@Inject
	private PlayerAbilities abilities;

	@Inject
	private PlaceNames placeNames;

	@Inject
	private PetCensus census;

	@Inject
	private PetPathfinder pathfinder;

	@Inject
	private RoamPlanner planner;

	@Inject
	private PetStore store;

	@Inject
	private PetMenu petMenu;

	@Inject
	private PetMapManager mapManager;

	@Inject
	private PetNameplateOverlay nameplateOverlay;

	@Inject
	private PetMinimapOverlay minimapOverlay;

	private final List<RoamingPet> pets = new ArrayList<>();
	private final List<RoamingPet> drawnPets = new ArrayList<>();
	private final List<PetStore.SavedPet> pendingRestore = new ArrayList<>();
	private final Set<Integer> hiddenPohNpcIndices = new HashSet<>();
	private final TileMap occupancy = new TileMap(64);
	private final Random scatterRandom = new Random();
	private final Random moods = new Random();

	private RoamContext roamContext;
	private FreeRangePetsPanel panel;
	private NavigationButton navButton;
	private PetFindInfoBox findBox;
	private BufferedImage pluginIcon;

	private volatile boolean running;
	private boolean callbackRegistered;
	private String rosterAccount;

	private RoamingPet finding;
	private WorldPoint arrowAt;
	private WorldPoint routedTo;
	private int routedAt;

	private int lastGameCycle = -1;
	private int lastTick = -1;
	private int saveAt = -1;
	private int ticksSinceSave;
	private int ticksSinceSidebar;
	private int ticksSinceCensus;
	private boolean rosterDirty;
	private long seedCounter;

	private final RenderCallback hidePohPetsCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			if (!config.hidePetsInPoh() || hiddenPohNpcIndices.isEmpty() || !(renderable instanceof NPC))
			{
				return true;
			}
			return !hiddenPohNpcIndices.contains(((NPC) renderable).getIndex());
		}
	};

	@Provides
	FreeRangePetsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FreeRangePetsConfig.class);
	}

	List<RoamingPet> getDrawnPets()
	{
		return drawnPets;
	}

	RoamingPet getFinding()
	{
		return finding;
	}

	@Override
	protected void startUp()
	{
		running = true;
		pluginIcon = ImageUtil.loadImageResource(FreeRangePetsPlugin.class, "pet-icon.png");

		worldMesh.load();
		transportNetwork.load();
		placeNames.load();
		worldMesh.admitTransportEnds(transportNetwork.all());
		worldMesh.linkSpaces(WaysBack.MAINLAND_X, WaysBack.MAINLAND_Y, 0);

		roamContext = new RoamContext(collisionMap, pathfinder, transportNetwork, abilities);
		roamContext.setCensus(census);
		roamContext.setPlanner(planner);
		roamContext.setOccupancy(occupancy);

		mapManager.startUp();

		petMenu.setOnRenamed(pet ->
		{
			rosterDirty = true;
			saveSoon();
			refreshSidebar();
		});

		panel = new FreeRangePetsPanel(
			pet -> clientThread.invoke(() -> toggleFind(pet)),
			pet -> clientThread.invoke(() ->
			{
				pet.setFavourite(!pet.isFavourite());
				rosterDirty = true;
				saveSoon();
				refreshSidebar();
			}),
			pet -> clientThread.invoke(() -> removePet(pet)),
			(pet, name) -> clientThread.invoke(() ->
			{
				pet.setNickname(name);
				rosterDirty = true;
				saveSoon();
				refreshSidebar();
			}),
			() -> clientThread.invoke(this::scatterAllPets)
		);

		navButton = NavigationButton.builder()
			.tooltip("Free Range Pets")
			.icon(pluginIcon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		overlayManager.add(minimapOverlay);
		overlayManager.add(nameplateOverlay);

		renderCallbacks.register(hidePohPetsCallback);
		callbackRegistered = true;
		lastGameCycle = -1;

		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				detector.scanScene(client.getTopLevelWorldView());
				collisionMap.sceneChanged();
				loadAccount();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		if (callbackRegistered)
		{
			renderCallbacks.unregister(hidePohPetsCallback);
			callbackRegistered = false;
		}

		onClientThread(() ->
		{
			savePets();
			running = false;
			clearRoster();
			collisionMap.clear();
			modelFactory.clear();
		});

		overlayManager.remove(minimapOverlay);
		overlayManager.remove(nameplateOverlay);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (!running)
		{
			return;
		}
		CompletableFuture<Void> done = new CompletableFuture<>();
		event.waitFor(done);
		onClientThread(() ->
		{
			savePets();
			done.complete(null);
		});
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		clientThread.invoke(() ->
		{
			if (!running)
			{
				return;
			}
			savePets();
			clearRoster();
			rosterAccount = null;
			loadAccount();
		});
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invoke(() ->
		{
			abilities.forgetQuests();
			planner.forgetWaysBack();
			loadAccount();
		});
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		detector.onMenuOptionClicked(event.getMenuOption());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		detector.onGameObjectSpawned(event.getGameObject());
		collisionMap.passabilityChanged(event.getTile().getWorldLocation());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		collisionMap.passabilityChanged(event.getTile().getWorldLocation());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		collisionMap.passabilityChanged(event.getTile().getWorldLocation());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		collisionMap.passabilityChanged(event.getTile().getWorldLocation());
	}

	@Subscribe
	public void onInfoBoxMenuClicked(InfoBoxMenuClicked event)
	{
		if (findBox != null && event.getInfoBox() == findBox && PetFindInfoBox.STOP.equals(event.getEntry().getOption()))
		{
			setFinding(null);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FreeRangePetsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("mapMarkers".equals(event.getKey()))
		{
			clientThread.invoke(() -> mapManager.update(pets, config.mapMarkers(), finding));
		}
		else if ("shortestPathOnFind".equals(event.getKey()) && !config.shortestPathOnFind())
		{
			clientThread.invoke(this::clearShortestPath);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOADING)
		{
			detachAllRenderers();
			hiddenPohNpcIndices.clear();
			detector.onSceneLoading();
			lastGameCycle = -1;
			return;
		}
		if (state == GameState.LOGGED_IN)
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv != null && !wv.isInstance())
			{
				detector.onLeftInstance();
				collisionMap.sceneChanged();
			}
			else
			{
				detector.scanScene(wv);
			}
			loadAccount();
			restorePending();
			return;
		}
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			savePets();
			detachAllRenderers();
			hiddenPohNpcIndices.clear();
			abilities.forgetQuests();
			planner.forgetWaysBack();
			lastGameCycle = -1;
			lastTick = -1;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (!drawnPets.isEmpty())
		{
			petMenu.addEntries(drawnPets);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		loadAccount();
		restorePending();
		syncPohMenagerie();

		collisionMap.harvestLoadedRegions();
		int[] fresh = collisionMap.takeFreshRegions();
		if (fresh.length > 0 && worldMesh.joinWhereWalked(collisionMap, fresh) > 0)
		{
			worldMesh.linkSpaces(WaysBack.MAINLAND_X, WaysBack.MAINLAND_Y, 0);
		}

		int tick = client.getTickCount();
		if (lastTick >= 0 && tick < lastTick)
		{
			// The tick counter started again: everything stamped by tick would never expire.
			for (RoamingPet pet : pets)
			{
				pet.timelineReset();
			}
			roamContext.timelineReset();
		}
		lastTick = tick;
		roamContext.setTick(tick);

		if (++ticksSinceCensus >= CENSUS_REFRESH_TICKS)
		{
			ticksSinceCensus = 0;
			census.recount(pets);
		}

		int plansLeft = FAR_PLANS_PER_TICK;
		roamContext.setSearchBudget(WATCHDOG_SEARCH_BUDGET);
		WorldPoint playerAt = localPlayerTile();
		boolean fewInView = drawnPets.size() <= WATCHING_CROWD;

		for (RoamingPet pet : pets)
		{
			boolean following = detector.isActiveFollower(client, pet);
			pet.setPausedByFollower(following);
			if (following)
			{
				detachRenderer(pet);
				if (pet == finding)
				{
					setFinding(null);
				}
				continue;
			}

			if (pet.getTier() == PetTier.FAR && pet.farCheckDue(tick))
			{
				if (!pet.needsFarPlan(tick) || plansLeft > 0)
				{
					if (pet.advanceFar(roamContext, planner))
					{
						plansLeft--;
					}
					pet.scheduleFarCheck(tick, FAR_CHECK_INTERVAL_TICKS);
				}
			}

			plansLeft = rescueIfStuck(pet, tick, plansLeft);

			if (fewInView && playerAt != null && pet.mayWatch() && moods.nextFloat() < WATCH_CHANCE)
			{
				WorldPoint at = pet.currentTile();
				if (at.getPlane() == playerAt.getPlane() && at.distanceTo2D(playerAt) <= WATCH_TILES)
				{
					pet.watch(tick + WATCH_TICKS, playerAt.getX() - at.getX(), playerAt.getY() - at.getY());
				}
			}
		}

		updateFindTracking();
		mapManager.update(pets, config.mapMarkers(), finding);

		if (rosterDirty || ++ticksSinceSidebar >= SIDEBAR_REFRESH_TICKS)
		{
			ticksSinceSidebar = 0;
			rosterDirty = false;
			refreshSidebar();
		}

		if ((saveAt >= 0 && client.getTickCount() >= saveAt) || ++ticksSinceSave >= SAVE_INTERVAL_TICKS)
		{
			savePets();
		}
	}

	/**
	 * Moves a pet that has stopped getting anywhere. A visible pet somewhere it
	 * could not have walked to is put right at once; otherwise the cheap fixes
	 * come first (spin, back out, re-plan) and only then is it carried to the
	 * nearest walkable tile, with a fresh spot elsewhere as the last resort.
	 *
	 * @return the FAR plans left this tick
	 */
	private int rescueIfStuck(RoamingPet pet, int tick, int plansLeft)
	{
		if (pet.inTransition())
		{
			return plansLeft;
		}
		WorldPoint at = pet.currentTile();
		boolean onOrigin = transportNetwork.hasOrigin(at.getX(), at.getY());

		if (pet.getTier() != PetTier.FAR && !onOrigin
			&& !collisionMap.isKnownWalkable(at.getX(), at.getY(), at.getPlane()))
		{
			WorldPoint safe = planner.snapToMesh(at);
			if (!safe.equals(at))
			{
				log.debug("Pet {} was on unwalkable ground at {}; moved to {}", pet.getId(), at, safe);
				pet.relocate(safe);
				pet.noteUnstuck(tick);
				return plansLeft;
			}
		}

		// A FAR pet partway along its leg is between planned tiles, not trapped.
		boolean trapped = !onOrigin && (pet.getTier() != PetTier.FAR || pet.needsFarPlan(tick)) && isTrapped(at);
		if (!trapped && !pet.isStuck(tick))
		{
			return plansLeft;
		}
		if (pet.getTier() == PetTier.FAR)
		{
			if (plansLeft <= 0)
			{
				return plansLeft;
			}
			plansLeft--;
		}
		if (!trapped && pet.workFree(roamContext, planner))
		{
			return plansLeft;
		}

		WorldPoint safe = planner.snapToMesh(at);
		if (safe.equals(at) || isTrapped(safe))
		{
			// The mesh says it is fine here, so it is stuck for a reason the map cannot see.
			census.recount(pets);
			safe = planner.pickScatterTile(at, census, scatterRandom);
		}
		log.debug("Rescuing stuck pet {} from {} to {}", pet.getId(), at, safe);
		pet.relocate(safe);
		pet.noteUnstuck(tick);
		return plansLeft;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (pets.isEmpty())
		{
			drawnPets.clear();
			return;
		}

		int cycle = client.getGameCycle();
		int elapsed = lastGameCycle < 0 ? 0 : cycle - lastGameCycle;
		lastGameCycle = cycle;
		if (elapsed < 0 || elapsed > 200)
		{
			elapsed = 0;
		}

		WorldView wv = client.getTopLevelWorldView();
		boolean overworldLoaded = wv != null && !wv.isInstance();
		WorldPoint playerAt = overworldLoaded ? localPlayerTile() : null;
		int tick = client.getTickCount();
		roamContext.setTick(tick);

		// Build personal-space tile occupancy map for all active pets on this frame.
		occupancy.clear();
		for (RoamingPet pet : pets)
		{
			if (!pet.isPausedByFollower())
			{
				occupancy.addTo(RoamContext.tileKey(
					pet.getFineX() / RoamingPet.TILE,
					pet.getFineY() / RoamingPet.TILE,
					pet.getPlane()), 1L);
			}
		}

		roamContext.setSearchBudget(SEARCH_BUDGET_PER_FRAME);
		drawnPets.clear();

		for (RoamingPet pet : pets)
		{
			if (!overworldLoaded || pet.isPausedByFollower())
			{
				pet.setTier(PetTier.FAR, roamContext, planner);
				detachRenderer(pet);
				continue;
			}

			if (pet.getTier() == PetTier.FAR)
			{
				pet.catchUpFar(tick);
			}

			PetTier tier = classifyTier(pet, wv, playerAt);
			pet.setTier(tier, roamContext, planner);

			if (tier == PetTier.FAR)
			{
				detachRenderer(pet);
				continue;
			}

			if (elapsed > 0)
			{
				pet.advanceLocal(elapsed, roamContext);
			}

			if (tier == PetTier.SCENE)
			{
				ensureRenderer(pet);
				if (pet.getRenderer() != null)
				{
					drawnPets.add(pet);
				}
			}
			else
			{
				detachRenderer(pet);
			}
		}
	}

	private PetTier classifyTier(RoamingPet pet, WorldView wv, WorldPoint playerAt)
	{
		int localX = pet.getFineX() - wv.getBaseX() * RoamingPet.TILE;
		int localY = pet.getFineY() - wv.getBaseY() * RoamingPet.TILE;
		if (wv.getPlane() == pet.getPlane() && RoamingPet.isInScene(wv, localX, localY))
		{
			return PetTier.SCENE;
		}
		if (playerAt != null)
		{
			int dist = WorldLayout.span(
				pet.getFineX() / RoamingPet.TILE - playerAt.getX(),
				pet.getFineY() / RoamingPet.TILE - playerAt.getY());
			if (dist <= NEAR_DISTANCE_TILES)
			{
				return PetTier.NEAR;
			}
		}
		return PetTier.FAR;
	}

	private boolean isTrapped(WorldPoint at)
	{
		return worldMesh.isSealedPocket(at.getX(), at.getY(), at.getPlane())
			|| worldMesh.isWater(at.getX(), at.getY(), at.getPlane());
	}

	private void syncPohMenagerie()
	{
		hiddenPohNpcIndices.clear();
		if (!detector.isInOwnMenagerie(client))
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		PohPortalLocation portal = PohPortalLocation.fromVarbit(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
		census.recount(pets);
		boolean addedAny = false;

		Map<NPC, PetSnapshot> menagerie = new LinkedHashMap<>();
		Set<Integer> presentIds = new HashSet<>();
		for (NPC npc : wv.npcs())
		{
			if (!detector.isMenageriePet(client, npc))
			{
				continue;
			}
			hiddenPohNpcIndices.add(npc.getIndex());
			PetSnapshot snap = PetSnapshot.fromNpc(npc);
			if (snap != null)
			{
				menagerie.put(npc, snap);
				presentIds.add(snap.getNpcId());
			}
		}

		Set<RoamingPet> matched = new HashSet<>();
		for (Map.Entry<NPC, PetSnapshot> entry : menagerie.entrySet())
		{
			NPC npc = entry.getKey();
			PetSnapshot snap = entry.getValue();

			RoamingPet existing = findExisting(snap, presentIds, matched);
			if (existing != null)
			{
				matched.add(existing);
				if (existing.getSnapshot().getNpcId() != snap.getNpcId())
				{
					// The same pet in another form (a metamorphosis, say): take its new look.
					existing.setSnapshot(snap);
					detachRenderer(existing);
					rosterDirty = true;
				}
				continue;
			}

			// Scatter each newly freed pet to its own distinct walkable Gielinor region.
			long seed = nextUniqueSeed(snap.getNpcId());
			WorldPoint startTile = planner.pickScatterTile(portal.getPortalTile(), census, scatterRandom);
			RoamingPet freed = new RoamingPet(seed, snap, startTile, npc.getCurrentOrientation());
			pets.add(freed);
			matched.add(freed);
			addedAny = true;
			log.debug("Freed POH menagerie pet '{}' (npc {}) at {}", snap.getName(), snap.getNpcId(), startTile);
		}

		if (addedAny)
		{
			rosterDirty = true;
			savePets();
		}
	}

	private void scatterAllPets()
	{
		if (pets.isEmpty())
		{
			return;
		}
		census.begin();
		int tick = client.getTickCount();
		for (RoamingPet pet : pets)
		{
			if (pet.isPausedByFollower())
			{
				continue;
			}
			detachRenderer(pet);
			WorldPoint destination = planner.pickScatterTile(
				new WorldPoint(WaysBack.MAINLAND_X, WaysBack.MAINLAND_Y, 0),
				census,
				scatterRandom
			);
			pet.relocate(destination);
			pet.noteUnstuck(tick);
		}
		rosterDirty = true;
		savePets();
		mapManager.update(pets, config.mapMarkers(), finding);
		refreshSidebar();
	}

	/**
	 * Spreads out any restored pets that were clumped on the same tiles (e.g.
	 * from an earlier save where all pets spawned on the POH portal) or standing
	 * on unwalkable ground.
	 */
	private void unclumpRestoredPets()
	{
		census.begin();
		int tick = client.getTickCount();
		boolean changed = false;

		for (int i = 0; i < pets.size(); i++)
		{
			RoamingPet pet = pets.get(i);
			WorldPoint at = pet.currentTile();
			boolean clumped = false;
			for (int j = 0; j < i; j++)
			{
				WorldPoint other = pets.get(j).currentTile();
				if (other.getPlane() == at.getPlane()
					&& WorldLayout.span(at.getX() - other.getX(), at.getY() - other.getY()) <= CLUMP_DETECT_TILES)
				{
					clumped = true;
					break;
				}
			}

			if (clumped || isTrapped(at))
			{
				WorldPoint spread = planner.pickScatterTile(at, census, scatterRandom);
				pet.relocate(spread);
				pet.noteUnstuck(tick);
				changed = true;
			}
			else
			{
				WorldPoint snapped = planner.snapToMesh(at);
				if (!snapped.equals(at))
				{
					pet.relocate(snapped);
					changed = true;
				}
				census.add(snapped.getX(), snapped.getY(), snapped.getPlane());
			}
		}

		if (changed)
		{
			rosterDirty = true;
			savePets();
		}
	}

	/**
	 * The roaming pet a menagerie NPC already is: by NPC id, or by name for a pet
	 * whose form changed. A name match is only taken for a roaming pet whose own
	 * NPC id is not also in the menagerie, so two pets sharing a name stay two pets.
	 */
	private RoamingPet findExisting(PetSnapshot snap, Set<Integer> presentIds, Set<RoamingPet> matched)
	{
		for (RoamingPet pet : pets)
		{
			if (pet.getSnapshot().getNpcId() == snap.getNpcId())
			{
				return pet;
			}
		}
		String name = snap.getName();
		if (name == null)
		{
			return null;
		}
		for (RoamingPet pet : pets)
		{
			if (!matched.contains(pet)
				&& !presentIds.contains(pet.getSnapshot().getNpcId())
				&& name.equalsIgnoreCase(pet.getSnapshot().getName()))
			{
				return pet;
			}
		}
		return null;
	}

	private long nextUniqueSeed(int npcId)
	{
		long seed = ((long) npcId << 32) ^ (++seedCounter * 0x9E3779B97F4A7C15L) ^ System.nanoTime();
		while (isSeedUsed(seed))
		{
			seed += 0x9E3779B97F4A7C15L;
		}
		return seed;
	}

	private boolean isSeedUsed(long seed)
	{
		for (RoamingPet pet : pets)
		{
			if (pet.getId() == seed)
			{
				return true;
			}
		}
		return false;
	}

	private void ensureRenderer(RoamingPet pet)
	{
		if (pet.getRenderer() != null)
		{
			return;
		}
		Model model = modelFactory.modelFor(pet.getSnapshot());
		if (model == null)
		{
			return;
		}
		FakePet renderer = new FakePet(client, pet, model, modelFactory);
		pet.setRenderer(renderer);
		client.registerRuneLiteObject(renderer);
	}

	private void detachRenderer(RoamingPet pet)
	{
		FakePet renderer = pet.getRenderer();
		if (renderer != null)
		{
			client.removeRuneLiteObject(renderer);
			pet.setRenderer(null);
		}
	}

	private void detachAllRenderers()
	{
		for (RoamingPet pet : pets)
		{
			detachRenderer(pet);
		}
		drawnPets.clear();
	}

	private void removePet(RoamingPet pet)
	{
		if (pet == finding)
		{
			setFinding(null);
		}
		detachRenderer(pet);
		pets.remove(pet);
		census.recount(pets);
		rosterDirty = true;
		savePets();
		refreshSidebar();
	}

	private void toggleFind(RoamingPet pet)
	{
		setFinding(pet == finding ? null : pet);
	}

	private void setFinding(RoamingPet target)
	{
		finding = target;
		if (finding == null)
		{
			clearOurHintArrow();
			clearShortestPath();
			showFindBox(false);
		}
		else
		{
			showFindBox(true);
			updateFindTracking();
		}
		mapManager.update(pets, config.mapMarkers(), finding);
		refreshSidebar();
	}

	private void updateFindTracking()
	{
		if (finding == null)
		{
			return;
		}
		if (!pets.contains(finding) || finding.isPausedByFollower())
		{
			setFinding(null);
			return;
		}

		WorldPoint target = finding.currentTile();
		WorldPoint playerAt = localPlayerTile();
		int distance = playerAt != null && playerAt.getPlane() == target.getPlane()
			? playerAt.distanceTo2D(target)
			: -1;

		if (findBox != null)
		{
			findBox.update(finding.displayName(), placeNames.describe(target), distance);
		}

		if (distance >= 0 && distance <= FOUND_DISTANCE_TILES)
		{
			setFinding(null);
			return;
		}

		updateShortestPath(target);

		if (finding.getRenderer() != null)
		{
			clearOurHintArrow();
		}
		else if (!client.hasHintArrow() || isOurHintArrow())
		{
			client.setHintArrow(target);
			arrowAt = target;
		}
		else
		{
			arrowAt = null;
		}
	}

	private void updateShortestPath(WorldPoint target)
	{
		if (!config.shortestPathOnFind())
		{
			clearShortestPath();
			return;
		}
		int tick = client.getTickCount();
		boolean moved = routedTo == null || routedTo.getPlane() != target.getPlane()
			|| routedTo.distanceTo2D(target) >= REROUTE_TILES;
		if (!moved || (routedTo != null && tick >= routedAt && tick - routedAt < REROUTE_TICKS))
		{
			return;
		}
		routedTo = target;
		routedAt = tick;
		Map<String, Object> data = new HashMap<>();
		data.put("target", target);
		eventBus.post(new PluginMessage(SHORTEST_PATH, SHORTEST_PATH_ROUTE, data));
	}

	private void clearShortestPath()
	{
		if (routedTo != null)
		{
			routedTo = null;
			eventBus.post(new PluginMessage(SHORTEST_PATH, SHORTEST_PATH_CLEAR));
		}
	}

	private void showFindBox(boolean shown)
	{
		if (shown && findBox == null)
		{
			findBox = new PetFindInfoBox(pluginIcon, this);
			infoBoxManager.addInfoBox(findBox);
		}
		else if (!shown && findBox != null)
		{
			infoBoxManager.removeInfoBox(findBox);
			findBox = null;
		}
	}

	private boolean isOurHintArrow()
	{
		return arrowAt != null
			&& client.hasHintArrow()
			&& client.getHintArrowType() == HintArrowType.COORDINATE
			&& arrowAt.equals(client.getHintArrowPoint());
	}

	private void clearOurHintArrow()
	{
		if (isOurHintArrow())
		{
			client.clearHintArrow();
		}
		arrowAt = null;
	}

	private void refreshSidebar()
	{
		FreeRangePetsPanel targetPanel = panel;
		if (targetPanel == null)
		{
			return;
		}

		WorldPoint me = localPlayerTile();
		List<RoamingPet> sorted = new ArrayList<>(pets);
		Comparator<RoamingPet> comparator = Comparator.comparing((RoamingPet p) -> !p.isFavourite());
		if (me != null)
		{
			comparator = comparator.thenComparingInt(p -> p.isPausedByFollower() ? 0 : p.currentTile().distanceTo2D(me));
		}
		comparator = comparator.thenComparing(RoamingPet::displayName, String.CASE_INSENSITIVE_ORDER);
		sorted.sort(comparator);

		List<FreeRangePetsPanel.RowData> rows = new ArrayList<>(sorted.size());
		for (RoamingPet pet : sorted)
		{
			WorldPoint at = pet.currentTile();
			int dist = me != null && !pet.isPausedByFollower() && me.getPlane() == at.getPlane()
				? me.distanceTo2D(at)
				: -1;
			rows.add(new FreeRangePetsPanel.RowData(
				pet,
				pet.displayName(),
				pet.getSnapshot().getName(),
				pet.isFavourite(),
				placeNames.describe(at),
				dist,
				pet.isPausedByFollower(),
				pet == finding
			));
		}
		targetPanel.updateRows(rows);
	}

	private WorldPoint localPlayerTile()
	{
		Player player = client.getLocalPlayer();
		return player == null ? null : player.getWorldLocation();
	}

	private void loadAccount()
	{
		if (!running || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String account = configManager.getRSProfileKey();
		if (account == null || account.equals(rosterAccount))
		{
			return;
		}
		if (rosterAccount != null)
		{
			savePets();
			clearRoster();
		}
		rosterAccount = account;
		String encoded = configManager.getConfiguration(
			FreeRangePetsConfig.GROUP,
			account,
			FreeRangePetsConfig.SAVED_PETS_KEY
		);
		pendingRestore.addAll(store.deserialise(encoded));
		restorePending();
		refreshSidebar();
	}

	private void restorePending()
	{
		if (pendingRestore.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		List<PetStore.SavedPet> toRestore = new ArrayList<>(pendingRestore);
		pendingRestore.clear();

		for (PetStore.SavedPet saved : toRestore)
		{
			RoamingPet revived = store.revive(saved);
			if (revived != null)
			{
				pets.add(revived);
			}
		}
		unclumpRestoredPets();
		rosterDirty = true;
		refreshSidebar();
	}

	private void saveSoon()
	{
		if (saveAt < 0)
		{
			saveAt = client.getTickCount() + SAVE_SOON_TICKS;
		}
	}

	private void savePets()
	{
		saveAt = -1;
		ticksSinceSave = 0;
		if (!running || rosterAccount == null || !pendingRestore.isEmpty())
		{
			return;
		}
		configManager.setConfiguration(
			FreeRangePetsConfig.GROUP,
			rosterAccount,
			FreeRangePetsConfig.SAVED_PETS_KEY,
			store.serialise(pets)
		);
	}

	private void clearRoster()
	{
		setFinding(null);
		detachAllRenderers();
		mapManager.clear();
		pets.clear();
		census.clear();
		pendingRestore.clear();
		hiddenPohNpcIndices.clear();
		refreshSidebar();
	}

	private void onClientThread(Runnable task)
	{
		if (client.isClientThread())
		{
			task.run();
		}
		else
		{
			clientThread.invoke(task);
		}
	}
}
