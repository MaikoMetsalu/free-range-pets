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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Strongly connected component reachability over {@link WorldMesh} spaces and
 * usable {@link WorldTransport} edges, ensuring pets never enter one-way traps
 * they cannot leave and slightly discouraging tiny dead-end rooms.
 */
final class WaysBack
{
	static final int RECHECK_TICKS = 50;
	static final int DEAD_END_SPACES = 8;

	/** Lumbridge Castle courtyard — core mainland connected component. */
	static final int MAINLAND_X = 3222;
	static final int MAINLAND_Y = 3218;

	private static final int SPACES = 1 << 16;
	private static final int MOST_REMEMBERED = 100_000;

	private final WorldMesh mesh;
	private final TransportNetwork transports;
	private final Predicate<WorldTransport> usable;

	private int builtRevision = -1;
	private int builtLinks = -1;
	private int checkedTick;
	private boolean checked;
	private BitSet builtUsable = new BitSet();
	private int builds;

	private final int[] group = new int[SPACES];
	private final boolean[] reachesMainland = new boolean[SPACES];
	private final Map<Integer, List<Integer>> edges = new HashMap<>();
	private Map<Long, List<WorldTransport>> starting = new HashMap<>();
	private List<Integer>[] fromSpaces = newLists(0);
	private List<Integer>[] toSpaces = newLists(0);

	private final Map<Long, Boolean> reaches = new HashMap<>();
	private final Map<Long, Boolean> deadEnds = new HashMap<>();
	private final int[] seenBy = new int[SPACES];
	private int searches;

	WaysBack(WorldMesh mesh, TransportNetwork transports, Predicate<WorldTransport> usable)
	{
		this.mesh = mesh;
		this.transports = transports;
		this.usable = usable;
	}

	@SuppressWarnings("unchecked")
	private static List<Integer>[] newLists(int size)
	{
		return (List<Integer>[]) new List[size];
	}

	int builds()
	{
		return builds;
	}

	void forget()
	{
		builtRevision = -1;
		checked = false;
	}

	void refresh(int tick)
	{
		boolean sameTable = transports.revision() == builtRevision && mesh.linkRevision() == builtLinks;
		if (sameTable && checked && tick >= checkedTick && tick - checkedTick < RECHECK_TICKS)
		{
			return;
		}
		checked = true;
		checkedTick = tick;

		List<WorldTransport> all = transports.all();
		BitSet now = new BitSet(all.size());
		for (int i = 0; i < all.size(); i++)
		{
			if (usable.test(all.get(i)))
			{
				now.set(i);
			}
		}
		if (sameTable && now.equals(builtUsable))
		{
			return;
		}
		builtRevision = transports.revision();
		builtLinks = mesh.linkRevision();
		builtUsable = now;
		build(all);
	}

	List<Integer> setOff(WorldTransport t)
	{
		int i = t.getIndex();
		if (i >= 0 && i < fromSpaces.length && fromSpaces[i] != null)
		{
			return fromSpaces[i];
		}
		List<Integer> spaces = mesh.spacesAt(t.getFromX(), t.getFromY(), t.getFromPlane());
		if (i >= 0 && i < fromSpaces.length)
		{
			fromSpaces[i] = spaces;
		}
		return spaces;
	}

	List<Integer> landing(WorldTransport t)
	{
		int i = t.getIndex();
		if (i >= 0 && i < toSpaces.length && toSpaces[i] != null)
		{
			return toSpaces[i];
		}
		List<Integer> spaces = mesh.spacesOnward(
			t.getToX(), t.getToY(), t.getToPlane(),
			t.getFromX(), t.getFromY(), t.getFromPlane(),
			TransportNetwork.CHAIN_HOPS, starting
		);
		if (i >= 0 && i < toSpaces.length)
		{
			toSpaces[i] = spaces;
		}
		return spaces;
	}

	boolean comesBack(List<Integer> from, List<Integer> to)
	{
		for (int space : to)
		{
			if (reachesMainland(space))
			{
				return true;
			}
			for (int origin : from)
			{
				if (reaches(space, origin))
				{
					return true;
				}
			}
		}
		return false;
	}

	boolean isDeadEnd(List<Integer> from, List<Integer> to)
	{
		if (to.isEmpty() || from.isEmpty())
		{
			return false;
		}
		for (int space : to)
		{
			if (from.contains(space))
			{
				return false;
			}
		}
		long key = ((long) to.get(0) << 16) | from.get(0);
		Boolean known = deadEnds.get(key);
		if (known != null)
		{
			return known;
		}
		int stamp = ++searches;
		for (int origin : from)
		{
			seenBy[origin] = stamp;
		}
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int pocket = 0;
		for (int space : to)
		{
			if (seenBy[space] != stamp)
			{
				seenBy[space] = stamp;
				queue.add(space);
				pocket++;
			}
		}
		boolean dead = true;
		while (!queue.isEmpty() && dead)
		{
			for (int next : edges.getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (seenBy[next] == stamp)
				{
					continue;
				}
				seenBy[next] = stamp;
				if (++pocket > DEAD_END_SPACES)
				{
					dead = false;
					break;
				}
				queue.add(next);
			}
		}
		if (deadEnds.size() >= MOST_REMEMBERED)
		{
			deadEnds.clear();
		}
		deadEnds.put(key, dead);
		return dead;
	}

