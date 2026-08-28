package com.rainbowinventorytags;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RainbowInventoryTagsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RainbowInventoryTagsPlugin.class);
		RuneLite.main(args);
	}
}
