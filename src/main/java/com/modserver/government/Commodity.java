package com.modserver.government;

import com.modserver.config.ModServerConfig;
import com.modserver.economy.NationEconomy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.Locale;

public enum Commodity {
    IRON(Items.IRON_INGOT, ModServerConfig.IRON_PRICE),
    GOLD(Items.GOLD_INGOT, ModServerConfig.GOLD_PRICE),
    DIAMOND(Items.DIAMOND, ModServerConfig.DIAMOND_PRICE),
    NETHERITE(Items.NETHERITE_INGOT, ModServerConfig.NETHERITE_PRICE);

    public final Item item;
    private final ModConfigSpec.IntValue rate;
    Commodity(Item item, ModConfigSpec.IntValue rate) { this.item = item; this.rate = rate; }
    public long price() { return rate.get() * NationEconomy.CENTS; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
}
