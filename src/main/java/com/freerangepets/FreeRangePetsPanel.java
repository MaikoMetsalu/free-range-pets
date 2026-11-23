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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
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
 */
class FreeRangePetsPanel extends PluginPanel
{
	private static final Font HEADING_FONT = FontManager.getRunescapeBoldFont().deriveFont(18f);
	private static final Font NAME_FONT = FontManager.getRunescapeFont().deriveFont(15f);
	private static final Font SMALL_FONT = FontManager.getRunescapeSmallFont();
	private static final Color STAR_ON = new Color(0xFFD23F);
	private static final Color STAR_OFF = new Color(110, 110, 110);
	private static final Color FIND_ACTIVE = new Color(0x4C8C5A);

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
				rebuildList();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuildList();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuildList();
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

		add(header, BorderLayout.NORTH);
		add(listContainer, BorderLayout.CENTER);

		rebuildList();
	}

	void updateRows(List<RowData> rows)
	{
		List<RowData> copy = new ArrayList<>(rows);
		SwingUtilities.invokeLater(() ->
		{
			Component focusOwner = SwingUtilities.getWindowAncestor(this) == null
				? null
				: SwingUtilities.getWindowAncestor(this).getFocusOwner();
			if (focusOwner instanceof JTextField && SwingUtilities.isDescendingFrom(focusOwner, listContainer))
			{
				return;
			}
			currentRows = copy;
			rebuildList();
		});
	}

	private void rebuildList()
	{
		listContainer.removeAll();
		int total = currentRows.size();
		scatterButton.setVisible(total > 1);

		if (total == 0)
		{
			headerCount.setText("No roaming pets yet");
			listContainer.add(buildEmptyCard(
				"Visit your POH Menagerie to let your pets roam freely across Gielinor."
			));
			listContainer.revalidate();
			listContainer.repaint();
			return;
		}

		headerCount.setText(total == 1 ? "1 pet roaming Gielinor" : total + " pets roaming Gielinor");

		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		int shown = 0;
		for (RowData row : currentRows)
		{
			if (!query.isEmpty() && !matches(row, query))
			{
				continue;
			}
			listContainer.add(buildPetCard(row));
			listContainer.add(Box.createVerticalStrut(6));
			shown++;
		}

		if (shown == 0)
		{
			listContainer.add(buildEmptyCard("No pets match \"" + searchField.getText().trim() + "\"."));
		}

		listContainer.revalidate();
		listContainer.repaint();
	}

	private static boolean matches(RowData row, String query)
	{
		return row.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
			|| row.getSpeciesName().toLowerCase(Locale.ROOT).contains(query)
			|| row.getLocation().toLowerCase(Locale.ROOT).contains(query);
	}

	private JPanel buildEmptyCard(String message)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(12, 10, 12, 10));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));

		JLabel label = new JLabel("<html><body style='width: 175px; text-align: center;'>"
			+ message + "</body></html>", SwingConstants.CENTER);
		label.setFont(SMALL_FONT);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		card.add(label, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildPetCard(RowData row)
	{
		JPanel card = new JPanel(new BorderLayout(4, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(row.isFinding() ? FIND_ACTIVE : ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(6, 7, 6, 7)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

		JButton starBtn = createIconButton(row.isFavourite() ? "★" : "☆",
			row.isFavourite() ? STAR_ON : STAR_OFF,
			row.isFavourite() ? "Unstar pet" : "Star pet to pin at top");
		starBtn.addActionListener(e -> onStar.accept(row.getPet()));

		JTextField nameField = new JTextField(row.getDisplayName());
		nameField.setFont(NAME_FONT);
		nameField.setForeground(Color.WHITE);
		nameField.setCaretColor(Color.WHITE);
		nameField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nameField.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		nameField.setToolTipText(row.getSpeciesName() + " — click to rename");
		Runnable commitName = () ->
		{
			String typed = nameField.getText().trim();
			if (typed.equals(row.getSpeciesName()))
			{
				typed = "";
			}
			String prev = row.getPet().getNickname() == null ? "" : row.getPet().getNickname();
			if (!typed.equals(prev))
			{
				onRename.accept(row.getPet(), typed.isEmpty() ? null : typed);
			}
		};
		nameField.addActionListener(e ->
		{
			commitName.run();
			listContainer.requestFocusInWindow();
		});
		nameField.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				commitName.run();
			}
		});

		JButton findBtn = createActionButton(row.isFinding() ? "Stop" : "Find",
			row.isFinding() ? FIND_ACTIVE : ColorScheme.MEDIUM_GRAY_COLOR,
			row.isFinding() ? "Stop tracking this pet" : "Point a hint arrow toward this pet");
		findBtn.setEnabled(!row.isFollowing());
		findBtn.addActionListener(e -> onFind.accept(row.getPet()));

		JButton removeBtn = createIconButton("×", ColorScheme.LIGHT_GRAY_COLOR,
			"Remove from roaming list (re-visiting your POH Menagerie will add it again)");
		removeBtn.addActionListener(e -> onRemove.accept(row.getPet()));

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actions.add(findBtn);
		actions.add(removeBtn);

		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		top.add(starBtn, BorderLayout.WEST);
		top.add(nameField, BorderLayout.CENTER);
		top.add(actions, BorderLayout.EAST);

		String whereText;
		if (row.isFollowing())
		{
			whereText = "Following you";
		}
		else if (row.getDistance() >= 0)
		{
			whereText = row.getLocation() + " · " + row.getDistance() + " tiles";
		}
		else
		{
			whereText = row.getLocation();
		}
		JLabel whereLabel = new JLabel(whereText);
		whereLabel.setFont(SMALL_FONT);
		whereLabel.setForeground(row.isFollowing() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		whereLabel.setBorder(new EmptyBorder(0, 18, 0, 0));

		card.add(top, BorderLayout.NORTH);
		card.add(whereLabel, BorderLayout.SOUTH);
		return card;
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
