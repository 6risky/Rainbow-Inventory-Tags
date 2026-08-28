/*
 * Copyright (c) 2026, AK
 * Copyright (c) 2018 kulers
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * This file's tag-color read/write format is structurally based on RuneLite's built-in Inventory
 * Tags plugin (InventoryTagsPlugin.java, Copyright (c) 2018 kulers) and its rainbow color-cycling
 * math on the Rainbow Rave plugin hub plugin (https://github.com/geheur/rainbow-rave, Copyright
 * (c) 2018, Adam <Adam@sigterm.info>), both used here under the BSD 2-Clause license below.
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
package com.rainbowinventorytags;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.awt.Color;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.inventorytags.InventoryTagsConfig;
import net.runelite.client.plugins.inventorytags.InventoryTagsPlugin;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;

/**
 * Rainbow Inventory Tags
 * <p>
 * Companion plugin for RuneLite's built-in "Inventory Tags" plugin. Instead of a single global
 * switch that turns every tagged item rainbow (which is all the "Inventory tags" option on the
 * Rainbow Rave plugin hub plugin can do - it can't tell your tag color groups apart), this lets
 * you pick, per tag color, whether that whole group should cycle through a rainbow or keep its
 * normal static color. So you can keep your red and blue tag groups as-is and only make (say) a
 * "junk to drop" group flash rainbow, without disturbing the rest of your color coding.
 * <p>
 * Tagging itself still happens exactly the way it always has, through the vanilla Inventory Tags
 * plugin (shift + right click an item -&gt; Examine -&gt; Inventory tag). This plugin adds a
 * "Rainbow tags" submenu to that same menu with: a toggle for the hovered item's tag color group,
 * a one-click "tag + rainbow" for items that aren't tagged yet, a per-group cycle speed override,
 * and bulk "rainbow all / remove all" actions across every tag color you've ever used.
 */
@Slf4j
@PluginDescriptor(
	name = "Rainbow Inventory Tags",
	description = "Pick which Inventory Tags color groups cycle through a rainbow, leaving your other tag colors alone",
	tags = {"inventory", "tag", "tags", "rainbow", "color", "highlight", "group"}
)
@PluginDependency(InventoryTagsPlugin.class)
public class RainbowInventoryTagsPlugin extends Plugin
{
	static final String GROUP = "rainbowinventorytags";

	// Matches net.runelite.client.plugins.inventorytags.InventoryTagsPlugin.TAG_KEY_PREFIX.
	// We can't reference that constant directly since the field isn't public, so it's
	// duplicated here - if RuneLite ever changes it, getTagColor() below will just stop
	// finding any tags rather than throwing.
	private static final String INVENTORY_TAGS_KEY_PREFIX = "tag_";

	private static final String RAINBOW_KEY_PREFIX = "rainbowColor_";
	private static final String RAINBOW_SPEED_KEY_PREFIX = "rainbowSpeed_";
	private static final int MIN_SPEED_MS = 100;

	// Maximum number of discrete color steps per rainbow cycle, regardless of how fast/slow the
	// cycle is. Keeps the animation visually smooth while capping how many distinct colors get
	// requested from ItemManager's shared, size-limited outline cache per item - without this,
	// a slow color speed could ask for hundreds of distinct outline colors per item every cycle.
	// Kept lower than you might expect because "Shuffle animation" mode gives every item in a
	// group its own phase: several identical items that used to share one outline-cache entry
	// (same color at the same instant) can each land on a different one of these steps at once,
	// multiplying how many distinct colors are in play for that item at any given moment.
	private static final int ANIMATION_STEPS = 30;

	// Every item's Inventory Tags color, parsed once and reused until that item's tag actually
	// changes, instead of re-parsing JSON every render frame for every visible tagged item.
	private static final TagColor NO_TAG = new TagColor();
	private final Cache<Integer, TagColor> tagColorCache = CacheBuilder.newBuilder()
		.maximumSize(256)
		.build();

