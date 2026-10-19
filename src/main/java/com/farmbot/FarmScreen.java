package com.farmbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Interface do mod (tecla G): painel escuro com abas, grade de cards (ícone, OPÇÕES, ATIVADO/DESATIVADO),
 * busca, rolagem e subtelas (plantas e área). Tudo é desenhado à mão; só a busca e as coordenadas
 * usam widgets de texto do jogo.
 */
public class FarmScreen extends Screen {

    // ---------------------------------------------------------------- abas / telas
    private enum Tab {
        ALL("TUDO"), FARM("FARM"), EC("MODO EC"), PR("MODO PR"), SYSTEM("SISTEMA");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private enum View { MAIN, CROPS, AREA }

    private static Tab tab = Tab.ALL;
    private static View view = View.MAIN;
    private static Crop.Category category = Crop.Category.CROPS;
    private static String searchText = "";

    // ---------------------------------------------------------------- cards (módulos)
    private static final class Card {
        final String id, title, desc;
        final ItemStack icon;
        final Tab[] tabs;

        Card(String id, String title, Item icon, String desc, Tab... tabs) {
            this.id = id;
            this.title = title;
            this.icon = new ItemStack(icon);
            this.desc = desc;
            this.tabs = tabs;
        }

        boolean inTab(Tab t) {
            for (Tab x : tabs) if (x == t) return true;
            return false;
        }
    }

    private static final List<Card> CARDS = List.of(
            new Card("farm", "Farm", Items.DIAMOND_HOE,
                    "Colhe plantações, flores, gramas, algas e corais dentro da área. Em OPÇÕES você escolhe quais plantas.",
                    Tab.ALL, Tab.FARM),
            new Card("replant", "Replantar", Items.WHEAT_SEEDS,
                    "Replanta sozinho depois de colher (só as plantas que têm semente).",
                    Tab.ALL, Tab.FARM),
            new Card("ec", "Modo EC", Items.DIAMOND_PICKAXE,
                    "Zona totalmente plana. O bot voa varrendo a área inteira, quebra tudo ao alcance e espera a mina regenerar.",
                    Tab.ALL, Tab.EC),
            new Card("pr", "Modo PR", Items.NETHERITE_PICKAXE,
                    "Zona plana no topo, mas muito funda e com vários minérios. O bot voa e escava de cima para baixo, em fatias, priorizando os minérios.",
                    Tab.ALL, Tab.PR),
            new Card("ores", "Só minérios", Items.DIAMOND,
                    "Ativado: quebra apenas minérios expostos. Desativado: quebra tudo (esvazia a zona). Vale para a zona da aba atual.",
                    Tab.ALL, Tab.EC, Tab.PR),
            new Card("move", "Voar (Farm)", Items.FEATHER,
                    "Só vale para o Farm: ativado voa, desativado corre. A mineração (EC e PR) sempre voa e precisa da permissão de voo.",
                    Tab.ALL, Tab.FARM, Tab.SYSTEM),
            new Card("area", "Área", Items.COMPASS,
                    "Dois cantos (X Y Z) que delimitam o trabalho. No EC cubra o plano; no PR cubra o poço do topo ao fundo.",
                    Tab.ALL, Tab.FARM, Tab.EC, Tab.PR, Tab.SYSTEM),
            new Card("guard", "Proteção", Items.SHIELD,
                    "Para o bot e avisa se o servidor reiniciar, se você for teleportado ou se sair da área demarcada.",
                    Tab.ALL, Tab.SYSTEM),
            new Card("sound", "Alerta sonoro", Items.BELL,
                    "Toca um sino 3 vezes quando a proteção para o bot. Em OPÇÕES você testa o som.",
                    Tab.ALL, Tab.SYSTEM));

    private static final Map<Crop, ItemStack> CROP_ICONS = new EnumMap<>(Crop.class);

