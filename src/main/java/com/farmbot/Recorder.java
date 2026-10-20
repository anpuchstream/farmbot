package com.farmbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gravador de movimentos.
 *
 * GRAVAR: a cada tick guarda o que o jogador está fazendo (W/A/S/D, pulo, agachar, correr, botão de
 * ataque, botão de uso, slot da hotbar, voo e para onde a câmera aponta).
 * REPLAY: reproduz esses mesmos comandos tick a tick, como se o jogador estivesse apertando as teclas
 * (nada de teleporte: o personagem anda, olha e quebra de verdade). Por isso o replay funciona melhor
 * quando começa no mesmo ponto onde a gravação começou.
 *
 * A gravação fica salva em config/farmbot_recording.bin e sobrevive ao fechar o jogo.
 */
public final class Recorder {
    public enum State { IDLE, RECORDING, PLAYING }

    // bits dos comandos guardados em cada tick
    private static final int F_FWD = 1, F_BACK = 1 << 1, F_LEFT = 1 << 2, F_RIGHT = 1 << 3,
            F_JUMP = 1 << 4, F_SNEAK = 1 << 5, F_SPRINT = 1 << 6, F_ATTACK = 1 << 7,
            F_USE = 1 << 8, F_FLY = 1 << 9;
    private static final int[] BITS = {F_FWD, F_BACK, F_LEFT, F_RIGHT, F_JUMP, F_SNEAK, F_SPRINT, F_ATTACK, F_USE};
    private static final int IDX_ATTACK = 7, IDX_USE = 8;

    /** Limite de uma gravação: 20 minutos (24000 ticks). */
    public static final int MAX_FRAMES = 24_000;
    private static final int MIN_FRAMES = 10;
    /** Salto de posição (em blocos, num tick) que conta como teleporte durante o replay. */
    private static final double TELEPORT_DIST = 8.0;
    /** Distância do ponto inicial a partir da qual o replay avisa que você não está no lugar certo. */
    private static final double START_WARN_DIST = 1.5;

    private record Frame(int flags, float yaw, float pitch, int slot) { }

    private static State state = State.IDLE;

    // ---- gravação guardada
    private static List<Frame> frames = new ArrayList<>();
    private static Vec3d startPos = Vec3d.ZERO, endPos = Vec3d.ZERO;
    private static boolean startFlying;

    // ---- gravando
    private static final List<Frame> building = new ArrayList<>();
    private static Vec3d bStart = Vec3d.ZERO, bLast = Vec3d.ZERO;
    private static boolean bStartFlying;
    private static ClientPlayerEntity recPlayer;
    private static ClientWorld recWorld;

    // ---- reproduzindo
    private static int idx, alignTicks, loops;
    private static int prevFlags;
    private static boolean aligning;
    private static ClientPlayerEntity playPlayer;
    private static ClientWorld playWorld;
    private static Vec3d lastPos;
    private static float lastHealth;

    // ================================================================== consulta (usada pela tela)
    public static State state() { return state; }

    public static boolean hasRecording() { return !frames.isEmpty(); }

    public static int frameCount() { return frames.size(); }

    /** Ticks gravados até agora (durante a gravação). */
    public static int liveFrames() { return building.size(); }

    public static int progressPercent() {
        return frames.isEmpty() ? 0 : Math.min(100, idx * 100 / frames.size());
    }

    public static Vec3d startPos() { return startPos; }

    public static String secs(int ticks) {
        return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
    }

    /** Nome da tecla de um atalho (ou NENHUMA). */
    public static String keyName(KeyBinding kb) {
        if (kb == null || "key.keyboard.unknown".equals(kb.getBoundKeyTranslationKey())) return "NENHUMA";
        return kb.getBoundKeyLocalizedText().getString().toUpperCase();
    }

    // ================================================================== atalhos
    public static void onRecordKey(MinecraftClient mc) {
        if (mc.player == null) return;
        switch (state) {
            case RECORDING -> stopRecording(mc);
            case PLAYING -> stopPlayback(mc, "§eReplay interrompido.");
            default -> startRecording(mc);
        }
    }

    public static void onReplayKey(MinecraftClient mc) {
        if (mc.player == null) return;
        switch (state) {
            case PLAYING -> stopPlayback(mc, "§eReplay interrompido.");
            case RECORDING -> chat(mc, "§cPare a gravação antes de reproduzir.");
            default -> startPlayback(mc);
        }
    }