	// Whether a tag color is rainbow, and its effective cycle speed, keyed by that color's RGB -
	// otherwise every render frame for every rainbow-tagged item re-reads two ConfigManager
	// entries. Invalidated wholesale whenever our own config group changes (see onConfigChanged).
	private final Cache<Integer, RainbowState> rainbowStateCache = CacheBuilder.newBuilder()
		.maximumSize(256)
		.build();

	private static final class RainbowState
	{
		private final boolean rainbow;
		private final int speedMs;

		private RainbowState(boolean rainbow, int speedMs)
		{
			this.rainbow = rainbow;
			this.speedMs = speedMs;
		}
	}

	@Inject
	private Client client;

	@Inject
	private RainbowInventoryTagsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private InventoryTagsConfig inventoryTagsConfig;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	private RainbowInventoryTagsOverlay overlay;
	private RainbowInventoryTagsOverlay bankOverlay;

	@Override
	protected void startUp()
	{
		if (overlay == null)
		{
			overlay = new RainbowInventoryTagsOverlay(itemManager, this, inventoryTagsConfig, false);
		}
		if (bankOverlay == null)
		{
			bankOverlay = new RainbowInventoryTagsOverlay(itemManager, this, inventoryTagsConfig, true);
		}

		overlayManager.add(overlay);
		if (config.showInBank())
		{
			overlayManager.add(bankOverlay);
		}

		// Caches are only ever invalidated reactively via ConfigChanged, which doesn't fire while
		// the plugin (or Inventory Tags) is disabled - so a tag or rainbow setting changed during
		// that window could otherwise leave a stale entry sitting here indefinitely. Starting
		// clean every time is cheap insurance against that narrow edge case.
		tagColorCache.invalidateAll();
		rainbowStateCache.invalidateAll();

		pruneOrphanedRainbowConfig();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(bankOverlay);
	}

