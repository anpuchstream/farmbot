package com.farmbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Estado e configuração do bot (salva em config/farmbot.json). */
public class FarmConfig {
    public enum Mode { WALK, FLY }
    /** Tarefa do bot: colher plantações ou minerar. */
    public enum Task { FARM, MINE }
    /**
     * Tipo de zona de mineração.
     * EC = totalmente plana (varre a área inteira e espera a mina regenerar).
     * PR = plana no topo, mas muito funda e com vários minérios (escava de cima para baixo, em fatias).
     */
    public enum MineZone { EC, PR }

    public static boolean enabled = false;
    public static Task task = Task.FARM;
    public static Mode mode = Mode.WALK;
    public static MineZone mineZone = MineZone.EC;
    /** true = quebra só minérios expostos; false = quebra tudo (esvazia a zona). Um valor por zona. */
    public static boolean oresOnlyEC = false;
    public static boolean oresOnlyPR = false;
    /** Proteção (reinício/teleporte/saída da área) e som de alerta. */
    public static boolean guardEnabled = true;
    public static boolean alertSound = true;
    /** Multiplicador da velocidade de voo na mineração (1.0 = voo normal do jogador; com sprint ~20 blocos/s). */
    public static float mineFlySpeed = 1.5f;
    public static boolean replant = true;
    public static boolean areaSet = false;
    /** Recorder: repete o replay em loop até apertar a tecla de novo. */
    public static boolean recorderLoop = false;
    public static final int[] p1 = {0, 64, 0};
    public static final int[] p2 = {0, 64, 0};
    public static final Set<Crop> crops = EnumSet.of(Crop.WHEAT);

    /** "Só minérios" da zona de mineração escolhida. */
    public static boolean oresOnly() {
        return mineZone == MineZone.PR ? oresOnlyPR : oresOnlyEC;
    }

    public static int minX() { return Math.min(p1[0], p2[0]); }
    public static int maxX() { return Math.max(p1[0], p2[0]); }
    public static int minY() { return Math.min(p1[1], p2[1]); }
    public static int maxY() { return Math.max(p1[1], p2[1]); }
    public static int minZ() { return Math.min(p1[2], p2[2]); }
    public static int maxZ() { return Math.max(p1[2], p2[2]); }

    public static boolean inArea(int x, int y, int z) {
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    public static long volume() {
        return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1);
    }

    // ---------- persistência ----------
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class Data {
        int[] p1, p2;
        String mode, task, mineZone;
        boolean replant, areaSet;
        // Boolean (objeto) para distinguir "ausente" de "false" em configs antigas
        Boolean oresOnlyEC, oresOnlyPR, guardEnabled, alertSound, recorderLoop;
        Float mineFlySpeed;
        List<String> crops;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("farmbot.json");
    }

    public static void save() {
        try {
            Data d = new Data();
            d.p1 = p1.clone();
            d.p2 = p2.clone();
            d.mode = mode.name();
            d.task = task.name();
            d.mineZone = mineZone.name();
            d.oresOnlyEC = oresOnlyEC;
            d.oresOnlyPR = oresOnlyPR;
            d.guardEnabled = guardEnabled;
            d.alertSound = alertSound;
            d.recorderLoop = recorderLoop;
            d.mineFlySpeed = mineFlySpeed;
            d.replant = replant;
            d.areaSet = areaSet;
            d.crops = new ArrayList<>();
            for (Crop c : crops) d.crops.add(c.name());
            Files.writeString(file(), GSON.toJson(d));
        } catch (Exception e) {
            System.err.println("[FarmBot] Erro ao salvar config: " + e);
        }
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Data d = GSON.fromJson(Files.readString(f), Data.class);
            if (d == null) return;
            if (d.p1 != null && d.p1.length == 3) System.arraycopy(d.p1, 0, p1, 0, 3);
            if (d.p2 != null && d.p2.length == 3) System.arraycopy(d.p2, 0, p2, 0, 3);
            if (d.mode != null) mode = Mode.valueOf(d.mode);
            if (d.task != null) task = Task.valueOf(d.task);
            if (d.mineZone != null) {
                try { mineZone = MineZone.valueOf(d.mineZone); } catch (IllegalArgumentException ignored) { }
            }
            if (d.oresOnlyEC != null) oresOnlyEC = d.oresOnlyEC;
            if (d.oresOnlyPR != null) oresOnlyPR = d.oresOnlyPR;
            if (d.guardEnabled != null) guardEnabled = d.guardEnabled;
            if (d.alertSound != null) alertSound = d.alertSound;
            if (d.recorderLoop != null) recorderLoop = d.recorderLoop;
            if (d.mineFlySpeed != null) mineFlySpeed = Math.max(0.5f, Math.min(3.0f, d.mineFlySpeed));
            replant = d.replant;
            areaSet = d.areaSet;
            if (d.crops != null) {
                crops.clear();
                for (String s : d.crops) {
                    Crop c = Crop.fromName(s);
                    if (c != null) crops.add(c);
                }
            }
        } catch (Exception e) {
            System.err.println("[FarmBot] Erro ao carregar config: " + e);
        }
    }
}