    // ---------------------------------------------------------------- cores
    private static final int C_DIM = 0xA0000000;
    private static final int C_PANEL = 0xEE10131C;
    private static final int C_PANEL_BORDER = 0xFF2B3350;
    private static final int C_CARD = 0xCC1B2030;
    private static final int C_CARD_HOVER = 0xCC252C42;
    private static final int C_CARD_BORDER = 0xFF39425F;
    private static final int C_BLUE = 0xFF3B7BF5;
    private static final int C_BLUE_DARK = 0xFF2A3A63;
    private static final int C_GREEN = 0xFF1FA463;
    private static final int C_GREEN_H = 0xFF27BF74;
    private static final int C_RED = 0xFFC62C4E;
    private static final int C_RED_H = 0xFFDD3A5E;
    private static final int C_OPT = 0xFF141826;
    private static final int C_OPT_H = 0xFF1E2438;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_MUTED = 0xFF9AA3BC;

    // ---------------------------------------------------------------- áreas clicáveis (refeitas a cada frame)
    private static final int H_TAB = 1, H_BACK = 2, H_TOGGLE = 3, H_OPTIONS = 4, H_CROP = 5, H_CAT = 6,
            H_ALL = 7, H_NONE = 8, H_START = 9, H_CLOSE = 10, H_POS = 11;

    private static final class Hit {
        final int x1, y1, x2, y2, kind, arg;
        final boolean clipped;

        Hit(int x, int y, int w, int h, int kind, int arg, boolean clipped) {
            this.x1 = x;
            this.y1 = y;
            this.x2 = x + w;
            this.y2 = y + h;
            this.kind = kind;
            this.arg = arg;
            this.clipped = clipped;
        }
    }

    private final List<Hit> hits = new ArrayList<>();

    // ---------------------------------------------------------------- estado da tela
    private static final int COLS = 3, GAP = 8;
    private int pw, ph, px, py;      // painel
    private int gx, gy, gw, gh;      // área útil (abaixo do cabeçalho, acima do rodapé)
    private int clipY1, clipY2;      // faixa vertical onde os itens rolados são clicáveis
    private float scroll, maxScroll;
    private String hoverTitle, hoverDesc;

    private TextFieldWidget search;
    private final TextFieldWidget[] fields = new TextFieldWidget[6];

    public FarmScreen() {
        super(Text.literal("Farm Bot"));
        view = View.MAIN;
        searchText = "";
    }

    // ================================================================ init / layout
    private void layout() {
        pw = Math.min(width - 12, 420);
        ph = Math.min(height - 10, 262);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        gx = px + 12;
        gy = py + 40;
        gw = pw - 24;
        gh = ph - 40 - 36;
    }

    @Override
    protected void init() {
        layout();

        // busca (cabeçalho, à direita)
        int sx = px + pw - 12 - 112;
        search = new TextFieldWidget(textRenderer, sx + 8, py + 15, 96, 10, Text.literal("Buscar"));
        search.setDrawsBackground(false);
        search.setMaxLength(24);
        search.setText(searchText);
        search.setChangedListener(s -> {
            searchText = s;
            scroll = 0;
        });
        addDrawableChild(search);

        // coordenadas da área (subtela Área)
        int fx0 = gx + 78;
        for (int which = 0; which < 2; which++) {
            int[] arr = which == 0 ? FarmConfig.p1 : FarmConfig.p2;
            int ry = gy + 38 + which * 38;
            for (int k = 0; k < 3; k++) {
                TextFieldWidget f = new TextFieldWidget(textRenderer, fx0 + k * 56 + 5, ry + 5, 42, 10,
                        Text.literal("XYZ".substring(k, k + 1)));
                f.setDrawsBackground(false);
                f.setTextPredicate(s -> s.matches("-?\\d*"));
                f.setText(String.valueOf(arr[k]));
                final int fk = k;
                f.setChangedListener(s -> {
                    try {
                        arr[fk] = Integer.parseInt(s);
                        FarmConfig.areaSet = true;
                    } catch (NumberFormatException ignored) { }
                });
                fields[which * 3 + k] = addDrawableChild(f);
            }
        }
        refreshVisibility();
    }

