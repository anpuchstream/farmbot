package com.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class FarmBotClient implements ClientModInitializer {
    public static KeyBinding openKey;
    public static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        FarmConfig.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.farmbot"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.farmbot"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) mc.setScreen(new FarmScreen());
            while (toggleKey.wasPressed()) FarmBot.toggle(mc);
            FarmBot.tick(mc);
        });
    }
}
