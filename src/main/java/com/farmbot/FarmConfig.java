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
    /** ORES = só minérios expostos; ALL = esvazia a área inteira. */
    public enum MineMode { ORES, ALL }

    public static boolean enabled = false;
    public static Task task = Task.FARM;
    public static Mode mode = Mode.WALK;
    public static MineMode mineMode = MineMode.ORES;
    public static boolean replant = true;
    public static boolean areaSet = false;
    public static final int[] p1 = {0, 64, 0};
    public static final int[] p2 = {0, 64, 0};
    public static final Set<Crop> crops = EnumSet.of(Crop.WHEAT);

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
        String mode, task, mineMode;
        boolean replant, areaSet;
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
            d.mineMode = mineMode.name();
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
            if (d.mineMode != null) mineMode = MineMode.valueOf(d.mineMode);
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
