package com.farmbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Interface do mod (tecla G). */
public class FarmScreen extends Screen {
    private static final int SLOTS = 8; // plantas visíveis por página (2 colunas x 4 linhas)
    private static Crop.Category category = Crop.Category.CROPS;
    private static int cropPage = 0;

    private final TextFieldWidget[] fields = new TextFieldWidget[6];
    private final ButtonWidget[] cropSlots = new ButtonWidget[SLOTS];
    private final ButtonWidget[] tabBtns = new ButtonWidget[Crop.Category.values().length];
    private ButtonWidget taskBtn, moveBtn, replantBtn, mineModeBtn, startBtn;
    private ButtonWidget prevBtn, nextBtn, allBtn, noneBtn;

    private int pagerY, labelAreaY, pos1Y, pos2Y, statusY;

    public FarmScreen() {
        super(Text.literal("Farm Bot"));
    }

    private List<Crop> list() {
        List<Crop> l = new ArrayList<>();
        for (Crop c : Crop.values()) if (c.category == category) l.add(c);
        return l;
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(list().size() / (double) SLOTS));
    }

    private Crop cropAt(int slot) {
        List<Crop> l = list();
        int idx = cropPage * SLOTS + slot;
        return idx < l.size() ? l.get(idx) : null;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int left = cx - 155;
        if (cropPage >= pageCount()) cropPage = 0;

        // Linha 1: tarefa + movimento
        taskBtn = addDrawableChild(ButtonWidget.builder(taskText(), b -> {
            FarmConfig.task = FarmConfig.task == FarmConfig.Task.FARM
                    ? FarmConfig.Task.MINE : FarmConfig.Task.FARM;
            refreshWidgets();
        }).dimensions(left, 20, 150, 20).build());

        moveBtn = addDrawableChild(ButtonWidget.builder(moveText(), b -> {
            FarmConfig.mode = FarmConfig.mode == FarmConfig.Mode.WALK
                    ? FarmConfig.Mode.FLY : FarmConfig.Mode.WALK;
            refreshWidgets();
        }).dimensions(left + 160, 20, 150, 20).build());

        // Linha 2: replantar (farm) + modo de mineração (mine)
        replantBtn = addDrawableChild(ButtonWidget.builder(replantText(), b -> {
            FarmConfig.replant = !FarmConfig.replant;
            refreshWidgets();
        }).dimensions(left, 42, 150, 20).build());

        mineModeBtn = addDrawableChild(ButtonWidget.builder(mineModeText(), b -> {
            FarmConfig.mineMode = FarmConfig.mineMode == FarmConfig.MineMode.ORES
                    ? FarmConfig.MineMode.ALL : FarmConfig.MineMode.ORES;
            refreshWidgets();
        }).dimensions(left + 160, 42, 150, 20).build());

        // Abas de categoria (todas visíveis): Plantas | Flores | Gramas | Algas | Outras | Mudas
        Crop.Category[] cats = Crop.Category.values();
        int tabW = 50, gap = 2; // 6*50 + 5*2 = 310
        for (int i = 0; i < cats.length; i++) {
            final Crop.Category cat = cats[i];
            tabBtns[i] = addDrawableChild(ButtonWidget.builder(Text.literal(cat.shortLabel), b -> {
                category = cat;
                cropPage = 0;
                refreshWidgets();
            }).dimensions(left + i * (tabW + gap), 65, tabW, 14).build());
        }

        // Linha do paginador: < > Todas Nenhuma (valem para a aba atual)
        pagerY = 81;
        prevBtn = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            cropPage = (cropPage - 1 + pageCount()) % pageCount();
            refreshWidgets();
        }).dimensions(left + 154, pagerY, 18, 14).build());
        nextBtn = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            cropPage = (cropPage + 1) % pageCount();
            refreshWidgets();
        }).dimensions(left + 174, pagerY, 18, 14).build());
        allBtn = addDrawableChild(ButtonWidget.builder(Text.literal("Todas"), b -> {
            FarmConfig.crops.addAll(list());
            refreshWidgets();
        }).dimensions(left + 196, pagerY, 56, 14).build());
        noneBtn = addDrawableChild(ButtonWidget.builder(Text.literal("Nenhuma"), b -> {
            FarmConfig.crops.removeAll(list());
            refreshWidgets();
        }).dimensions(left + 256, pagerY, 54, 14).build());

        // Grade de plantas (8 por página)
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            int bx = left + (i % 2) * 160;
            int by = 97 + (i / 2) * 20;
            cropSlots[i] = addDrawableChild(ButtonWidget.builder(Text.empty(), btn -> {
                Crop c = cropAt(slot);
                if (c == null) return;
                if (!FarmConfig.crops.remove(c)) FarmConfig.crops.add(c);
                refreshWidgets();
            }).dimensions(bx, by, 150, 18).build());
        }

        // Área
        labelAreaY = 97 + 4 * 20 + 3;
        pos1Y = labelAreaY + 12;
        pos2Y = pos1Y + 22;
        buildPosRow(0, pos1Y, left, cx);
        buildPosRow(1, pos2Y, left, cx);

        // Iniciar / Fechar
        int by = pos2Y + 26;
        startBtn = addDrawableChild(ButtonWidget.builder(startText(), b -> {
            FarmBot.toggle(client);
            b.setMessage(startText());
            if (FarmConfig.enabled) close();
        }).dimensions(left, by, 150, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Fechar"), b -> close())
                .dimensions(left + 160, by, 150, 20).build());

        statusY = by + 25;
        refreshWidgets();
    }

    private void buildPosRow(int which, int y, int left, int cx) {
        int[] arr = which == 0 ? FarmConfig.p1 : FarmConfig.p2;
        for (int k = 0; k < 3; k++) {
            int idx = which * 3 + k;
            TextFieldWidget f = new TextFieldWidget(textRenderer, cx - 90 + k * 50, y, 46, 18,
                    Text.literal("XYZ".substring(k, k + 1)));
            f.setTextPredicate(s -> s.matches("-?\\d*"));
            f.setText(String.valueOf(arr[k]));
            final int fk = k;
            f.setChangedListener(s -> {
                try {
                    arr[fk] = Integer.parseInt(s);
                    FarmConfig.areaSet = true;
                } catch (NumberFormatException ignored) { }
            });
            fields[idx] = addDrawableChild(f);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Minha posição"), b -> {
            if (client == null || client.player == null) return;
            BlockPos bp = client.player.getBlockPos();
            fields[which * 3].setText(String.valueOf(bp.getX()));
            fields[which * 3 + 1].setText(String.valueOf(bp.getY()));
            fields[which * 3 + 2].setText(String.valueOf(bp.getZ()));
        }).dimensions(cx + 66, y - 1, 89, 20).build());
    }

    /** Atualiza textos e ativa/desativa os botões conforme a tarefa escolhida. */
    private void refreshWidgets() {
        boolean farm = FarmConfig.task == FarmConfig.Task.FARM;
        taskBtn.setMessage(taskText());
        moveBtn.setMessage(moveText());
        replantBtn.setMessage(replantText());
        mineModeBtn.setMessage(mineModeText());
        replantBtn.active = farm;
        mineModeBtn.active = !farm;

        Crop.Category[] cats = Crop.Category.values();
        for (int i = 0; i < cats.length; i++) {
            boolean sel = cats[i] == category;
            tabBtns[i].setMessage(Text.literal(cats[i].shortLabel)
                    .formatted(sel ? Formatting.YELLOW : Formatting.WHITE));
            tabBtns[i].active = farm;
        }
        prevBtn.active = farm;
        nextBtn.active = farm;
        allBtn.active = farm;
        noneBtn.active = farm;

        for (int i = 0; i < SLOTS; i++) {
            Crop c = cropAt(i);
            cropSlots[i].visible = c != null;
            if (c != null) {
                cropSlots[i].setMessage(cropText(c));
                cropSlots[i].active = farm;
            }
        }
    }

    // ---------- textos ----------
    private Text taskText() {
        return FarmConfig.task == FarmConfig.Task.FARM
                ? Text.literal("Tarefa: Farm").formatted(Formatting.GREEN)
                : Text.literal("Tarefa: Mineração").formatted(Formatting.AQUA);
    }

    private Text moveText() {
        return Text.literal("Movimento: " + (FarmConfig.mode == FarmConfig.Mode.WALK ? "Correndo" : "Voando"));
    }

    private Text replantText() {
        boolean on = FarmConfig.replant;
        return Text.literal("Replantar: " + (on ? "Sim" : "Não"))
                .formatted(on ? Formatting.GREEN : Formatting.GRAY);
    }

    private Text mineModeText() {
        return Text.literal("Minerar: " + (FarmConfig.mineMode == FarmConfig.MineMode.ORES
                ? "Só minérios" : "Tudo (esvaziar)"));
    }

    private Text cropText(Crop c) {
        boolean on = FarmConfig.crops.contains(c);
        return Text.literal((on ? "[X] " : "[   ] ") + c.label)
                .formatted(on ? Formatting.GREEN : Formatting.GRAY);
    }

    private Text startText() {
        return FarmConfig.enabled
                ? Text.literal("Parar").formatted(Formatting.RED)
                : Text.literal("Iniciar").formatted(Formatting.GREEN);
    }

    // ---------- render ----------
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2, left = cx - 155;
        boolean farm = FarmConfig.task == FarmConfig.Task.FARM;

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Farm Bot"), cx, 7, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal(category.label + " — pág. " + (cropPage + 1) + "/" + pageCount()),
                left, pagerY + 3, farm ? 0xFFFF55 : 0x777777);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Área (X Y Z dos dois cantos):"), left, labelAreaY, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Canto 1"), left, pos1Y + 5, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Canto 2"), left, pos2Y + 5, 0xFFFFFF);

        String info = "Volume: " + FarmConfig.volume() + " blocos"
                + (farm ? "  |  Selecionadas: " + FarmConfig.crops.size() : "")
                + (FarmConfig.enabled ? "  |  " + (farm ? "Prontas: " : "Alvos: ") + FarmBot.targetCount() : "")
                + "  |  H liga/desliga";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(info), cx, statusY, 0x88FF88);
    }

    @Override
    public void removed() {
        FarmConfig.save();
        super.removed();
    }
}