    private void refreshVisibility() {
        boolean area = view == View.AREA;
        search.visible = !area;
        for (TextFieldWidget f : fields) f.visible = area;
        setFocused(null);
    }

    private void setView(View v) {
        view = v;
        scroll = 0;
        refreshVisibility();
    }

    private void setTab(Tab t) {
        tab = t;
        scroll = 0;
    }

    // ================================================================ estado dos cards
    /** Zona de mineração a que a aba atual se refere. */
    private static FarmConfig.MineZone zoneCtx() {
        if (tab == Tab.EC) return FarmConfig.MineZone.EC;
        if (tab == Tab.PR) return FarmConfig.MineZone.PR;
        return FarmConfig.mineZone;
    }

    private static boolean isOn(String id) {
        switch (id) {
            case "farm": return FarmConfig.task == FarmConfig.Task.FARM;
            case "replant": return FarmConfig.replant;
            case "ec": return FarmConfig.task == FarmConfig.Task.MINE && FarmConfig.mineZone == FarmConfig.MineZone.EC;
            case "pr": return FarmConfig.task == FarmConfig.Task.MINE && FarmConfig.mineZone == FarmConfig.MineZone.PR;
            case "ores": return zoneCtx() == FarmConfig.MineZone.PR ? FarmConfig.oresOnlyPR : FarmConfig.oresOnlyEC;
            case "move": return FarmConfig.mode == FarmConfig.Mode.FLY;
            case "area": return FarmConfig.areaSet;
            case "guard": return FarmConfig.guardEnabled;
            case "sound": return FarmConfig.alertSound;
            default: return false;
        }
    }

    private static String statusLabel(String id, boolean on) {
        if (id.equals("area")) return on ? "DEFINIDA" : "SEM ÁREA";
        return on ? "ATIVADO" : "DESATIVADO";
    }

    private static void toggleCard(String id) {
        switch (id) {
            case "farm":
                FarmConfig.task = FarmConfig.Task.FARM;
                break;
            case "replant":
                FarmConfig.replant = !FarmConfig.replant;
                break;
            case "ec":
                FarmConfig.task = FarmConfig.Task.MINE;
                FarmConfig.mineZone = FarmConfig.MineZone.EC;
                break;
            case "pr":
                FarmConfig.task = FarmConfig.Task.MINE;
                FarmConfig.mineZone = FarmConfig.MineZone.PR;
                break;
            case "ores":
                if (zoneCtx() == FarmConfig.MineZone.PR) FarmConfig.oresOnlyPR = !FarmConfig.oresOnlyPR;
                else FarmConfig.oresOnlyEC = !FarmConfig.oresOnlyEC;
                break;
            case "move":
                FarmConfig.mode = FarmConfig.mode == FarmConfig.Mode.WALK ? FarmConfig.Mode.FLY : FarmConfig.Mode.WALK;
                break;
            case "guard":
                FarmConfig.guardEnabled = !FarmConfig.guardEnabled;
                break;
            case "sound":
                FarmConfig.alertSound = !FarmConfig.alertSound;
                break;
            default:
                break;
        }
    }

    private static boolean hasOptions(String id) {
        switch (id) {
            case "farm":
            case "area":
            case "sound":
                return true;
            case "ec": return tab != Tab.EC;
            case "pr": return tab != Tab.PR;
            default: return false;
        }
    }

    private void openOptions(String id) {
        switch (id) {
            case "farm": setView(View.CROPS); break;
            case "area": setView(View.AREA); break;
            case "ec": setTab(Tab.EC); break;
            case "pr": setTab(Tab.PR); break;
            case "sound": if (client != null) FarmBot.testAlert(client); break;
            default: break;
        }
    }

    private List<Card> visibleCards() {
        String q = searchText.trim().toLowerCase();
        List<Card> l = new ArrayList<>();
        for (Card k : CARDS) {
            if (!k.inTab(tab)) continue;
            if (!q.isEmpty() && !k.title.toLowerCase().contains(q)) continue;
            l.add(k);
        }
        return l;
    }