	@Provides
	RainbowInventoryTagsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RainbowInventoryTagsConfig.class);
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		final MenuEntry[] entries = event.getMenuEntries();
		for (int idx = entries.length - 1; idx >= 0; --idx)
		{
			final MenuEntry entry = entries[idx];
			final Widget w = entry.getWidget();

			if (w != null && WidgetUtil.componentToInterface(w.getId()) == InterfaceID.INVENTORY
				&& "Examine".equals(entry.getOption()) && entry.getIdentifier() == 10)
			{
				addRainbowTagsMenu(idx, entry, w.getItemId());
			}
		}
	}

	private void addRainbowTagsMenu(int idx, MenuEntry entry, int itemId)
	{
		final Color tagColor = getTagColor(itemId);

		final MenuEntry parent = client.createMenuEntry(idx)
			.setOption("Rainbow tags")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE);
		final Menu submenu = parent.createSubMenu();

		if (tagColor == null)
		{
			// Item isn't tagged yet - offer to tag it with a fresh color and make it rainbow
			// in one click, instead of tagging then separately toggling rainbow on it.
			final Color freshColor = randomVividColor();
			submenu.createMenuEntry(0)
				.setOption(ColorUtil.prependColorTag("Tag + rainbow this item", freshColor))
				.setType(MenuAction.RUNELITE)
				.onClick(e ->
				{
					setTagColor(itemId, freshColor);
					setRainbow(freshColor, true);
				});
		}
		else
		{
			final boolean rainbow = isRainbow(tagColor);
			submenu.createMenuEntry(0)
				.setOption(ColorUtil.prependColorTag(rainbow ? "Remove rainbow" : "Rainbow tag", tagColor))
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setRainbow(tagColor, !rainbow));

			if (rainbow)
			{
				submenu.createMenuEntry(0)
					.setOption(ColorUtil.prependColorTag("Set speed for this group", tagColor))
					.setType(MenuAction.RUNELITE)
					.onClick(e -> openSpeedPrompt(tagColor));

				if (hasSpeedOverride(tagColor))
				{
					submenu.createMenuEntry(0)
						.setOption(ColorUtil.prependColorTag("Reset speed for this group", tagColor))
						.setType(MenuAction.RUNELITE)
						.onClick(e -> clearSpeedOverride(tagColor));
				}
			}
		}

		submenu.createMenuEntry(0)
			.setOption("Rainbow all tag groups")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> setRainbowForAllGroups(true));

		submenu.createMenuEntry(0)
			.setOption("Remove rainbow from all groups")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> setRainbowForAllGroups(false));
	}

	private void openSpeedPrompt(Color tagColor)
	{
		chatboxPanelManager.openTextInput("Rainbow speed for this group (ms per cycle, min " + MIN_SPEED_MS + ")")
			.value(String.valueOf(getEffectiveSpeedMs(tagColor)))
			.addCharValidator(Character::isDigit)
			.onDone(input ->
			{
				if (input == null || input.isEmpty())
				{
					return;
				}

				try
				{
					int ms = Integer.parseInt(input.trim());
					if (ms >= MIN_SPEED_MS)
					{
						setSpeedOverride(tagColor, ms);
					}
				}
				catch (NumberFormatException ignored)
				{
				}
			})
			.build();
	}

	private static Color randomVividColor()
	{
		return Color.getHSBColor((float) Math.random(), 0.85f + (float) Math.random() * 0.15f, 1f);
	}

	/**
	 * Invalidate our parsed-tag cache whenever the vanilla Inventory Tags plugin's config changes
	 * (someone tagged/retagged/reset an item), so we don't keep rendering a stale color forever.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(InventoryTagsConfig.GROUP))
		{
			tagColorCache.invalidateAll();

			// Only worth scanning for orphaned rainbow/speed settings when an actual tag
			// assignment changed, not for every unrelated Inventory Tags setting (fill opacity,
			// outline toggle, etc.) which can fire repeatedly while dragging a slider.
			if (event.getKey() != null && event.getKey().startsWith(INVENTORY_TAGS_KEY_PREFIX))
			{
				pruneOrphanedRainbowConfig();
			}
		}
		else if (event.getGroup().equals(GROUP))
		{
			// Covers the rainbow toggle, per-group speed overrides, and the global default speed -
			// all cheap to just recompute on next use rather than trying to patch individual
			// entries.
			rainbowStateCache.invalidateAll();

			if ("showInBank".equals(event.getKey()))
			{
				if (config.showInBank())
				{
					overlayManager.add(bankOverlay);
				}
				else
				{
					overlayManager.remove(bankOverlay);
				}
			}
		}
	}

	/**
	 * Reads the color the vanilla Inventory Tags plugin has stored for this item, if any. Cached
	 * per item ID since this is called every render frame for every visible item.
	 */
	Color getTagColor(int itemId)
	{
		TagColor tag = tagColorCache.getIfPresent(itemId);
		if (tag == null)
		{
			tag = parseTagColor(itemId);
			tagColorCache.put(itemId, tag);
		}
		return tag == NO_TAG ? null : tag.getColor();
	}

	private TagColor parseTagColor(int itemId)
	{
		String json = configManager.getConfiguration(InventoryTagsConfig.GROUP, INVENTORY_TAGS_KEY_PREFIX + itemId);
		if (json == null || json.isEmpty())
		{
			return NO_TAG;
		}

		try
		{
			TagColor tag = gson.fromJson(json, TagColor.class);
			return tag == null ? NO_TAG : tag;
		}
		catch (JsonSyntaxException e)
		{
			log.debug("Unable to parse inventory tag color for item {}", itemId, e);
			return NO_TAG;
		}
	}

	/**
	 * Writes a new Inventory Tags color for this item, in the same format the vanilla plugin uses,
	 * so it shows up as tagged there too (until we override its render with a rainbow color).
	 */
	void setTagColor(int itemId, Color color)
	{
		TagColor tag = new TagColor();
		tag.setColor(color);
		configManager.setConfiguration(InventoryTagsConfig.GROUP, INVENTORY_TAGS_KEY_PREFIX + itemId, gson.toJson(tag));
		// setConfiguration() only posts ConfigChanged (which clears our cache in
		// onConfigChanged) when the value actually differs from before, so invalidate this
		// item's cache entry directly too as a safety net for the no-op-write edge case.
		tagColorCache.invalidate(itemId);
	}

	/**
	 * Whether every item tagged with this exact color should be rendered as rainbow. Backed by
	 * {@link #rainbowStateCache} since this (and {@link #getEffectiveSpeedMs}) is called every
	 * render frame for every rainbow-tagged item.
	 */
	boolean isRainbow(Color tagColor)
	{
		return rainbowState(tagColor).rainbow;
	}

	void setRainbow(Color tagColor, boolean rainbow)
	{
		if (rainbow)
		{
			configManager.setConfiguration(GROUP, rainbowKey(tagColor), "true");
		}
		else
		{
			configManager.unsetConfiguration(GROUP, rainbowKey(tagColor));
		}
		// setConfiguration()/unsetConfiguration() only post ConfigChanged (which invalidates the
		// whole cache in onConfigChanged) when the value actually changes, so invalidate this
		// entry directly too as a safety net for the no-op-write edge case.
		rainbowStateCache.invalidate(tagColor.getRGB());
	}

	private RainbowState rainbowState(Color tagColor)
	{
		int key = tagColor.getRGB();
		RainbowState state = rainbowStateCache.getIfPresent(key);
		if (state == null)
		{
			boolean rainbow = "true".equals(configManager.getConfiguration(GROUP, rainbowKey(tagColor)));
			state = new RainbowState(rainbow, readEffectiveSpeedMs(tagColor));
			rainbowStateCache.put(key, state);
		}
		return state;
	}

	/**
	 * Every distinct tag color currently used by any item (whether or not it's in your inventory
	 * right now), for the "rainbow all / remove all" bulk actions.
	 */
	private Set<Color> getAllTagColors()
	{
		Set<Color> colors = new HashSet<>();
		String prefix = InventoryTagsConfig.GROUP + "." + INVENTORY_TAGS_KEY_PREFIX;
		for (String fullKey : configManager.getConfigurationKeys(prefix))
		{
			String key = fullKey.substring(InventoryTagsConfig.GROUP.length() + 1);
			String json = configManager.getConfiguration(InventoryTagsConfig.GROUP, key);
			if (json == null || json.isEmpty())
			{
				continue;
			}

			try
			{
				TagColor tag = gson.fromJson(json, TagColor.class);
				if (tag != null && tag.getColor() != null)
				{
					colors.add(tag.getColor());
				}
			}
			catch (JsonSyntaxException e)
			{
				log.debug("Unable to parse inventory tag color for key {}", key, e);
			}
		}
		return colors;
	}

	private void setRainbowForAllGroups(boolean rainbow)
	{
		for (Color color : getAllTagColors())
		{
			setRainbow(color, rainbow);
		}
	}

	private static String rainbowKey(Color color)
	{
		return RAINBOW_KEY_PREFIX + color.getRGB();
	}

	private static String speedKey(Color color)
	{
		return RAINBOW_SPEED_KEY_PREFIX + color.getRGB();
	}

	boolean hasSpeedOverride(Color tagColor)
	{
		return configManager.getConfiguration(GROUP, speedKey(tagColor)) != null;
	}

	void setSpeedOverride(Color tagColor, int ms)
	{
		configManager.setConfiguration(GROUP, speedKey(tagColor), String.valueOf(ms));
		rainbowStateCache.invalidate(tagColor.getRGB());
	}

	void clearSpeedOverride(Color tagColor)
	{
		configManager.unsetConfiguration(GROUP, speedKey(tagColor));
		rainbowStateCache.invalidate(tagColor.getRGB());
	}

	/**
	 * The cycle speed to use for this tag color: its own override if it has one, otherwise the
	 * plugin-wide default from the config panel. Cached via {@link #rainbowState}.
	 */
	int getEffectiveSpeedMs(Color tagColor)
	{
		return rainbowState(tagColor).speedMs;
	}

	private int readEffectiveSpeedMs(Color tagColor)
	{
		String override = configManager.getConfiguration(GROUP, speedKey(tagColor));
		if (override != null)
		{
			try
			{
				int ms = Integer.parseInt(override);
				if (ms > 0)
				{
					return ms;
				}
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return config.colorSpeed();
	}

	/**
	 * The animated color to draw a rainbow-enabled tag with right now. Phase-shifted per tag color
	 * group (unless "sync all rainbow groups" is on) so different rainbow groups don't necessarily
	 * flash in lockstep, and using that group's own speed override if it has one.
	 * <p>
	 * Timing and color-step count are deliberately independent here. {@code positionInCycle} is
	 * computed against the exact requested cycle length ({@code cycleGameCycles}), so the cycle
	 * always takes exactly as long as configured, no matter what it divides into. Separately, that
	 * position is snapped down to one of at most {@link #ANIMATION_STEPS} evenly-spaced values
	 * purely to bound how many distinct colors get requested from ItemManager's shared,
	 * size-limited outline cache per item - an earlier version derived the cycle length itself from
	 * that step count, which meant most speeds that weren't an exact multiple of the step duration
	 * quietly ran short (e.g. a requested 2000ms cycle actually running at 1200ms).
	 *
	 * @param shuffleSeed extra per-item phase offset used only when "shuffle" mode is on, so items
	 *                    sharing a rainbow group can drift out of sync with each other instead of
	 *                    all pulsing in lockstep; ignored otherwise.
	 */
	Color getRainbowColor(Color tagColor, int shuffleSeed)
	{
		int hashCode = tagColor.getRGB();
		int cycleGameCycles = Math.max(1, getEffectiveSpeedMs(tagColor) / 20);
		int phase = config.syncColors() ? 0 : hashCode;
		if (config.shuffleMode())
		{
			phase += shuffleSeed;
		}
		int gameCycle = client.getGameCycle();

		// floorMod (rather than %) always lands in [0, cycleGameCycles) even though phase can be
		// negative (tag colors are opaque ARGB ints, so getRGB() is usually negative).
		long positionInCycle = Math.floorMod((long) phase + gameCycle, (long) cycleGameCycles);
		float rawRatio = positionInCycle / (float) cycleGameCycles;

		int steps = Math.max(1, Math.min(ANIMATION_STEPS, cycleGameCycles));
		float ratio = (float) Math.floor(rawRatio * steps) / steps;

		if (config.animationStyle() == RainbowInventoryTagsConfig.AnimationStyle.PASTEL_PULSE)
		{
			float saturation = 0.45f;
			float brightness = 0.75f + 0.25f * (float) ((Math.sin(ratio * 2 * Math.PI) + 1) / 2);
			return Color.getHSBColor(ratio, saturation, brightness);
		}

		return Color.getHSBColor(ratio, 1f, 1f);
	}

	/**
	 * Removes rainbow/speed settings for tag colors nobody uses anymore, so they don't accumulate
	 * forever in RuneLite's config store and can't unexpectedly reactivate if that exact color is
	 * ever reused by a new tag later.
	 */
	private void pruneOrphanedRainbowConfig()
	{
		Set<Integer> usedRgbs = new HashSet<>();
		for (Color color : getAllTagColors())
		{
			usedRgbs.add(color.getRGB());
		}

		pruneOrphanedKeys(RAINBOW_KEY_PREFIX, usedRgbs);
		pruneOrphanedKeys(RAINBOW_SPEED_KEY_PREFIX, usedRgbs);
	}

	private void pruneOrphanedKeys(String prefix, Set<Integer> usedRgbs)
	{
		String fullPrefix = GROUP + "." + prefix;
		for (String fullKey : configManager.getConfigurationKeys(fullPrefix))
		{
			String key = fullKey.substring(GROUP.length() + 1);
			try
			{
				int rgb = Integer.parseInt(key.substring(prefix.length()));
				if (!usedRgbs.contains(rgb))
				{
					configManager.unsetConfiguration(GROUP, key);
				}
			}
			catch (NumberFormatException e)
			{
				log.debug("Unexpected rainbow config key format: {}", key);
			}
		}
	}
}