    /** Chamado quando o Farm Bot liga: o gravador não pode brigar com ele pelo controle do jogador. */
    public static void cancelAll(MinecraftClient mc) {
        if (state == State.RECORDING) stopRecording(mc);
        else if (state == State.PLAYING) stopPlayback(mc, "§eReplay interrompido: o Farm Bot foi ligado.");
    }

    // ================================================================== gravar
    public static void startRecording(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (FarmConfig.enabled) {
            chat(mc, "§cDesligue o Farm Bot antes de gravar.");
            return;
        }
        if (state == State.PLAYING) stopPlayback(mc, null);
        building.clear();
        recPlayer = p;
        recWorld = mc.world;
        bStart = p.getPos();
        bLast = bStart;
        bStartFlying = p.getAbilities().flying;
        state = State.RECORDING;
        chat(mc, "§c● Gravando! Faça seus movimentos e aperte "
                + keyName(FarmBotClient.recordKey) + " de novo para parar.");
    }

    public static void stopRecording(MinecraftClient mc) {
        if (state != State.RECORDING) return;
        finishRecording(mc, null);
    }

    private static void finishRecording(MinecraftClient mc, String note) {
        state = State.IDLE;
        recPlayer = null;
        recWorld = null;
        if (building.size() < MIN_FRAMES) {
            building.clear();
            chat(mc, "§eGravação muito curta, descartada (a anterior foi mantida).");
            return;
        }
        frames = new ArrayList<>(building);
        building.clear();
        startPos = bStart;
        endPos = bLast;
        startFlying = bStartFlying;
        save();
        if (note != null) chat(mc, note);
        chat(mc, "§a■ Gravação salva: " + secs(frames.size()) + " s. Aperte "
                + keyName(FarmBotClient.replayKey) + " para reproduzir.");
    }

    private static int snapshotFlags(MinecraftClient mc, ClientPlayerEntity p) {
        GameOptions o = mc.options;
        int f = 0;
        if (o.forwardKey.isPressed()) f |= F_FWD;
        if (o.backKey.isPressed()) f |= F_BACK;
        if (o.leftKey.isPressed()) f |= F_LEFT;
        if (o.rightKey.isPressed()) f |= F_RIGHT;
        if (o.jumpKey.isPressed()) f |= F_JUMP;
        if (o.sneakKey.isPressed()) f |= F_SNEAK;
        if (o.sprintKey.isPressed() || p.isSprinting()) f |= F_SPRINT;
        if (o.attackKey.isPressed()) f |= F_ATTACK;
        if (o.useKey.isPressed()) f |= F_USE;
        if (p.getAbilities().flying) f |= F_FLY;
        return f;
    }