    private List<Crop> visibleCrops() {
        String q = searchText.trim().toLowerCase();
        List<Crop> l = new ArrayList<>();
        for (Crop c : Crop.values()) {
            if (q.isEmpty() ? c.category == category : c.label.toLowerCase().contains(q)) l.add(c);
        }
        return l;
    }

    // ================================================================ desenho: primitivas
    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Retângulo com cantos levemente arredondados. */
    private static void rr(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x + 1, y, x + w - 1, y + h, color);
        c.fill(x, y + 1, x + w, y + h - 1, color);
    }

    private static void rrBorder(DrawContext c, int x, int y, int w, int h, int border, int fill) {
        rr(c, x, y, w, h, border);
        rr(c, x + 1, y + 1, w - 2, h - 2, fill);
    }

    private void text(DrawContext c, String s, int x, int y, int color) {
        c.drawTextWithShadow(textRenderer, Text.literal(s), x, y, color);
    }

    private void ctext(DrawContext c, String s, int cx, int y, int color) {
        c.drawCenteredTextWithShadow(textRenderer, Text.literal(s), cx, y, color);
    }

    private static String spaced(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static void drawIcon(DrawContext c, ItemStack s, int x, int y, float scale) {
        c.getMatrices().push();
        c.getMatrices().translate((float) x, (float) y, 0f);
        c.getMatrices().scale(scale, scale, 1f);
        c.drawItem(s, 0, 0);
        c.getMatrices().pop();
    }

    private void hit(int x, int y, int w, int h, int kind, int arg) {
        hits.add(new Hit(x, y, w, h, kind, arg, false));
    }

    private void hitClip(int x, int y, int w, int h, int kind, int arg) {
        hits.add(new Hit(x, y, w, h, kind, arg, true));
    }

    private List<String> wrap(String s, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() > 0 && textRenderer.getWidth(t) > maxW) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(t);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ================================================================ render
    /** O fundo é desenhado em render(); isto só impede o desfoque padrão de cobrir o painel. */
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        layout();
        hits.clear();
        hoverTitle = null;
        hoverDesc = null;

        ctx.fill(0, 0, width, height, C_DIM);
        rrBorder(ctx, px, py, pw, ph, C_PANEL_BORDER, C_PANEL);

        drawHeader(ctx, mx, my);
        if (view == View.MAIN) drawMain(ctx, mx, my);
        else if (view == View.CROPS) drawCrops(ctx, mx, my);
        else drawArea(ctx, mx, my);
        drawFooter(ctx, mx, my);

        super.render(ctx, mx, my, delta);   // campos de texto por cima do painel
        if (hoverDesc != null) drawTip(ctx, mx, my, hoverTitle, hoverDesc);
    }

    // ---------------------------------------------------------------- cabeçalho
    private void drawHeader(DrawContext c, int mx, int my) {
        int x = px + 12, y = py + 10;
        if (view != View.MAIN) {
            int bw = 70;
            boolean h = in(mx, my, x, y, bw, 18);
            rrBorder(c, x, y, bw, 18, C_CARD_BORDER, h ? C_BLUE : C_BLUE_DARK);
            ctext(c, "< VOLTAR", x + bw / 2, y + 5, C_TEXT);
            hit(x, y, bw, 18, H_BACK, 0);
            text(c, view == View.CROPS ? "PLANTAS DO FARM" : "ÁREA DE TRABALHO", x + bw + 10, y + 5, C_MUTED);
        } else {
            for (Tab t : Tab.values()) {
                int w = textRenderer.getWidth(t.label) + 16;
                boolean sel = t == tab;
                boolean h = in(mx, my, x, y, w, 18);
                rrBorder(c, x, y, w, 18, sel ? 0xFF6FA0FF : C_CARD_BORDER, sel ? C_BLUE : (h ? 0xFF2F3A5C : C_BLUE_DARK));
                ctext(c, t.label, x + w / 2, y + 5, C_TEXT);
                hit(x, y, w, 18, H_TAB, t.ordinal());
                x += w + 5;
            }
        }

        if (view != View.AREA) {
            int sx = px + pw - 12 - 112;
            rrBorder(c, sx, y, 112, 18, search.isFocused() ? C_BLUE : C_CARD_BORDER, C_OPT);
            if (searchText.isEmpty() && !search.isFocused()) text(c, "Buscar...", sx + 8, y + 5, 0xFF566079);
        }
    }

