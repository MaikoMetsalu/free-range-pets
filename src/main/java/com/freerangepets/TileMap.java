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

/**
 * Primitive {@code long -> long} hash map preserving insertion order, avoiding
 * boxed allocations during tile floods, pathfinding, and occupancy tracking.
 */
final class TileMap
{
	private static final long EMPTY = Long.MIN_VALUE;

	private long[] keys;
	private long[] values;
	private int mask;
	private long[] order;
	private int size;

	private boolean hasEmptyKey;
	private long emptyKeyValue;

	TileMap(int expected)
	{
		int capacity = Integer.highestOneBit(Math.max(4, expected) * 2 - 1) * 2;
		keys = new long[capacity];
		values = new long[capacity];
		Arrays.fill(keys, EMPTY);
		mask = capacity - 1;
		order = new long[Math.max(4, expected)];
	}

	int size()
	{
		return size;
	}

	long keyAt(int index)
	{
		return order[index];
	}

	boolean containsKey(long key)
	{
		if (key == EMPTY)
		{
			return hasEmptyKey;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return true;
			}
			if (at == EMPTY)
			{
				return false;
			}
		}
	}

	long getOrDefault(long key, long missing)
	{
		if (key == EMPTY)
		{
			return hasEmptyKey ? emptyKeyValue : missing;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return values[slot];
			}
			if (at == EMPTY)
			{
				return missing;
			}
		}
	}

	long get(long key)
	{
		if (key == EMPTY)
		{
			if (!hasEmptyKey)
			{
				throw new IllegalStateException("No such tile");
			}
			return emptyKeyValue;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return values[slot];
			}
			if (at == EMPTY)
			{
				throw new IllegalStateException("No such tile");
			}
		}
	}

	void add(long key, long value)
	{
		if (size == order.length)
		{
			order = Arrays.copyOf(order, size * 2);
		}
		order[size++] = key;
		if (key == EMPTY)
		{
			hasEmptyKey = true;
			emptyKeyValue = value;
			return;
		}
		if (size * 2 > keys.length)
		{
			grow();
		}
		insert(key, value);
	}

	long addTo(long key, long delta)
	{
		if (key == EMPTY)
		{
			if (!hasEmptyKey)
			{
				add(key, delta);
				return delta;
			}
			return emptyKeyValue += delta;
		}
		for (int slot = slot(key); ; slot = (slot + 1) & mask)
		{
			long at = keys[slot];
			if (at == key)
			{
				return values[slot] += delta;
			}
			if (at == EMPTY)
			{
				add(key, delta);
				return delta;
			}
		}
	}

	void clear()
	{
		if (size == 0)
		{
			return;
		}
		Arrays.fill(keys, EMPTY);
		size = 0;
		hasEmptyKey = false;
	}

	private void insert(long key, long value)
	{
		int slot = slot(key);
		while (keys[slot] != EMPTY)
		{
			slot = (slot + 1) & mask;
		}
		keys[slot] = key;
		values[slot] = value;
	}

	private void grow()
	{
		long[] oldKeys = keys;
		long[] oldValues = values;
		keys = new long[oldKeys.length * 2];
		values = new long[oldKeys.length * 2];
		Arrays.fill(keys, EMPTY);
		mask = keys.length - 1;
		for (int i = 0; i < oldKeys.length; i++)
		{
			if (oldKeys[i] != EMPTY)
			{
				insert(oldKeys[i], oldValues[i]);
			}
		}
	}

	private int slot(long key)
	{
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32)) & mask;
	}
}
