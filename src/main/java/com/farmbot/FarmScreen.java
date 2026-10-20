package com.farmbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

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
        ALL("TUDO"), FARM("FARM"), EC("MODO EC"), PR("MODO PR"), SYSTEM("SISTEMA"), RECORDER("RECORDER");
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
                    "Zona totalmente plana. O bot voa em sprint olhando para baixo, quebrando o bloco sob a mira, e espera a mina regenerar.",
                    Tab.ALL, Tab.EC),
            new Card("pr", "Modo PR", Items.NETHERITE_PICKAXE,
                    "Zona de mina funda (prisão): minérios e terracota, ou todos os blocos. Ative e escolha abaixo como o bot trabalha: Em cima ou Corredor.",
                    Tab.ALL, Tab.PR),
            new Card("prtop", "PR: Em cima", Items.ELYTRA,
                    "O bot voa em sprint por cima da mina, olhando para baixo. Quando a camada de cima é quebrada (por encantamentos), ele desce sozinho para ficar no alcance e continuar minerando.",
                    Tab.PR),
            new Card("prcor", "PR: Corredor", Items.BEDROCK,
                    "O bot fica no espaço entre a bedrock e a mina: LIGUE-O ali, de frente para a mina. Ele desce em diagonal (como SHIFT + A/D com o botão de quebrar pressionado), inverte na última camada e sobe na diagonal do mesmo lado, rodeando a mina. A mira fica travada e só gira ao contornar um canto.",
                    Tab.PR),
            new Card("ores", "Só minérios", Items.DIAMOND,
                    "Ativado: quebra apenas minérios expostos. Desativado: quebra tudo (esvazia a zona). Vale para a zona da aba atual. No modo PR também quebra terracota.",
                    Tab.ALL, Tab.EC, Tab.PR),
            new Card("allblocks", "Todos os blocos", Items.NETHERITE_PICKAXE,
                    "Ativado: quebra todos os blocos da zona, exceto cerca, placa e bedrock. Desativado: volta para Minérios + terracota.",
                    Tab.PR),
            new Card("speed", "Vel. de voo", Items.FIREWORK_ROCKET,
                    "Velocidade do voo na mineração (multiplica o voo normal, com sprint). Clique para alternar 1.0X a 3.0X. Mais rápido = mais blocos pulados e mais risco de o servidor reclamar.",
                    Tab.ALL, Tab.EC, Tab.PR),
            new Card("move", "Voar (Farm)", Items.FEATHER,
                    "Só vale para o Farm: ativado voa, desativado corre. A mineração (EC e PR) sempre voa e precisa da permissão de voo.",
                    Tab.ALL, Tab.FARM, Tab.SYSTEM),
            new Card("area", "Área", Items.COMPASS,
                    "Dois cantos (X Y Z) que delimitam o trabalho. No EC cubra o plano; no PR cubra a mina do topo ao fundo (no Corredor, a mina inteira ao longo da parede).",
                    Tab.ALL, Tab.FARM, Tab.EC, Tab.PR, Tab.SYSTEM),
            new Card("guard", "Proteção", Items.SHIELD,
                    "Para o bot e avisa se o servidor reiniciar, se você for teleportado, se sair da área, se a ferramenta (machado/enxada/picareta) for trocada ou se você receber um mapa de CAPTCHA.",
                    Tab.ALL, Tab.SYSTEM),
            new Card("recorder", "Recorder", Items.CLOCK,
                    "Grava tudo o que você faz (andar, olhar, quebrar, usar itens) e repete quando você apertar a tecla de replay. Clique em OPÇÕES para escolher as teclas.",
                    Tab.ALL),
            new Card("sound", "Alerta sonoro", Items.BELL,
                    "Toca o alarme (30 s) quando a proteção para o bot. Clique em OPÇÕES para testar o som.",
                    Tab.ALL, Tab.SYSTEM));

    private static final Map<Crop, ItemStack> CROP_ICONS = new EnumMap<>(Crop.class);
    private static final ItemStack ICON_REC = new ItemStack(Items.REDSTONE);
    private static final ItemStack ICON_PLAY = new ItemStack(Items.CLOCK);
    private static final ItemStack ICON_TAPE = new ItemStack(Items.BOOK);

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
            H_ALL = 7, H_NONE = 8, H_START = 9, H_CLOSE = 10, H_POS = 11,
            H_REC_KEY = 12, H_PLAY_KEY = 13, H_REC_BTN = 14, H_PLAY_BTN = 15, H_LOOP = 16, H_CLEAR = 17;

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
    private long soundTestUntil;
    /** Recorder: qual atalho está esperando uma tecla (0 = nenhum, 1 = gravar, 2 = replay). */
    private int listening;

    private TextFieldWidget search;
    private final TextFieldWidget[] fields = new TextFieldWidget[6];

    public FarmScreen() {
        super(Text.literal("Farm Bot"));
        view = View.MAIN;
        searchText = "";
    }

    // ================================================================ init / layout
    private void layout() {
        pw = Math.min(width - 12, 460);
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

        // busca (cabeçalho, à direita; encolhe se as abas ocuparem o espaço)
        int sw = Math.max(40, searchW());
        int sx = px + pw - 12 - sw;
        search = new TextFieldWidget(textRenderer, sx + 8, py + 15, sw - 16, 10, Text.literal("Buscar"));
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
        search.visible = searchShown();
        for (TextFieldWidget f : fields) f.visible = area;
        setFocused(null);
    }

    private int tabW(Tab t) { return textRenderer.getWidth(t.label) + 12; }

    private int tabsEnd() {
        int x = px + 12;
        for (Tab t : Tab.values()) x += tabW(t) + 5;
        return x - 5;
    }

    /** Largura que sobra para a busca ao lado das abas (máx. 112). */
    private int searchW() {
        return Math.min(112, (px + pw - 12) - (tabsEnd() + 8));
    }

    private boolean searchShown() {
        if (view == View.AREA) return false;
        if (view == View.MAIN && tab == Tab.RECORDER) return false;
        return searchW() >= 40;
    }

    private void setView(View v) {
        view = v;
        scroll = 0;
        refreshVisibility();
    }

    private void setTab(Tab t) {
        tab = t;
        scroll = 0;
        listening = 0;
        if (search != null) refreshVisibility();
    }

    // ================================================================ estado dos cards
    /** Zona de mineração a que a aba atual se refere. */
    private static FarmConfig.MineZone zoneCtx() {
        if (tab == Tab.EC) return FarmConfig.MineZone.EC;
        if (tab == Tab.PR) return FarmConfig.MineZone.PR;
        return FarmConfig.mineZone;
    }

    /** Título do card (o de minérios vira "Minérios + terracota" na aba do modo PR). */
    private static String titleOf(Card k) {
        if (k.id.equals("ores") && zoneCtx() == FarmConfig.MineZone.PR) return "Minérios + terracota";
        return k.title;
    }

    private static String descOf(Card k) {
        if (k.id.equals("ores") && zoneCtx() == FarmConfig.MineZone.PR) {
            return "Ativado: quebra apenas minérios e terracotas expostos. Desativado: muda para Todos os blocos.";
        }
        return k.desc;
    }

    private static boolean isOn(String id) {
        switch (id) {
            case "farm": return FarmConfig.task == FarmConfig.Task.FARM;
            case "replant": return FarmConfig.replant;
            case "ec": return FarmConfig.task == FarmConfig.Task.MINE && FarmConfig.mineZone == FarmConfig.MineZone.EC;
            case "pr": return FarmConfig.task == FarmConfig.Task.MINE && FarmConfig.mineZone == FarmConfig.MineZone.PR;
            case "prtop": return FarmConfig.task == FarmConfig.Task.MINE && FarmConfig.mineZone == FarmConfig.MineZone.PR
                    && FarmConfig.prMode == FarmConfig.PrMode.TOP;
            case "prcor": return FarmConfig.task == FarmConfig.Task.MINE && FarmConfig.mineZone == FarmConfig.MineZone.PR
                    && FarmConfig.prMode == FarmConfig.PrMode.CORRIDOR;
            case "ores": return zoneCtx() == FarmConfig.MineZone.PR ? FarmConfig.oresOnlyPR : FarmConfig.oresOnlyEC;
            case "allblocks": return FarmConfig.allBlocksPR;
            case "move": return FarmConfig.mode == FarmConfig.Mode.FLY;
            case "area": return FarmConfig.areaSet;
            case "speed": return true;
            case "guard": return FarmConfig.guardEnabled;
            case "sound": return FarmConfig.alertSound;
            case "recorder": return Recorder.state() != Recorder.State.IDLE || Recorder.hasRecording();
            default: return false;
        }
    }

    private static String statusLabel(String id, boolean on) {
        if (id.equals("area")) return on ? "DEFINIDA" : "SEM ÁREA";
        if (id.equals("speed")) return FarmConfig.mineFlySpeed + "X";
        if (id.equals("recorder")) {
            if (Recorder.state() == Recorder.State.RECORDING) return "GRAVANDO";
            if (Recorder.state() == Recorder.State.PLAYING) return "REPRODUZINDO";
            return on ? "PRONTO" : "VAZIO";
        }
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
            case "prtop":
                FarmConfig.task = FarmConfig.Task.MINE;
                FarmConfig.mineZone = FarmConfig.MineZone.PR;
                FarmConfig.prMode = FarmConfig.PrMode.TOP;
                break;
            case "prcor":
                FarmConfig.task = FarmConfig.Task.MINE;
                FarmConfig.mineZone = FarmConfig.MineZone.PR;
                FarmConfig.prMode = FarmConfig.PrMode.CORRIDOR;
                break;
            case "ores":
                if (zoneCtx() == FarmConfig.MineZone.PR) {
                    FarmConfig.oresOnlyPR = !FarmConfig.oresOnlyPR;
                    FarmConfig.allBlocksPR = !FarmConfig.oresOnlyPR;   // exclusivos
                } else FarmConfig.oresOnlyEC = !FarmConfig.oresOnlyEC;
                break;
            case "allblocks":
                FarmConfig.allBlocksPR = !FarmConfig.allBlocksPR;
                FarmConfig.oresOnlyPR = !FarmConfig.allBlocksPR;       // exclusivos
                break;
            case "move":
                FarmConfig.mode = FarmConfig.mode == FarmConfig.Mode.WALK ? FarmConfig.Mode.FLY : FarmConfig.Mode.WALK;
                break;
            case "speed": {
                float[] steps = {1.0f, 1.5f, 2.0f, 2.5f, 3.0f};
                int idx = 0;
                for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - FarmConfig.mineFlySpeed) < 0.01f) idx = i;
                FarmConfig.mineFlySpeed = steps[(idx + 1) % steps.length];
                break;
            }
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
            case "recorder":
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
            case "recorder": setTab(Tab.RECORDER); break;
            case "sound":
                if (client != null) { FarmBot.testAlert(client); soundTestUntil = System.currentTimeMillis() + 2500; }
                break;
            default: break;
        }
    }

    private List<Card> visibleCards() {
        String q = searchText.trim().toLowerCase();
        List<Card> l = new ArrayList<>();
        for (Card k : CARDS) {
            if (!k.inTab(tab)) continue;
            if (!q.isEmpty() && !titleOf(k).toLowerCase().contains(q)) continue;
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
        if (System.currentTimeMillis() < soundTestUntil) {
            ctext(ctx, "♪ Tocando o alarme... (sem ouvir? veja o volume Principal/Master do jogo)", width / 2, py + ph + 6, 0xFFFFD866);
        }
        if (hoverDesc != null) drawTip(ctx, mx, my, hoverTitle, hoverDesc);
    }

    /** Não pausa o jogo: em mundo single-player a pausa silenciava o som de teste. */
    @Override
    public boolean shouldPause() { return false; }

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
                int w = tabW(t);
                boolean sel = t == tab;
                boolean h = in(mx, my, x, y, w, 18);
                rrBorder(c, x, y, w, 18, sel ? 0xFF6FA0FF : C_CARD_BORDER, sel ? C_BLUE : (h ? 0xFF2F3A5C : C_BLUE_DARK));
                ctext(c, t.label, x + w / 2, y + 5, C_TEXT);
                hit(x, y, w, 18, H_TAB, t.ordinal());
                x += w + 5;
            }
        }

        if (searchShown()) {
            int sw = Math.max(40, searchW());
            int sx = px + pw - 12 - sw;
            rrBorder(c, sx, y, sw, 18, search.isFocused() ? C_BLUE : C_CARD_BORDER, C_OPT);
            if (searchText.isEmpty() && !search.isFocused()) text(c, "Buscar...", sx + 8, y + 5, 0xFF566079);
        }
    }

    // ---------------------------------------------------------------- tela principal: grade de módulos
    private void drawMain(DrawContext c, int mx, int my) {
        clipY1 = gy;
        clipY2 = gy + gh;
        if (tab == Tab.RECORDER) {
            maxScroll = 0;
            scroll = 0;
            drawRecorder(c, mx, my);
            return;
        }
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
            hoverTitle = titleOf(k);
            hoverDesc = descOf(k);
        }

        drawIcon(c, k.icon, x + w / 2 - 12, y + 6, 1.5f);
        ctext(c, titleOf(k), x + w / 2, y + 33, on ? C_TEXT : C_MUTED);

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
        String lab = statusLabel(k.id, on);
        ctext(c, k.id.equals("speed") ? lab : spaced(lab), x + w / 2, by + 3, C_TEXT);
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

    // ---------------------------------------------------------------- aba RECORDER
    private void drawRecorder(DrawContext c, int mx, int my) {
        Recorder.State st = Recorder.state();
        boolean recording = st == Recorder.State.RECORDING;
        boolean playing = st == Recorder.State.PLAYING;

        // --- gravar
        recorderCard(c, mx, my, gy, ICON_REC, "GRAVAR MOVIMENTOS",
                "Grava andar, câmera, quebrar e usar itens, até você parar.",
                FarmBotClient.recordKey, 1,
                recording ? "■ PARAR" : "● GRAVAR", recording, H_REC_KEY, H_REC_BTN,
                recording ? "§c● GRAVANDO " + Recorder.secs(Recorder.liveFrames()) + " s" : null);

        // --- replay
        recorderCard(c, mx, my, gy + 62, ICON_PLAY, "REPRODUZIR (REPLAY)",
                "Repete o que foi gravado, a partir de onde você está.",
                FarmBotClient.replayKey, 2,
                playing ? "■ PARAR" : "▶ REPLAY", playing, H_PLAY_KEY, H_PLAY_BTN,
                playing ? "§a▶ " + Recorder.progressPercent() + "%" : null);

        // --- gravação atual
        int y0 = gy + 124;
        rrBorder(c, gx, y0, gw, 56, C_CARD_BORDER, C_CARD);
        drawIcon(c, ICON_TAPE, gx + 8, y0 + 8, 1.5f);
        text(c, "GRAVAÇÃO ATUAL", gx + 40, y0 + 7, C_TEXT);
        String info;
        if (Recorder.hasRecording()) {
            Vec3d sp = Recorder.startPos();
            info = Recorder.frameCount() + " ticks  |  " + Recorder.secs(Recorder.frameCount()) + " s  |  início: X "
                    + (int) Math.floor(sp.x) + "  Y " + (int) Math.floor(sp.y) + "  Z " + (int) Math.floor(sp.z);
        } else {
            info = "Nenhuma gravação ainda.";
        }
        text(c, textRenderer.trimToWidth(info, gw - 50), gx + 40, y0 + 19, Recorder.hasRecording() ? 0xFF88FF88 : C_MUTED);

        int by = y0 + 32, bh = 18;
        boolean loop = FarmConfig.recorderLoop;
        int lw = 150;
        boolean lh = in(mx, my, gx + 40, by, lw, bh);
        rrBorder(c, gx + 40, by, lw, bh, C_CARD_BORDER, loop ? (lh ? C_GREEN_H : C_GREEN) : (lh ? C_RED_H : C_RED));
        ctext(c, "LOOP: " + (loop ? "ATIVADO" : "DESATIVADO"), gx + 40 + lw / 2, by + 5, C_TEXT);
        hit(gx + 40, by, lw, bh, H_LOOP, 0);

        int cw = 110, cx = gx + 40 + lw + 8;
        boolean can = Recorder.hasRecording() && st == Recorder.State.IDLE;
        boolean ch = can && in(mx, my, cx, by, cw, bh);
        rrBorder(c, cx, by, cw, bh, C_CARD_BORDER, can ? (ch ? C_RED_H : C_RED) : 0xFF101320);
        ctext(c, "APAGAR", cx + cw / 2, by + 5, can ? C_TEXT : 0xFF566079);
        if (can) hit(cx, by, cw, bh, H_CLEAR, 0);

        int tx = cx + cw + 8;
        text(c, textRenderer.trimToWidth("Repete sem parar.", gx + gw - 8 - tx), tx, by + 5, C_MUTED);
    }

    /** Cartão de um atalho do Recorder: título, descrição, botão da tecla e botão de ação. */
    private void recorderCard(DrawContext c, int mx, int my, int y0, ItemStack icon, String title, String desc,
                              KeyBinding kb, int which, String btnLabel, boolean active,
                              int keyKind, int btnKind, String status) {
        rrBorder(c, gx, y0, gw, 56, C_CARD_BORDER, C_CARD);
        drawIcon(c, icon, gx + 8, y0 + 8, 1.5f);
        text(c, title, gx + 40, y0 + 7, C_TEXT);
        text(c, textRenderer.trimToWidth(desc, gw - 50), gx + 40, y0 + 19, C_MUTED);
        if (status != null) {
            int w = textRenderer.getWidth(status.replaceAll("§.", ""));
            text(c, status, gx + gw - 8 - w, y0 + 7, C_TEXT);
        }

        // botão da tecla de atalho
        int kx = gx + 40, ky = y0 + 32, kw = 120, kh = 18;
        boolean wait = listening == which;
        boolean kh2 = in(mx, my, kx, ky, kw, kh);
        rrBorder(c, kx, ky, kw, kh, wait ? 0xFF6FA0FF : C_CARD_BORDER, wait ? C_BLUE : (kh2 ? C_OPT_H : C_OPT));
        ctext(c, wait ? "Pressione uma tecla..." : "Tecla: " + Recorder.keyName(kb), kx + kw / 2, ky + 5, C_TEXT);
        hit(kx, ky, kw, kh, keyKind, 0);

        // botão de ação (gravar/parar, replay/parar)
        int bw = 110, bx = gx + gw - 8 - bw;
        boolean bh2 = in(mx, my, bx, ky, bw, kh);
        rrBorder(c, bx, ky, bw, kh, C_CARD_BORDER, active ? (bh2 ? C_RED_H : C_RED) : (bh2 ? C_GREEN_H : C_GREEN));
        ctext(c, btnLabel, bx + bw / 2, ky + 5, C_TEXT);
        hit(bx, ky, bw, kh, btnKind, 0);

        // dica / conflito, no espaço entre os dois botões
        int ix = kx + kw + 8, iw = bx - 6 - ix;
        if (wait) {
            text(c, textRenderer.trimToWidth("ESC cancela | BACKSPACE limpa", iw), ix, ky + 5, 0xFFFFD866);
        } else {
            String other = conflictName(kb);
            if (other != null) text(c, textRenderer.trimToWidth("Conflito: " + other, iw), ix, ky + 5, 0xFFFF6B6B);
        }
    }

    /** Nome de outro atalho do jogo que usa a mesma tecla (null = sem conflito). */
    private String conflictName(KeyBinding kb) {
        if (client == null || kb == null || "key.keyboard.unknown".equals(kb.getBoundKeyTranslationKey())) return null;
        for (KeyBinding o : client.options.allKeys) {
            if (o != kb && o.getBoundKeyTranslationKey().equals(kb.getBoundKeyTranslationKey())) {
                return Text.translatable(o.getTranslationKey()).getString();
            }
        }
        return null;
    }

    private void bindKey(KeyBinding kb, InputUtil.Key key) {
        kb.setBoundKey(key);
        KeyBinding.updateKeysByCode();
        if (client != null) client.options.write();
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
        if (view == View.MAIN && tab == Tab.RECORDER) {
            String s = "Recorder  |  " + (Recorder.hasRecording() ? Recorder.secs(Recorder.frameCount()) + " s gravados" : "sem gravação");
            s += "  |  " + Recorder.keyName(FarmBotClient.recordKey) + " grava, " + Recorder.keyName(FarmBotClient.replayKey) + " replay";
            return s;
        }
        boolean farm = FarmConfig.task == FarmConfig.Task.FARM;
        String s = farm ? "Farm" : "Modo " + FarmConfig.mineZone;
        if (!farm && FarmConfig.mineZone == FarmConfig.MineZone.PR) {
            s += FarmConfig.prMode == FarmConfig.PrMode.TOP ? " (em cima)" : " (corredor)";
        }
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
        if (listening != 0) {   // esperando uma tecla/botão para o atalho do Recorder
            KeyBinding kb = listening == 1 ? FarmBotClient.recordKey : FarmBotClient.replayKey;
            listening = 0;
            if (button >= 2) bindKey(kb, InputUtil.Type.MOUSE.createFromCode(button));   // botão do meio / laterais
            return true;                                                                  // esquerdo/direito = cancela
        }
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != 0) {
            KeyBinding kb = listening == 1 ? FarmBotClient.recordKey : FarmBotClient.replayKey;
            listening = 0;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) return true;   // cancela sem fechar a tela
            bindKey(kb, keyCode == GLFW.GLFW_KEY_BACKSPACE ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
                else if (id.equals("recorder")) setTab(Tab.RECORDER);
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
            case H_REC_KEY:
                listening = listening == 1 ? 0 : 1;
                break;
            case H_PLAY_KEY:
                listening = listening == 2 ? 0 : 2;
                break;
            case H_REC_BTN:
                if (client == null) break;
                if (Recorder.state() == Recorder.State.RECORDING) {
                    Recorder.stopRecording(client);
                } else {
                    Recorder.startRecording(client);
                    if (Recorder.state() == Recorder.State.RECORDING) close();   // fecha o menu para você poder jogar
                }
                break;
            case H_PLAY_BTN:
                if (client == null) break;
                if (Recorder.state() == Recorder.State.PLAYING) {
                    Recorder.stopPlayback(client, "§eReplay interrompido.");
                } else {
                    Recorder.startPlayback(client);
                    if (Recorder.state() == Recorder.State.PLAYING) close();
                }
                break;
            case H_LOOP:
                FarmConfig.recorderLoop = !FarmConfig.recorderLoop;
                break;
            case H_CLEAR:
                if (client != null) Recorder.clear(client);
                break;
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