    // ---------------------------------------------------------------- tela principal: grade de módulos
    private void drawMain(DrawContext c, int mx, int my) {
        clipY1 = gy;
        clipY2 = gy + gh;
        List<Card> list = visibleCards();
        int cw = (gw - GAP * (COLS - 1)) / COLS, ch = 74;
        int rows = (list.size() + COLS - 1) / COLS;
        int total = Math.max(0, rows * (ch + GAP) - GAP);
        maxScroll = Math.max(0, total - gh);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (list.isEmpty()) {
            ctext(c, "Nenhum módulo encontrado.", gx + gw / 2, gy + 20, C_MUTED);
            return;
        }

        c.enableScissor(gx, gy, gx + gw, gy + gh);
        for (int i = 0; i < list.size(); i++) {
            int x = gx + (i % COLS) * (cw + GAP);
            int y = gy + (i / COLS) * (ch + GAP) - (int) scroll;
            if (y + ch < gy || y > gy + gh) continue;
            drawCard(c, mx, my, list.get(i), x, y, cw, ch);
        }
        c.disableScissor();
        drawScrollbar(c, total, gy, gh);
    }

    private void drawCard(DrawContext c, int mx, int my, Card k, int x, int y, int w, int h) {
        int idx = CARDS.indexOf(k);
        boolean inView = my >= clipY1 && my < clipY2;
        boolean hov = inView && in(mx, my, x, y, w, h);
        boolean on = isOn(k.id);

        rrBorder(c, x, y, w, h, hov ? 0xFF5B6AA0 : C_CARD_BORDER, hov ? C_CARD_HOVER : C_CARD);
        if (hov) {
            hoverTitle = k.title;
            hoverDesc = k.desc;
        }

        drawIcon(c, k.icon, x + w / 2 - 12, y + 6, 1.5f);
        ctext(c, k.title, x + w / 2, y + 33, on ? C_TEXT : C_MUTED);

        // linha OPÇÕES
        int ox = x + 3, oy = y + 44, ow = w - 6, oh = 13;
        boolean opt = hasOptions(k.id);
        boolean oHov = inView && opt && in(mx, my, ox, oy, ow, oh);
        rr(c, ox, oy, ow, oh, opt ? (oHov ? C_OPT_H : C_OPT) : 0xFF101320);
        ctext(c, spaced("OPÇÕES"), x + w / 2, oy + 3, opt ? C_TEXT : 0xFF566079);
        if (opt) hitClip(ox, oy, ow, oh, H_OPTIONS, idx);

        // barra de estado
        int bx = x + 3, by = y + h - 17, bw = w - 6, bh = 14;
        boolean bHov = inView && in(mx, my, bx, by, bw, bh);
        int col = on ? (bHov ? C_GREEN_H : C_GREEN) : (bHov ? C_RED_H : C_RED);
        rr(c, bx, by, bw, bh, col);
        ctext(c, spaced(statusLabel(k.id, on)), x + w / 2, by + 3, C_TEXT);
        hitClip(bx, by, bw, bh, H_TOGGLE, idx);
    }

    private void drawScrollbar(DrawContext c, int total, int top, int height) {
        if (maxScroll <= 0) return;
        int x = gx + gw + 4;
        int thumb = Math.max(14, (int) ((float) height * height / total));
        int ty = top + (int) ((height - thumb) * (scroll / maxScroll));
        c.fill(x, top, x + 3, top + height, 0xFF1A2036);
        c.fill(x, ty, x + 3, ty + thumb, 0xFF4F6AB0);
    }

