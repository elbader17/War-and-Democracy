package com.modserver.client;

import com.modserver.client.map.JourneyMapScreenOpener;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ModServerKeyBindings {
    // Keep the binding ID so players retain their saved key assignments.
    private static final KeyMapping OPEN_MAP = new KeyMapping("key.modserver.nation_map",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.modserver");

    private static final KeyMapping GOVERNMENT = new KeyMapping("key.modserver.government",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.modserver");

    private static final KeyMapping TACTICS = new KeyMapping("key.modserver.tactics",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.modserver");

    private ModServerKeyBindings() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MAP);
        event.register(GOVERNMENT);
        event.register(TACTICS);
    }

    public static void pollMapKey(Minecraft mc) {
        if (TACTICS.consumeClick() && mc.player != null && mc.screen == null)
            mc.player.connection.sendCommand("nation tactics");
        if (GOVERNMENT.consumeClick() && mc.player != null && mc.screen == null)
            mc.player.connection.sendCommand(CouncilScreen.supported(mc) ? "nation council" : "nation panel");
        if (OPEN_MAP.consumeClick() && mc.player != null && mc.screen == null) {
            if (!JourneyMapScreenOpener.open(mc)) {
                mc.player.displayClientMessage(Component.translatable("message.modserver.terrain_map_unavailable"), true);
            }
        }
    }
}
