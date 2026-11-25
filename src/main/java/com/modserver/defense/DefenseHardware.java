package com.modserver.defense;

import com.modserver.ModServer;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

import java.util.EnumMap;

public final class DefenseHardware {
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModServer.MOD_ID);
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModServer.MOD_ID);
    static final DeferredRegister<BlockEntityType<?>> MACHINES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModServer.MOD_ID);
    static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ModServer.MOD_ID);
    public static final EnumMap<DefenseTier, DeferredBlock<TurretBlock>> TURRETS =
            new EnumMap<>(DefenseTier.class);

    static {
        for (var tier : DefenseTier.values()) {
            var block =
                    BLOCKS.register(
                            tier.id(),
                            () ->
                                    new TurretBlock(
                                            tier,
                                            BlockBehaviour.Properties.of()
                                                    .strength(4)
                                                    .sound(SoundType.COPPER)
                                                    .noOcclusion()));
            TURRETS.put(tier, block);
            ITEMS.registerSimpleBlockItem(block);
        }
    }

    public static final DeferredItem<Item> SLUG = ITEMS.registerSimpleItem("brass_slug");
    public static final DeferredItem<Item> ROCKET = ITEMS.registerSimpleItem("skyrocket");
    public static final DeferredItem<Item> INTERCEPTOR = ITEMS.registerSimpleItem("interceptor");
    public static final DeferredItem<DefenseSupply> FLARE =
            ITEMS.registerItem("flare_cartridge", p -> new DefenseSupply(p, false));
    public static final DeferredItem<DefenseSupply> KEY =
            ITEMS.registerItem("survey_key", p -> new DefenseSupply(p.stacksTo(1), true));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretEntity>> TURRET =
            MACHINES.register(
                    "territorial_turret",
                    () ->
                            BlockEntityType.Builder.of(
                                            TurretEntity::new,
                                            TURRETS.values().stream()
                                                    .map(DeferredBlock::get)
                                                    .toArray(TurretBlock[]::new))
                                    .build(null));
    public static final DeferredHolder<EntityType<?>, EntityType<DefenseRound>> ROUND =
            ENTITIES.register(
                    "defense_round",
                    () ->
                            EntityType.Builder.<DefenseRound>of(DefenseRound::new, MobCategory.MISC)
                                    .sized(.25f, .25f)
                                    .clientTrackingRange(10)
                                    .updateInterval(1)
                                    .build("modserver:defense_round"));

    static Item ammo(DefenseTier tier) {
        return switch (tier) {
            case WATCHTOWER -> Items.ARROW;
            case BASTION -> SLUG.get();
            case SKYGUARD -> ROCKET.get();
            case AEGIS -> INTERCEPTOR.get();
        };
    }

    public static void install(IEventBus bus) {
        bus.addListener(
                (net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) ->
                        event.enqueueWork(DefenseSupply::registerDispenser));
        BLOCKS.register(bus);
        ITEMS.register(bus);
        MACHINES.register(bus);
        ENTITIES.register(bus);
        bus.addListener(
                (net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
                    if (event.getTabKey() == CreativeModeTabs.COMBAT)
                        ITEMS.getEntries().forEach(item -> event.accept(item.get()));
                });
    }

    private DefenseHardware() {}
}