    // ---------------------------------------------------------------- subtela: plantas do farm
    private void drawCrops(DrawContext c, int mx, int my) {
        // linha 1: categorias
        int x = gx, y = gy;
        boolean searching = !searchText.trim().isEmpty();
        for (Crop.Category cat : Crop.Category.values()) {
            int w = textRenderer.getWidth(cat.shortLabel) + 12;
            boolean sel = !searching && cat == category;
            boolean h = in(mx, my, x, y, w, 16);
            rrBorder(c, x, y, w, 16, sel ? 0xFF6FA0FF : C_CARD_BORDER, sel ? C_BLUE : (h ? 0xFF2F3A5C : C_BLUE_DARK));
            ctext(c, cat.shortLabel, x + w / 2, y + 4, searching && !h ? C_MUTED : C_TEXT);
            hit(x, y, w, 16, H_CAT, cat.ordinal());
            x += w + 4;
        }

        // linha 2: título + Todas / Nenhuma
        int y2 = gy + 21;
        List<Crop> list = visibleCrops();
        int selected = 0;
        for (Crop cr : list) if (FarmConfig.crops.contains(cr)) selected++;
        text(c, (searching ? "Resultados" : category.label) + "  (" + selected + "/" + list.size() + ")", gx, y2 + 4, C_MUTED);

        int nw = 58, aw = 48;
        int nx = gx + gw - nw, ax = nx - 4 - aw;
        boolean ah = in(mx, my, ax, y2, aw, 15), nh = in(mx, my, nx, y2, nw, 15);
        rrBorder(c, ax, y2, aw, 15, C_CARD_BORDER, ah ? C_GREEN_H : C_GREEN);
        ctext(c, "TODAS", ax + aw / 2, y2 + 4, C_TEXT);
        hit(ax, y2, aw, 15, H_ALL, 0);
        rrBorder(c, nx, y2, nw, 15, C_CARD_BORDER, nh ? C_RED_H : C_RED);
        ctext(c, "NENHUMA", nx + nw / 2, y2 + 4, C_TEXT);
        hit(nx, y2, nw, 15, H_NONE, 0);

        // grade rolável
        int top = gy + 42, vh = gh - 42;
        clipY1 = top;
        clipY2 = top + vh;
        int cw = (gw - GAP * (COLS - 1)) / COLS, ch = 44, pitch = ch + 6;
        int rows = (list.size() + COLS - 1) / COLS;
        int total = Math.max(0, rows * pitch - 6);
        maxScroll = Math.max(0, total - vh);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (list.isEmpty()) {
            ctext(c, "Nenhuma planta encontrada.", gx + gw / 2, top + 20, C_MUTED);
            return;
        }

        c.enableScissor(gx, top, gx + gw, top + vh);
        for (int i = 0; i < list.size(); i++) {
            int cx = gx + (i % COLS) * (cw + GAP);
            int cy = top + (i / COLS) * pitch - (int) scroll;
            if (cy + ch < top || cy > top + vh) continue;
            drawCropCard(c, mx, my, list.get(i), cx, cy, cw, ch);
        }
        c.disableScissor();
        drawScrollbar(c, total, top, vh);
    }

    private void drawCropCard(DrawContext c, int mx, int my, Crop cr, int x, int y, int w, int h) {
        boolean on = FarmConfig.crops.contains(cr);
        boolean hov = my >= clipY1 && my < clipY2 && in(mx, my, x, y, w, h);
        rrBorder(c, x, y, w, h, on ? 0xFF2E8B5A : C_CARD_BORDER, hov ? C_CARD_HOVER : C_CARD);

        ItemStack icon = CROP_ICONS.computeIfAbsent(cr, k -> new ItemStack(k.icon()));
        drawIcon(c, icon, x + 6, y + 5, 1.0f);
        text(c, textRenderer.trimToWidth(cr.label, w - 32), x + 26, y + 9, on ? C_TEXT : C_MUTED);

        int bx = x + 3, by = y + h - 14, bw = w - 6;
        rr(c, bx, by, bw, 11, on ? (hov ? C_GREEN_H : C_GREEN) : (hov ? C_RED_H : C_RED));
        ctext(c, on ? "ATIVADO" : "DESATIVADO", x + w / 2, by + 2, C_TEXT);
        hitClip(x, y, w, h, H_CROP, cr.ordinal());
    }

