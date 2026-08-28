/*
 * Copyright (c) 2026, AK
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
package com.rainbowinventorytags;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(RainbowInventoryTagsConfig.GROUP)
public interface RainbowInventoryTagsConfig extends Config
{
	String GROUP = "rainbowinventorytags";

	@Range(min = 100)
	@ConfigItem(
		keyName = "colorSpeed",
		name = "Color speed (ms)",
		description = "How fast rainbow-enabled tags cycle through colors, in milliseconds per full cycle.",
		position = 0
	)
	default int colorSpeed()
	{
		return 6000;
	}

	@ConfigItem(
		keyName = "syncColors",
		name = "Sync all rainbow groups",
		description = "Make every rainbow-enabled tag group flash the same color at the same time. When off, each " +
			"tag color group cycles on its own, out of phase with the others.",
		position = 1
	)
	default boolean syncColors()
	{
		return false;
	}

	enum AnimationStyle
	{
		RAINBOW,
		PASTEL_PULSE
	}

	@ConfigItem(
		keyName = "animationStyle",
		name = "Animation style",
		description = "Rainbow cycles full-saturation, full-brightness hues. Pastel pulse cycles softer, " +
			"lower-saturation colors with a gentle brightness pulse instead.",
		position = 2
	)
	default AnimationStyle animationStyle()
	{
		return AnimationStyle.RAINBOW;
	}

	@ConfigItem(
		keyName = "showInBank",
		name = "Show tags in bank",
		description = "Inventory Tags itself never shows tag colors in bank storage, static or rainbow. Turn this " +
			"on to show both there too, matching how tagged items look in your inventory. Turn it off for a " +
			"plain, undecorated bank.",
		position = 3
	)
	default boolean showInBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shuffleMode",
		name = "Shuffle animation",
		description = "Give every item in a rainbow group its own slightly randomized color offset instead of " +
			"the whole group pulsing through the rainbow in the same neat phase-locked pattern. Feels more " +
			"chaotic and festive, less like a synchronized light show. Purely cosmetic - it doesn't change " +
			"which items are rainbow, only how in or out of sync they look with each other.",
		position = 4
	)
	default boolean shuffleMode()
	{
		return false;
	}
}
