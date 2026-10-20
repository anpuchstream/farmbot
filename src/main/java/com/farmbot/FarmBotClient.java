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
    /** Recorder: iniciar/parar a gravação e reproduzir. Dá para trocar na aba RECORDER do menu ou em Controles. */
    public static KeyBinding recordKey;
    public static KeyBinding replayKey;

    @Override
    public void onInitializeClient() {
        FarmConfig.load();
        Recorder.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.farmbot"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.farmbot"));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.farmbot"));
        replayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.replay", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.farmbot"));

        // O replay aplica os comandos no COMEÇO do tick, antes de o jogo processar movimento e cliques.
        ClientTickEvents.START_CLIENT_TICK.register(Recorder::startTick);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) mc.setScreen(new FarmScreen());
            while (toggleKey.wasPressed()) FarmBot.toggle(mc);
            // grava primeiro o tick que acabou de rodar; só depois trata os atalhos
            Recorder.endTick(mc);
            while (recordKey.wasPressed()) Recorder.onRecordKey(mc);
            while (replayKey.wasPressed()) Recorder.onReplayKey(mc);
            FarmBot.tick(mc);
        });
    }
}