    // ---------------------------------------------------------------- subtela: área
    private void drawArea(DrawContext c, int mx, int my) {
        clipY1 = gy;
        clipY2 = gy + gh;
        text(c, "Defina os dois cantos da área (X Y Z):", gx, gy + 2, C_TEXT);

        int fx0 = gx + 78;
        String[] axis = {"X", "Y", "Z"};
        for (int k = 0; k < 3; k++) text(c, axis[k], fx0 + k * 56 + 22, gy + 18, C_MUTED);

        for (int which = 0; which < 2; which++) {
            int ry = gy + 38 + which * 38;
            text(c, "Canto " + (which + 1), gx, ry + 6, C_TEXT);
            for (int k = 0; k < 3; k++) {
                TextFieldWidget f = fields[which * 3 + k];
                rrBorder(c, fx0 + k * 56, ry, 52, 20, f.isFocused() ? C_BLUE : C_CARD_BORDER, C_OPT);
            }
            int bx = fx0 + 3 * 56 + 6, bw = 96;
            boolean h = in(mx, my, bx, ry, bw, 20);
            rrBorder(c, bx, ry, bw, 20, C_CARD_BORDER, h ? C_BLUE : C_BLUE_DARK);
            ctext(c, "Minha posição", bx + bw / 2, ry + 6, C_TEXT);
            hit(bx, ry, bw, 20, H_POS, which);
        }

        int sx = FarmConfig.maxX() - FarmConfig.minX() + 1;
        int sy = FarmConfig.maxY() - FarmConfig.minY() + 1;
        int sz = FarmConfig.maxZ() - FarmConfig.minZ() + 1;
        text(c, "Tamanho: " + sx + " x " + sy + " x " + sz + "   |   Volume: " + FarmConfig.volume() + " blocos",
                gx, gy + 112, 0xFF88FF88);
        text(c, "Modo EC: cubra a zona plana inteira (Y = camada do chão).", gx, gy + 128, C_MUTED);
        text(c, "Modo PR: cubra o poço inteiro, do topo (Y alto) ao fundo (Y baixo).", gx, gy + 140, C_MUTED);
        text(c, "O EC aceita blocos até 2 abaixo do Y mínimo (pode marcar voando logo acima do chão).", gx, gy + 152, C_MUTED);
    }

    // ---------------------------------------------------------------- rodapé
    private void drawFooter(DrawContext c, int mx, int my) {
        int fy = py + ph - 28;
        c.fill(px + 12, fy - 5, px + pw - 12, fy - 4, 0xFF262D45);

        int bw = 80, bh = 18;
        int sx = px + pw - 12 - bw;
        int cxb = sx - 8 - 62;

        boolean running = FarmConfig.enabled;
        boolean sh = in(mx, my, sx, fy, bw, bh);
        rrBorder(c, sx, fy, bw, bh, C_CARD_BORDER, running ? (sh ? C_RED_H : C_RED) : (sh ? C_GREEN_H : C_GREEN));
        ctext(c, spaced(running ? "PARAR" : "INICIAR"), sx + bw / 2, fy + 5, C_TEXT);
        hit(sx, fy, bw, bh, H_START, 0);

        boolean ch = in(mx, my, cxb, fy, 62, bh);
        rrBorder(c, cxb, fy, 62, bh, C_CARD_BORDER, ch ? 0xFF2F3A5C : C_BLUE_DARK);
        ctext(c, "FECHAR", cxb + 31, fy + 5, C_TEXT);
        hit(cxb, fy, 62, bh, H_CLOSE, 0);

        text(c, textRenderer.trimToWidth(statusLine(), cxb - (px + 12) - 8), px + 12, fy + 5,
                running ? 0xFF88FF88 : C_MUTED);
    }

