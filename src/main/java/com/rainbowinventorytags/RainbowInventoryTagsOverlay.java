/*
 * Copyright (c) 2026, AK
 * Copyright (c) 2018 kulers
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * This file's render loop and outline/fill/underline drawing is structurally based on RuneLite's
 * built-in Inventory Tags plugin (InventoryTagsOverlay.java, Copyright (c) 2018 kulers) and its
 * fill-image caching approach on the Rainbow Rave plugin hub plugin
 * (https://github.com/geheur/rainbow-rave, Copyright (c) 2018, Adam <Adam@sigterm.info>), both
 * used here under the BSD 2-Clause license below.
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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.inventorytags.InventoryTagsConfig;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Draws tag colors for RuneLite's built-in Inventory Tags plugin, in two different scopes:
 * <p>
 * The normal, non-bank instance ({@code bankScoped = false}) draws over the top of the vanilla
 * Inventory Tags overlay in your inventory/equipment. It only paints anything for items whose tag
 * color has been marked as "rainbow" via {@link RainbowInventoryTagsPlugin}; every other tagged item
 * is left alone so its normal static tag color (drawn underneath by the Inventory Tags plugin
 * itself) keeps showing through untouched.
 * <p>
 * The bank-scoped instance ({@code bankScoped = true}) covers a gap in the vanilla plugin:
 * Inventory Tags doesn't draw tag colors on items sitting in bank storage at all, static or
 * otherwise. So in the bank, this instance draws BOTH the rainbow animation for rainbow-enabled
 * groups AND the plain static tag color for everything else that's tagged - matching what you'd
 * see in your inventory, instead of rainbow items suddenly appearing while everything else stays
 * plain. It's only added to the overlay manager while the "Animate in bank" setting is on.
 */
class RainbowInventoryTagsOverlay extends WidgetItemOverlay
{
	private final ItemManager itemManager;
	private final RainbowInventoryTagsPlugin plugin;
	private final InventoryTagsConfig inventoryTagsConfig;
	private final boolean bankScoped;

	// The rainbow color only actually changes a bounded number of times per cycle (see
	// RainbowInventoryTagsPlugin.ANIMATION_STEPS), so most consecutive render frames ask for the
	// exact same (itemId, quantity, color) fill image. Caching it avoids re-allocating and
	// re-filling a BufferedImage on every single frame for every rainbow-filled item.
	private final Cache<String, Image> fillCache = CacheBuilder.newBuilder()
		.maximumSize(64)
		.expireAfterAccess(10, TimeUnit.SECONDS)
		.build();

	RainbowInventoryTagsOverlay(ItemManager itemManager, RainbowInventoryTagsPlugin plugin, InventoryTagsConfig inventoryTagsConfig, boolean bankScoped)
	{
		this.itemManager = itemManager;
		this.plugin = plugin;
		this.inventoryTagsConfig = inventoryTagsConfig;
		this.bankScoped = bankScoped;
		if (bankScoped)
		{
			showOnBank();
		}
		else
		{
			showOnEquipment();
			showOnInventory();
			showOnInterfaces(
				InterfaceID.RAIDS_STORAGE_SIDE,
				InterfaceID.RAIDS_STORAGE_PRIVATE,
				InterfaceID.RAIDS_STORAGE_SHARED,
				InterfaceID.GRAVESTONE_GENERIC
			);
		}
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		final Color tagColor = plugin.getTagColor(itemId);
		if (tagColor == null)
		{
			return;
		}

		final Color color;
		if (plugin.isRainbow(tagColor))
		{
			color = plugin.getRainbowColor(tagColor, shuffleSeed(widgetItem));
		}
		else if (bankScoped)
		{
			// Vanilla Inventory Tags doesn't render in the bank at all, so this instance fills
			// that gap for non-rainbow tags too, instead of only the rainbow ones showing up.
			color = tagColor;
		}
		else
		{
			// Non-rainbow tag outside the bank - the vanilla overlay already draws this.
			return;
		}

		final Rectangle bounds = widgetItem.getCanvasBounds();
		if (inventoryTagsConfig.showTagOutline())
		{
			final BufferedImage outline = itemManager.getItemOutline(itemId, widgetItem.getQuantity(), color);
			graphics.drawImage(outline, (int) bounds.getX(), (int) bounds.getY(), null);
		}

		if (inventoryTagsConfig.showTagFill())
		{
			final Image image = getFillImage(color, widgetItem.getId(), widgetItem.getQuantity());
			graphics.drawImage(image, (int) bounds.getX(), (int) bounds.getY(), null);
		}

		if (inventoryTagsConfig.showTagUnderline())
		{
			int heightOffSet = (int) bounds.getY() + (int) bounds.getHeight() + 2;
			graphics.setColor(color);
			graphics.drawLine((int) bounds.getX(), heightOffSet, (int) bounds.getX() + (int) bounds.getWidth(), heightOffSet);
		}
	}

	private Image getFillImage(Color color, int itemId, int qty)
	{
		// Fill opacity is baked into the cached image (via colorWithAlpha below), so it has to be
		// part of the cache key too - otherwise changing the Inventory Tags "Fill opacity" setting
		// wouldn't actually change anything on screen until every existing cache entry aged out.
		final int opacity = inventoryTagsConfig.fillOpacity();
		final String key = itemId + ":" + qty + ":" + color.getRGB() + ":" + opacity;
		Image image = fillCache.getIfPresent(key);
		if (image == null)
		{
			final Color fillColor = ColorUtil.colorWithAlpha(color, opacity);
			image = ImageUtil.fillImage(itemManager.getImage(itemId, qty, false), fillColor);
			fillCache.put(key, image);
		}
		return image;
	}

	/**
	 * A stable-while-the-item-stays-put seed for "shuffle" mode, so each item gets its own
	 * independent color phase instead of every item in a group moving in lockstep. Derived from
	 * where the item is drawn rather than an item index, since {@link WidgetItem} doesn't expose
	 * one - this reshuffles if the item moves slots, which is a fine trade-off for a purely
	 * cosmetic effect.
	 */
	private static int shuffleSeed(WidgetItem widgetItem)
	{
		final Rectangle bounds = widgetItem.getCanvasBounds();
		return bounds.x * 92821 + bounds.y * 68917 + widgetItem.getId() * 7919;
	}
}
