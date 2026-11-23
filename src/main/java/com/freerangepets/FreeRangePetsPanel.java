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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.Value;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * Sidebar panel listing all roaming pets with their live locations, distances,
 * star toggles, inline renaming, Find buttons, remove buttons, and a Scatter
 * button to spread pets across Gielinor.
 *
 * <p>Rows belong to pets, not to positions in the list: each periodic update
 * only changes the texts and colours that moved, and components are only
 * added, removed or moved when the set or order of pets actually changes.
 * All {@link RoamingPet} state shown here arrives through {@link RowData};
 * the pet itself is only used as an identity key and handed back to the
 * callbacks.
 */
class FreeRangePetsPanel extends PluginPanel
{
	private static final Font HEADING_FONT = FontManager.getRunescapeBoldFont().deriveFont(18f);
	private static final Font NAME_FONT = FontManager.getRunescapeFont().deriveFont(15f);
	private static final Font SMALL_FONT = FontManager.getRunescapeSmallFont();
	private static final Color STAR_ON = new Color(0xFFD23F);
	private static final Color STAR_OFF = new Color(110, 110, 110);
	private static final Color FIND_ACTIVE = new Color(0x4C8C5A);

	/**
	 * How many updates a committed rename may be contradicted by stale data
	 * before the panel gives up waiting for it and shows what it is told.
	 */
	private static final int PENDING_NAME_PATIENCE = 3;

	@Value
	static class RowData
	{
		RoamingPet pet;
		String displayName;
		String speciesName;
		boolean favourite;
		String location;
		int distance;
		boolean following;
		boolean finding;
	}

	private final Consumer<RoamingPet> onFind;
	private final Consumer<RoamingPet> onStar;
	private final Consumer<RoamingPet> onRemove;
	private final BiConsumer<RoamingPet, String> onRename;

	private final JLabel headerCount = new JLabel("No roaming pets yet");
	private final JButton scatterButton;
	private final IconTextField searchField = new IconTextField();
	private final JPanel listContainer = new JPanel();
	private final JPanel emptyCard;
	private final JLabel noMatchLabel;
	private final JPanel noMatchCard;

	/** Row components keyed by pet identity, for every pet currently tracked (shown or filtered out). */
	private final Map<RoamingPet, PetRow> rowsByPet = new IdentityHashMap<>();

	private List<RowData> currentRows = Collections.emptyList();