    private String statusLine() {
        boolean farm = FarmConfig.task == FarmConfig.Task.FARM;
        String s = farm ? "Farm" : "Modo " + FarmConfig.mineZone;
        s += "  |  Volume: " + FarmConfig.volume();
        if (farm) s += "  |  Plantas: " + FarmConfig.crops.size();
        if (FarmConfig.enabled) {
            s += "  |  " + (farm ? "Prontas: " : "Alvos: ") + FarmBot.targetCount();
            String layer = FarmBot.mineLayerInfo();
            if (!layer.isEmpty()) s += "  |  " + layer;
        } else if (!FarmConfig.areaSet) {
            s += "  |  defina a área";
        } else {
            s += "  |  H liga/desliga";
        }
        return s;
    }

    // ---------------------------------------------------------------- dica ao passar o mouse
    private void drawTip(DrawContext c, int mx, int my, String title, String desc) {
        List<String> lines = wrap(desc, 170);
        int w = textRenderer.getWidth(title);
        for (String l : lines) w = Math.max(w, textRenderer.getWidth(l));
        w += 12;
        int h = 18 + lines.size() * 10;
        int x = Math.min(mx + 10, width - w - 4);
        int y = Math.min(my + 10, height - h - 4);

        c.getMatrices().push();
        c.getMatrices().translate(0f, 0f, 400f);
        rrBorder(c, x, y, w, h, 0xFF5B6AA0, 0xF0101420);
        text(c, title, x + 6, y + 5, 0xFFFFD866);
        for (int i = 0; i < lines.size(); i++) text(c, lines.get(i), x + 6, y + 17 + i * 10, C_TEXT);
        c.getMatrices().pop();
    }

    // ================================================================ entrada
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;   // campos de texto (busca e coordenadas)
        if (button != 0) return false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx < h.x1 || mx >= h.x2 || my < h.y1 || my >= h.y2) continue;
            if (h.clipped && (my < clipY1 || my >= clipY2)) continue;
            onHit(h);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        if (view == View.AREA) return false;
        scroll = (float) Math.max(0, Math.min(maxScroll, scroll - verticalAmount * 18));
        return true;
    }

    private void onHit(Hit h) {
        switch (h.kind) {
            case H_TAB:
                setTab(Tab.values()[h.arg]);
                break;
            case H_BACK:
                setView(View.MAIN);
                break;
            case H_TOGGLE: {
                String id = CARDS.get(h.arg).id;
                if (id.equals("area")) setView(View.AREA);
                else toggleCard(id);
                break;
            }
            case H_OPTIONS:
                openOptions(CARDS.get(h.arg).id);
                break;
            case H_CROP: {
                Crop cr = Crop.values()[h.arg];
                if (!FarmConfig.crops.remove(cr)) FarmConfig.crops.add(cr);
                break;
            }
            case H_CAT:
                category = Crop.Category.values()[h.arg];
                scroll = 0;
                break;
            case H_ALL:
                FarmConfig.crops.addAll(visibleCrops());
                break;
            case H_NONE:
                FarmConfig.crops.removeAll(visibleCrops());
                break;
            case H_START:
                FarmBot.toggle(client);
                if (FarmConfig.enabled) close();
                break;
            case H_CLOSE:
                close();
                break;
            case H_POS: {
                if (client == null || client.player == null) break;
                BlockPos bp = client.player.getBlockPos();
                fields[h.arg * 3].setText(String.valueOf(bp.getX()));
                fields[h.arg * 3 + 1].setText(String.valueOf(bp.getY()));
                fields[h.arg * 3 + 2].setText(String.valueOf(bp.getZ()));
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void removed() {
        FarmConfig.save();
        super.removed();
    }
}