    /** Fim de cada tick: guarda o que o jogador fez neste tick. */
    public static void endTick(MinecraftClient mc) {
        if (state != State.RECORDING) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || p != recPlayer || mc.world != recWorld) {
            finishRecording(mc, "§eGravação encerrada: você saiu do mundo/servidor ou renasceu.");
            return;
        }
        if (FarmConfig.enabled) {
            finishRecording(mc, "§eGravação encerrada: o Farm Bot foi ligado.");
            return;
        }
        building.add(new Frame(snapshotFlags(mc, p), p.getYaw(), p.getPitch(), p.getInventory().selectedSlot));
        bLast = p.getPos();
        if (building.size() >= MAX_FRAMES) {
            finishRecording(mc, "§eLimite de 20 minutos atingido.");
        } else if (building.size() % 10 == 0) {
            FarmBot.msg(mc, "§c● Gravando " + secs(building.size()) + " s  (" + keyName(FarmBotClient.recordKey) + " para parar)");
        }
    }

    // ================================================================== reproduzir
    public static void startPlayback(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (frames.isEmpty()) {
            chat(mc, "§cNenhuma gravação ainda. Aperte " + keyName(FarmBotClient.recordKey) + " para gravar.");
            return;
        }
        if (state == State.RECORDING) {
            chat(mc, "§cPare a gravação antes de reproduzir.");
            return;
        }
        if (FarmConfig.enabled) {
            chat(mc, "§cDesligue o Farm Bot antes de reproduzir.");
            return;
        }
        playPlayer = p;
        playWorld = mc.world;
        lastPos = p.getPos();
        lastHealth = p.getHealth();
        idx = 0;
        loops = 0;
        prevFlags = 0;
        aligning = true;
        alignTicks = 0;
        HumanLook.reset();
        state = State.PLAYING;

        double d = p.getPos().distanceTo(startPos);
        if (d > START_WARN_DIST) {
            chat(mc, "§eVocê está a " + String.format(Locale.ROOT, "%.1f", d)
                    + " blocos do ponto onde a gravação começou (X " + (int) Math.floor(startPos.x)
                    + " Y " + (int) Math.floor(startPos.y) + " Z " + (int) Math.floor(startPos.z)
                    + "). O replay segue os mesmos comandos, então pode sair do caminho.");
        }
        chat(mc, "§a▶ Reproduzindo (" + secs(frames.size()) + " s)"
                + (FarmConfig.recorderLoop ? " em loop" : "") + ". " + keyName(FarmBotClient.replayKey) + " para parar.");
    }

    public static void stopPlayback(MinecraftClient mc, String note) {
        if (state != State.PLAYING) return;
        state = State.IDLE;
        playPlayer = null;
        playWorld = null;
        releaseKeys(mc);
        if (note != null) chat(mc, note);
    }

    /** Para o replay por causa da proteção (teleporte, dano...) e dispara o alarme. */
    private static void guardStop(MinecraftClient mc, String note) {
        stopPlayback(mc, note);
        FarmBot.raiseAlert();
    }

    /** Começo de cada tick: aplica os comandos gravados antes do jogo processar o tick. */
    public static void startTick(MinecraftClient mc) {
        if (state != State.PLAYING) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || p != playPlayer || mc.world != playWorld) {
            if (FarmConfig.guardEnabled) guardStop(mc, "§cReplay interrompido: o servidor reiniciou, você mudou de mundo ou renasceu.");
            else stopPlayback(mc, "§cReplay interrompido: mudou de mundo ou renasceu.");
            return;
        }
        if (FarmConfig.enabled) {
            stopPlayback(mc, "§eReplay interrompido: o Farm Bot foi ligado.");
            return;
        }
        if (mc.currentScreen != null) {
            stopPlayback(mc, "§eReplay interrompido: uma tela foi aberta.");
            return;
        }
        if (FarmConfig.guardEnabled) {
            Vec3d pos = p.getPos();
            if (pos.squaredDistanceTo(lastPos) > TELEPORT_DIST * TELEPORT_DIST) {
                guardStop(mc, "§cReplay interrompido: você foi teleportado.");
                return;
            }
            lastPos = pos;
            float hp = p.getHealth();
            if (hp < lastHealth - 0.01f) {
                guardStop(mc, "§cReplay interrompido: você tomou dano.");
                return;
            }
            lastHealth = hp;
        }

        // acabou a gravação: termina (ou volta ao começo, se o loop estiver ligado)
        if (idx >= frames.size()) {
            if (FarmConfig.recorderLoop) {
                idx = 0;
                loops++;
                aligning = true;     // gira a câmera de volta ao ângulo inicial de forma suave, se preciso
                alignTicks = 0;
                HumanLook.reset();
            } else {
                double drift = Math.hypot(p.getX() - endPos.x, p.getZ() - endPos.z);
                stopPlayback(mc, "§a■ Replay concluído. Desvio do ponto final: "
                        + String.format(Locale.ROOT, "%.1f", drift) + " blocos.");
                return;
            }
        }

        // alinha a câmera com o primeiro quadro (só gira se estiver desalinhada)
        if (aligning) {
            Frame f0 = frames.get(0);
            if (aligned(p, f0) || ++alignTicks > 40) {
                aligning = false;
            } else {
                setKeys(mc, 0, 0);
                HumanLook.step(p, f0.yaw(), f0.pitch(), false);
                return;
            }
        }

        Frame f = frames.get(idx);

        // voo: garante o estado que o jogador tinha no início deste tick (o duplo-pulo reproduz as trocas)
        boolean wantFly = idx > 0 ? (frames.get(idx - 1).flags() & F_FLY) != 0 : startFlying;
        if (p.getAbilities().allowFlying && p.getAbilities().flying != wantFly) {
            p.getAbilities().flying = wantFly;
            p.sendAbilitiesUpdate();
        }

        setKeys(mc, f.flags(), prevFlags);
        prevFlags = f.flags();

        float yaw = p.getYaw();
        p.setYaw(yaw + MathHelper.wrapDegrees(f.yaw() - yaw));
        p.setPitch(MathHelper.clamp(f.pitch(), -90f, 90f));

        if (f.slot() >= 0 && f.slot() < 9 && p.getInventory().selectedSlot != f.slot()) {
            p.getInventory().selectedSlot = f.slot();
        }

        idx++;
        if (idx % 10 == 0) {
            FarmBot.msg(mc, "§a▶ Reproduzindo " + progressPercent() + "%"
                    + (FarmConfig.recorderLoop ? "  (volta " + (loops + 1) + ")" : "")
                    + "  (" + keyName(FarmBotClient.replayKey) + " para parar)");
        }
    }

    private static boolean aligned(ClientPlayerEntity p, Frame f) {
        return Math.abs(MathHelper.wrapDegrees(f.yaw() - p.getYaw())) < 3f
                && Math.abs(f.pitch() - p.getPitch()) < 3f
                && Math.abs(HumanLook.yawVelocity()) < 1.5f;
    }

    // ================================================================== teclas
    private static KeyBinding[] keys(MinecraftClient mc) {
        GameOptions o = mc.options;
        return new KeyBinding[]{o.forwardKey, o.backKey, o.leftKey, o.rightKey, o.jumpKey,
                o.sneakKey, o.sprintKey, o.attackKey, o.useKey};
    }

    /** Aperta/solta as teclas conforme os bits; clique novo (ataque/uso) gera o "clique" de verdade. */
    private static void setKeys(MinecraftClient mc, int flags, int prev) {
        KeyBinding[] ks = keys(mc);
        for (int i = 0; i < ks.length; i++) {
            boolean want = (flags & BITS[i]) != 0;
            boolean was = (prev & BITS[i]) != 0;
            setKey(ks[i], want);
            if (want && !was && (i == IDX_ATTACK || i == IDX_USE)) {
                KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(ks[i].getBoundKeyTranslationKey()));
            }
        }
    }

    /** Define a tecla; funciona também com "alternar agachar/correr" (que inverte a cada setPressed). */
    private static void setKey(KeyBinding kb, boolean want) {
        if (kb.isPressed() == want) return;
        kb.setPressed(want);
        if (kb.isPressed() != want) kb.setPressed(true);
    }

    /** Devolve as teclas ao estado físico real (o que o jogador está realmente apertando). */
    private static void releaseKeys(MinecraftClient mc) {
        if (mc.options == null) return;
        for (KeyBinding kb : keys(mc)) setKey(kb, physicallyDown(mc, kb));
    }

    private static boolean physicallyDown(MinecraftClient mc, KeyBinding kb) {
        InputUtil.Key k = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
        long h = mc.getWindow().getHandle();
        int code = k.getCode();
        if (code < 0) return false;
        if (k.getCategory() == InputUtil.Type.KEYSYM) return InputUtil.isKeyPressed(h, code);
        if (k.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(h, code) == GLFW.GLFW_PRESS;
        return false;
    }

    // ================================================================== mensagens
    private static void chat(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Recorder] " + s), false);
    }

    // ================================================================== disco
    private static final int MAGIC = 0x46425243;   // "FBRC"
    private static final int VERSION = 1;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("farmbot_recording.bin");
    }

    private static void save() {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file())))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeDouble(startPos.x);
            out.writeDouble(startPos.y);
            out.writeDouble(startPos.z);
            out.writeDouble(endPos.x);
            out.writeDouble(endPos.y);
            out.writeDouble(endPos.z);
            out.writeBoolean(startFlying);
            out.writeInt(frames.size());
            for (Frame f : frames) {
                out.writeShort(f.flags());
                out.writeFloat(f.yaw());
                out.writeFloat(f.pitch());
                out.writeByte(f.slot());
            }
        } catch (Exception e) {
            System.err.println("[FarmBot] Erro ao salvar a gravação: " + e);
        }
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return;
            Vec3d s = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            Vec3d e = new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
            boolean fly = in.readBoolean();
            int n = in.readInt();
            if (n < 0 || n > MAX_FRAMES) return;
            List<Frame> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new Frame(in.readShort() & 0xFFFF, in.readFloat(), in.readFloat(), in.readByte()));
            }
            frames = list;
            startPos = s;
            endPos = e;
            startFlying = fly;
        } catch (Exception e) {
            System.err.println("[FarmBot] Erro ao carregar a gravação: " + e);
        }
    }

    /** Apaga a gravação (memória e disco). */
    public static void clear(MinecraftClient mc) {
        if (state == State.PLAYING) stopPlayback(mc, null);
        if (state == State.RECORDING) return;
        frames = new ArrayList<>();
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) { }
        chat(mc, "§eGravação apagada.");
    }
}