	boolean reachesMainland(int space)
	{
		return space > 0 && space < SPACES && reachesMainland[space];
	}

	private boolean reaches(int from, int to)
	{
		if (from == to || (group[from] != 0 && group[from] == group[to]))
		{
			return true;
		}
		long key = ((long) from << 16) | to;
		Boolean known = reaches.get(key);
		if (known != null)
		{
			return known;
		}
		boolean found = search(from, to);
		if (reaches.size() >= MOST_REMEMBERED)
		{
			reaches.clear();
		}
		reaches.put(key, found);
		return found;
	}

	private boolean search(int from, int to)
	{
		int stamp = ++searches;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seenBy[from] = stamp;
		queue.add(from);
		while (!queue.isEmpty())
		{
			for (int next : edges.getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (next == to)
				{
					return true;
				}
				if (seenBy[next] != stamp)
				{
					seenBy[next] = stamp;
					queue.add(next);
				}
			}
		}
		return false;
	}

	private void build(List<WorldTransport> all)
	{
		builds++;
		reaches.clear();
		deadEnds.clear();
		edges.clear();
		fromSpaces = newLists(all.size());
		toSpaces = newLists(all.size());
		starting = new HashMap<>();

		for (int i = builtUsable.nextSetBit(0); i >= 0; i = builtUsable.nextSetBit(i + 1))
		{
			WorldTransport t = all.get(i);
			starting.computeIfAbsent(WorldMesh.tileKey(t.getFromX(), t.getFromY(), t.getFromPlane()),
				k -> new ArrayList<>()).add(t);
		}
		for (int i = builtUsable.nextSetBit(0); i >= 0; i = builtUsable.nextSetBit(i + 1))
		{
			WorldTransport t = all.get(i);
			List<Integer> to = landing(t);
			for (int from : setOff(t))
			{
				for (int space : to)
				{
					edge(from, space);
				}
			}
		}
		for (Map.Entry<Integer, List<Integer>> walked : mesh.walkedTo().entrySet())
		{
			for (int to : walked.getValue())
			{
				edge(walked.getKey(), to);
			}
		}
		computeGroups();
		computeMainlandReach();
	}

	private void edge(int from, int to)
	{
		if (from == to)
		{
			return;
		}
		List<Integer> out = edges.computeIfAbsent(from, k -> new ArrayList<>());
		if (!out.contains(to))
		{
			out.add(to);
		}
	}

	private void computeMainlandReach()
	{
		Arrays.fill(reachesMainland, false);
		int home = mesh.componentAt(MAINLAND_X, MAINLAND_Y, 0);
		if (home == 0)
		{
			return;
		}
		Map<Integer, List<Integer>> back = new HashMap<>();
		for (Map.Entry<Integer, List<Integer>> e : edges.entrySet())
		{
			for (int to : e.getValue())
			{
				back.computeIfAbsent(to, k -> new ArrayList<>()).add(e.getKey());
			}
		}
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		reachesMainland[home] = true;
		queue.add(home);
		while (!queue.isEmpty())
		{
			for (int prior : back.getOrDefault(queue.poll(), Collections.emptyList()))
			{
				if (!reachesMainland[prior])
				{
					reachesMainland[prior] = true;
					queue.add(prior);
				}
			}
		}
	}

	private void computeGroups()
	{
		Arrays.fill(group, 0);
		int[] index = new int[SPACES];
		Arrays.fill(index, -1);
		int[] low = new int[SPACES];
		boolean[] onStack = new boolean[SPACES];
		int[] stack = new int[SPACES];
		int[] callNode = new int[SPACES];
		int[] callEdge = new int[SPACES];
		int top = 0;
		int counter = 0;
		int groupCount = 0;

		for (int root : edges.keySet())
		{
			if (index[root] >= 0)
			{
				continue;
			}
			int depth = 0;
			callNode[0] = root;
			callEdge[0] = 0;
			index[root] = low[root] = counter++;
			stack[top++] = root;
			onStack[root] = true;
			while (depth >= 0)
			{
				int v = callNode[depth];
				List<Integer> out = edges.getOrDefault(v, Collections.emptyList());
				if (callEdge[depth] < out.size())
				{
					int w = out.get(callEdge[depth]++);
					if (index[w] < 0)
					{
						index[w] = low[w] = counter++;
						stack[top++] = w;
						onStack[w] = true;
						depth++;
						callNode[depth] = w;
						callEdge[depth] = 0;
					}
					else if (onStack[w])
					{
						low[v] = Math.min(low[v], index[w]);
					}
					continue;
				}
				if (low[v] == index[v])
				{
					groupCount++;
					int w;
					do
					{
						w = stack[--top];
						onStack[w] = false;
						group[w] = groupCount;
					}
					while (w != v);
				}
				depth--;
				if (depth >= 0)
				{
					int u = callNode[depth];
					low[u] = Math.min(low[u], low[v]);
				}
			}
		}
	}
}