	FreeRangePetsPanel(
		Consumer<RoamingPet> onFind,
		Consumer<RoamingPet> onStar,
		Consumer<RoamingPet> onRemove,
		BiConsumer<RoamingPet, String> onRename,
		Runnable onScatter)
	{
		super(true);
		this.onFind = onFind;
		this.onStar = onStar;
		this.onRemove = onRemove;
		this.onRename = onRename;

		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Free Range Pets");
		title.setFont(HEADING_FONT);
		title.setForeground(Color.WHITE);

		scatterButton = createActionButton("Scatter", ColorScheme.MEDIUM_GRAY_COLOR,
			"Spread all roaming pets out across different regions of Gielinor");
		scatterButton.setVisible(false);
		scatterButton.addActionListener(e -> onScatter.run());

		JPanel titleRow = new JPanel(new BorderLayout(4, 0));
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(scatterButton, BorderLayout.EAST);

		headerCount.setFont(SMALL_FONT);
		headerCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		headerCount.setAlignmentX(Component.LEFT_ALIGNMENT);

		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refreshList();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refreshList();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refreshList();
			}
		});

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(titleRow);
		header.add(Box.createVerticalStrut(2));
		header.add(headerCount);
		header.add(Box.createVerticalStrut(8));
		header.add(searchField);

		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
		listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		emptyCard = buildEmptyCard(createEmptyLabel(
			"Visit your POH Menagerie to let your pets roam freely across Gielinor."));
		noMatchLabel = createEmptyLabel("");
		noMatchCard = buildEmptyCard(noMatchLabel);

		add(header, BorderLayout.NORTH);
		add(listContainer, BorderLayout.CENTER);

		refreshList();
	}

	void updateRows(List<RowData> rows)
	{
		List<RowData> copy = new ArrayList<>(rows);
		SwingUtilities.invokeLater(() ->
		{
			if (isEditingName())
			{
				return;
			}
			// A row moved out from under the pointer hands the click meant for it to
			// another pet, so while the mouse is over the list the order stands; pets
			// still come and go, and the next update after it leaves sorts them.
			currentRows = isPointerOverList() ? keepCurrentOrder(copy) : copy;
			refreshList();
		});
	}

	/** Whether the keyboard is in one of the rows' rename fields. */
	private boolean isEditingName()
	{
		Window window = SwingUtilities.getWindowAncestor(this);
		Component focusOwner = window == null ? null : window.getFocusOwner();
		return focusOwner instanceof JTextField && SwingUtilities.isDescendingFrom(focusOwner, listContainer);
	}

	private boolean isPointerOverList()
	{
		if (!listContainer.isShowing())
		{
			return false;
		}
		PointerInfo pointer = MouseInfo.getPointerInfo();
		if (pointer == null)
		{
			return false;
		}
		Point at = pointer.getLocation();
		SwingUtilities.convertPointFromScreen(at, listContainer);
		return listContainer.contains(at);
	}

	/** The given rows in the order currently shown, with any new pets after them in the given order. */
	private List<RowData> keepCurrentOrder(List<RowData> rows)
	{
		Map<RoamingPet, RowData> byPet = new IdentityHashMap<>();
		for (RowData row : rows)
		{
			byPet.put(row.getPet(), row);
		}
		List<RowData> ordered = new ArrayList<>(rows.size());
		for (RowData old : currentRows)
		{
			RowData row = byPet.remove(old.getPet());
			if (row != null)
			{
				ordered.add(row);
			}
		}
		for (RowData row : rows)
		{
			if (byPet.remove(row.getPet()) != null)
			{
				ordered.add(row);
			}
		}
		return ordered;
	}

	/**
	 * Brings the list in line with {@link #currentRows} and the search filter:
	 * updates existing rows in place, builds rows only for new pets, drops rows
	 * for pets that are gone, and only touches the container's children (and
	 * revalidates) when what is shown, or its order, changed.
	 */
	private void refreshList()
	{
		int total = currentRows.size();
		scatterButton.setVisible(total > 1);
		headerCount.setText(total == 0 ? "No roaming pets yet"
			: total == 1 ? "1 pet roaming Gielinor" : total + " pets roaming Gielinor");

		String rawQuery = searchField.getText() == null ? "" : searchField.getText().trim();
		String query = rawQuery.toLowerCase(Locale.ROOT);

		Set<RoamingPet> present = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Component> wanted = new ArrayList<>();
		for (RowData data : currentRows)
		{
			RoamingPet pet = data.getPet();
			if (!present.add(pet))
			{
				continue;
			}
			PetRow row = rowsByPet.get(pet);
			if (row == null)
			{
				row = new PetRow(data);
				rowsByPet.put(pet, row);
			}
			else
			{
				row.update(data);
			}
			if (query.isEmpty() || matches(data, query))
			{
				wanted.add(row.card);
				wanted.add(row.gap);
			}
		}
		rowsByPet.keySet().retainAll(present);

		if (total == 0)
		{
			wanted.add(emptyCard);
		}
		else if (wanted.isEmpty())
		{
			noMatchLabel.setText(emptyCardHtml("No pets match \"" + rawQuery + "\"."));
			wanted.add(noMatchCard);
		}

		if (syncChildren(wanted))
		{
			listContainer.revalidate();
			listContainer.repaint();
		}
	}

	/**
	 * Makes the list container's children exactly {@code wanted}, in order,
	 * moving only the components that are out of place.
	 *
	 * @return whether anything was added, removed or moved
	 */
	private boolean syncChildren(List<Component> wanted)
	{
		boolean changed = false;
		for (int i = 0; i < wanted.size(); i++)
		{
			Component component = wanted.get(i);
			if (i < listContainer.getComponentCount() && listContainer.getComponent(i) == component)
			{
				continue;
			}
			// No-op when it is not (yet) a child.
			listContainer.remove(component);
			listContainer.add(component, i);
			changed = true;
		}
		while (listContainer.getComponentCount() > wanted.size())
		{
			listContainer.remove(listContainer.getComponentCount() - 1);
			changed = true;
		}
		return changed;
	}

	private static boolean matches(RowData row, String query)
	{
		return row.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
			|| row.getSpeciesName().toLowerCase(Locale.ROOT).contains(query)
			|| row.getLocation().toLowerCase(Locale.ROOT).contains(query);
	}

	/** The nickname a row's data implies: empty when the pet goes by its species name. */
	private static String nicknameOf(RowData data)
	{
		return data.getDisplayName().equals(data.getSpeciesName()) ? "" : data.getDisplayName();
	}

	private static String emptyCardHtml(String message)
	{
		return "<html><body style='width: 175px; text-align: center;'>" + message + "</body></html>";
	}

	private static JLabel createEmptyLabel(String message)
	{
		JLabel label = new JLabel(emptyCardHtml(message), SwingConstants.CENTER);
		label.setFont(SMALL_FONT);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	private static JPanel buildEmptyCard(JLabel label)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(12, 10, 12, 10));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
		card.add(label, BorderLayout.CENTER);
		return card;
	}

	private static Border cardBorder(boolean finding)
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(finding ? FIND_ACTIVE : ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(6, 7, 6, 7)
		);
	}

	private static void setToolTip(JComponent component, String tooltip)
	{
		if (!Objects.equals(component.getToolTipText(), tooltip))
		{
			component.setToolTipText(tooltip);
		}
	}

	/** One pet's card, built once and updated in place for as long as the pet is tracked. */
	private final class PetRow
	{
		private final RoamingPet pet;
		private final JPanel card = new JPanel(new BorderLayout(4, 4));
		private final Component gap = Box.createVerticalStrut(6);
		private final JButton starBtn;
		private final JTextField nameField = new JTextField();
		private final JButton findBtn;
		private final JLabel whereLabel = new JLabel();

		/** The latest data for this pet; only ever replaced on the Swing thread. */
		private RowData data;
		private boolean borderFinding;

		/**
		 * A nickname committed but not yet reflected in {@link #data} ("" for
		 * cleared), so stale updates neither undo it on screen nor resend it.
		 */
		private String pendingName;
		private int pendingStale;

		private PetRow(RowData initial)
		{
			pet = initial.getPet();
			data = initial;

			borderFinding = initial.isFinding();
			card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			card.setBorder(cardBorder(borderFinding));
			card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

			starBtn = createIconButton("☆", STAR_OFF, "Star pet to pin at top");
			starBtn.addActionListener(e -> onStar.accept(pet));

			nameField.setFont(NAME_FONT);
			nameField.setForeground(Color.WHITE);
			nameField.setCaretColor(Color.WHITE);
			nameField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			nameField.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
			nameField.addActionListener(e ->
			{
				commitName();
				listContainer.requestFocusInWindow();
			});
			nameField.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent e)
				{
					commitName();
				}
			});

			findBtn = createActionButton("Find", ColorScheme.MEDIUM_GRAY_COLOR,
				"Point a hint arrow toward this pet");
			findBtn.addActionListener(e -> onFind.accept(pet));

			JButton removeBtn = createIconButton("×", ColorScheme.LIGHT_GRAY_COLOR,
				"Remove from roaming list (re-visiting your POH Menagerie will add it again)");
			removeBtn.addActionListener(e -> confirmRemove());

			JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
			actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			actions.add(findBtn);
			actions.add(removeBtn);

			JPanel top = new JPanel(new BorderLayout(4, 0));
			top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			top.add(starBtn, BorderLayout.WEST);
			top.add(nameField, BorderLayout.CENTER);
			top.add(actions, BorderLayout.EAST);

			whereLabel.setFont(SMALL_FONT);
			whereLabel.setBorder(new EmptyBorder(0, 18, 0, 0));

			card.add(top, BorderLayout.NORTH);
			card.add(whereLabel, BorderLayout.SOUTH);

			update(initial);
		}

		/** Applies new data, touching only what changed (Swing's setters skip unchanged text and colours). */
		private void update(RowData next)
		{
			data = next;

			boolean fav = next.isFavourite();
			starBtn.setText(fav ? "★" : "☆");
			starBtn.setForeground(fav ? STAR_ON : STAR_OFF);
			setToolTip(starBtn, fav ? "Unstar pet" : "Star pet to pin at top");

			if (pendingName != null)
			{
				String expected = pendingName.isEmpty() ? next.getSpeciesName() : pendingName;
				if (next.getDisplayName().equals(expected) || ++pendingStale > PENDING_NAME_PATIENCE)
				{
					pendingName = null;
				}
			}
			if (pendingName == null && !nameField.isFocusOwner()
				&& !nameField.getText().equals(next.getDisplayName()))
			{
				nameField.setText(next.getDisplayName());
				nameField.setCaretPosition(0);
			}
			setToolTip(nameField, next.getSpeciesName() + " — click to rename");

			boolean finding = next.isFinding();
			findBtn.setText(finding ? "Stop" : "Find");
			findBtn.setBackground(finding ? FIND_ACTIVE : ColorScheme.MEDIUM_GRAY_COLOR);
			setToolTip(findBtn, finding ? "Stop tracking this pet" : "Point a hint arrow toward this pet");
			findBtn.setEnabled(!next.isFollowing());

			if (finding != borderFinding)
			{
				borderFinding = finding;
				card.setBorder(cardBorder(finding));
			}

			String whereText;
			if (next.isFollowing())
			{
				whereText = "Following you";
			}
			else if (next.getDistance() >= 0)
			{
				whereText = next.getLocation() + " · " + next.getDistance() + " tiles";
			}
			else
			{
				whereText = next.getLocation();
			}
			whereLabel.setText(whereText);
			whereLabel.setForeground(next.isFollowing() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		}

		private void commitName()
		{
			String typed = nameField.getText().trim();
			if (typed.equals(data.getSpeciesName()))
			{
				typed = "";
			}
			String current = pendingName != null ? pendingName : nicknameOf(data);
			if (!typed.equals(current))
			{
				pendingName = typed;
				pendingStale = 0;
				onRename.accept(pet, typed.isEmpty() ? null : typed);
			}
		}

		private void confirmRemove()
		{
			int choice = JOptionPane.showConfirmDialog(FreeRangePetsPanel.this,
				"Stop tracking " + data.getDisplayName() + "? Visiting your POH Menagerie again will bring it back.",
				"Remove pet",
				JOptionPane.YES_NO_OPTION);
			if (choice == JOptionPane.YES_OPTION)
			{
				onRemove.accept(pet);
			}
		}
	}

	private static JButton createIconButton(String symbol, Color fg, String tooltip)
	{
		JButton btn = new JButton(symbol);
		btn.setFont(NAME_FONT);
		btn.setForeground(fg);
		btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		btn.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		btn.setFocusPainted(false);
		btn.setContentAreaFilled(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setToolTipText(tooltip);
		return btn;
	}

	private static JButton createActionButton(String text, Color bg, String tooltip)
	{
		JButton btn = new JButton(text);
		btn.setFont(SMALL_FONT);
		btn.setForeground(Color.WHITE);
		btn.setBackground(bg);
		btn.setMargin(new Insets(1, 6, 1, 6));
		btn.setFocusPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setToolTipText(tooltip);
		return btn;
	}
}
