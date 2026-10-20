package com.farmbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Farm "legit": o personagem corre pela área, mira (câmera suave) na planta e só quebra
 * o bloco que está realmente sob a mira, como um jogador segurando o botão de ataque.
 */
public class FarmBot {
    private static final Random RNG = new Random();

    /** Alcance real do clique (igual ao do jogo, 4.5 blocos). */
    private static final double BREAK_RANGE = 4.5;
    /** Distância a partir da qual a câmera começa a mirar na planta alvo. */
    private static final double AIM_RANGE = 6.5;
    /** Alcance usado na mineração para escolher blocos. */
    private static final double REACH = 4.0;
    /** Tolerância vertical do farm: aceita plantas alguns blocos acima/abaixo da área. */
    private static final int V_TOL = 2;
    private static final int MAX_VOLUME_FARM = 250_000;
    /** Mineração varre por fatias, então o limite é em colunas (X*Z), não em volume. */
    private static final long MAX_COLUMNS_MINE = 4_000_000L;
    private static final double WALK_SPEED = 0.25; // blocos por tick (~5 blocos/s, corrida)
    private static final double FLY_SPEED = 0.32;
    /** Inclinação da câmera na mineração (graus para baixo): olha o chão logo à frente. */
    private static final float MINE_PITCH = 58f;
    /** EC: aceita blocos até 2 abaixo do Y mínimo da área (quem marca os cantos em pé/voando fica acima do chão). */
    private static final int MINE_V_TOL = 2;

    // --- farm ---
    private static final List<BlockPos> ready = new ArrayList<>();
    private static final Map<BlockPos, Crop> replantQueue = new LinkedHashMap<>();

    // --- mineração ---
    private static final List<BlockPos> mineCandidates = new ArrayList<>();
    private static int mineCount = 0;
    private static boolean flyBoosted;                  // velocidade de voo do jogador alterada pela mineração
    private static float origFlySpeed = 0.05f;
    private static int mineTopY = Integer.MIN_VALUE;   // Y do bloco mais alto achado no último scan (altura do voo)
    private static BlockPos slowPos;                    // bloco lento em quebra (detecta bloco protegido)
    private static int slowTicks;
    private static final Map<BlockPos, Integer> breakCount = new HashMap<>();   // quebras repetidas (servidor devolveu o bloco)

    // --- movimento / câmera ---
    private static BlockPos targetBlock;
    private static Vec3d target;
    private static Vec3d aimOffset = Vec3d.ZERO;       // pequena variação de onde mirar
    private static Vec3d cmdVel = Vec3d.ZERO;           // velocidade comandada (suavizada)
    private static Vec3d aimPoint;                      // ponto para onde a câmera deve olhar
    private static int idleTicks;
    private static final Set<BlockPos> badTargets = new HashSet<>();
    private static int scanTimer, cooldown, targetAge, stuckTimer, statusTimer, tickCounter;
    private static Vec3d lastCheckPos;
    private static boolean warnedFly, warnedHunger;
    // corrida "de verdade" (teclas) no farm
    private static boolean keysHeld;        // estamos segurando W/pulo
    private static boolean breakingSlow;    // quebrando algo que demora (melancia etc.): para de andar
    private static int forwardTicks, headingTicks, rightClickCooldown, lastTurnSign = 1;
    private static float heading;       // rumo de corrida (yaw) atual

    public static int targetCount() {
        return FarmConfig.task == FarmConfig.Task.FARM ? ready.size() : Math.max(mineCount, 0);
    }

    // ------------------------------------------------------------------
    public static void toggle(MinecraftClient mc) {
        if (mc.player == null) return;
        if (FarmConfig.enabled) { stop(mc, "§eFarm Bot DESLIGADO"); return; }

        boolean mining = FarmConfig.task == FarmConfig.Task.MINE;
        if (!FarmConfig.areaSet) { msg(mc, "§cDefina a área primeiro (tecla G)."); return; }
        if (!mining && FarmConfig.crops.isEmpty()) { msg(mc, "§cEscolha ao menos uma planta."); return; }
        if (mining && !mc.player.getAbilities().allowFlying) {
            msg(mc, "§cA mineração (EC/PR) funciona voando, e você não pode voar aqui.");
            return;
        }
        if (!mining && FarmConfig.volume() > MAX_VOLUME_FARM) {
            msg(mc, "§cÁrea grande demais (" + FarmConfig.volume() + " blocos, máx " + MAX_VOLUME_FARM + ").");
            return;
        }
        if (mining && columns() > MAX_COLUMNS_MINE) {
            msg(mc, "§cÁrea grande demais (" + columns() + " colunas X*Z, máx " + MAX_COLUMNS_MINE + ").");
            return;
        }
        Recorder.cancelAll(mc);   // o Recorder não pode brigar com o bot pelo controle do jogador
        ready.clear();
        replantQueue.clear();
        mineCandidates.clear();
        badTargets.clear();
        mineCount = 0;
        surfaceY = Integer.MIN_VALUE;
        surfaceTimer = 0;
        corTarget = null;
        corVel = Vec3d.ZERO;
        mineEmptyTicks = 0;
        mineTopY = Integer.MIN_VALUE;
        slowPos = null;
        slowTicks = 0;
        breakCount.clear();
        target = null; targetBlock = null; lastCheckPos = null; aimPoint = null;
        cmdVel = Vec3d.ZERO;
        idleTicks = 0;
        scanTimer = 0; cooldown = 0; statusTimer = 0; warnedFly = false; warnedHunger = false;
        forwardTicks = 0; breakingSlow = false; headingTicks = 0; rightClickCooldown = 0;
        heading = mc.player.getYaw();
        mineSlow = 1.0;
        HumanLook.reset();
        if (mining && prCorridor() && !initCorridor(mc, mc.player)) {
            msg(mc, "§cModo Corredor: fique no espaço entre a bedrock e a mina, de frente para ela (a menos de 14 blocos), e ligue de novo.");
            return;
        }
        armGuard(mc);
        stopAlarm(mc);
        FarmConfig.enabled = true;
        msg(mc, mining ? "§aMineração " + FarmConfig.mineZone + (isPR() ? (prCorridor() ? " (corredor)" : " (em cima)") : "") + " LIGADA" : "§aFarm LIGADO");
    }

    public static void stop(MinecraftClient mc, String message) {
        FarmConfig.enabled = false;
        restoreFly(mc);
        corVel = Vec3d.ZERO;
        corTarget = null;
        cmdVel = Vec3d.ZERO;
        aimPoint = null;
        releaseKeys(mc);
        HumanLook.reset();
        if (message != null) msg(mc, message);
    }

    // ------------------------------------------------------------------
    //  PROTEÇÃO: reinício do servidor / teleporte / saída da área demarcada
    // ------------------------------------------------------------------
    /** Blocos em 1 tick a partir dos quais consideramos teleporte (voo normal ~0.3-0.5/tick). */
    private static final double TELEPORT_DIST = 8.0;
    /** Folga horizontal além da borda da área antes de considerar "fora". */
    private static final double SLACK_XZ = 3.0;
    /** Folga vertical (o farm já aceita V_TOL=2 acima/abaixo). */
    private static final double SLACK_Y = 4.0;

    private static ClientPlayerEntity guardPlayer;
    private static ClientWorld guardWorld;
    private static Vec3d guardPos;
    private static boolean guardWasInside;

    // --- alerta sonoro (assets/farmbot/sounds/alarm.ogg, ~30 s) ---
    private static final SoundEvent ALARM = SoundEvent.of(Identifier.of("farmbot", "alarm"));
    private static boolean alertPending;
    private static PositionedSoundInstance alarmInstance;

    /** Para o bot por um motivo da proteção e dispara o som de alerta. */
    private static void guardStop(MinecraftClient mc, String message) {
        stop(mc, message);
        alert();
    }

    private static void alert() {
        if (!FarmConfig.alertSound) return;
        alertPending = true;
    }

    /** Dispara o alarme da proteção (usado também pelo Recorder). */
    public static void raiseAlert() {
        alert();
    }

    /** Botão de teste do menu: toca o alarme (ignora a opção ligado/desligado). */
    public static void testAlert(MinecraftClient mc) {
        alertPending = true;
        tickAlert(mc);   // sai na hora, sem esperar o próximo tick
    }

    /** Para o alarme se estiver tocando (chamado ao ligar o bot). */
    public static void stopAlarm(MinecraftClient mc) {
        alertPending = false;
        if (alarmInstance != null) {
            mc.getSoundManager().stop(alarmInstance);
            alarmInstance = null;
        }
    }

    /** Dispara o alarme pendente. Roda todo tick, mesmo com o bot desligado ou em menus. */
    private static void tickAlert(MinecraftClient mc) {
        if (!alertPending) return;
        alertPending = false;
        // não reinicia se já estiver tocando
        if (alarmInstance != null && mc.getSoundManager().isPlaying(alarmInstance)) return;
        alarmInstance = PositionedSoundInstance.master(ALARM, 1.0f, 1.0f);
        mc.getSoundManager().play(alarmInstance);
    }

    // ------------------------------------------------------------------
    //  PROTEÇÃO EXTRA: ferramenta trocada / mapa de captcha
    // ------------------------------------------------------------------
    private static final int TOOL_MISMATCH_TICKS = 3;   // tolera falha de sincronização do inventário
    private static final Item[] toolSnap = new Item[9]; // ferramenta de cada slot da hotbar ao ligar
    private static final int[] toolBad = new int[9];
    private static int mapBaseline;

    private static boolean isGuardTool(ItemStack s) {
        return !s.isEmpty() && (s.isIn(ItemTags.AXES) || s.isIn(ItemTags.HOES)
                || s.isIn(ItemTags.PICKAXES) || s.isIn(ItemTags.SHOVELS));
    }

    private static int countMaps(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).isOf(Items.FILLED_MAP)) n++;
        }
        return n;
    }

    private static void snapshotItems(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            toolSnap[i] = isGuardTool(st) ? st.getItem() : null;
            toolBad[i] = 0;
        }
        mapBaseline = countMaps(p);
    }

    /** @return true se tudo certo; false se o bot foi parado por ferramenta trocada ou mapa de captcha. */
    private static boolean itemGuard(MinecraftClient mc, ClientPlayerEntity p) {
        // 1) Recebeu um mapa (captcha em mapa) ou está segurando um na mão
        if (countMaps(p) > mapBaseline
                || p.getMainHandStack().isOf(Items.FILLED_MAP) && mapBaseline == 0
                || p.getOffHandStack().isOf(Items.FILLED_MAP) && mapBaseline == 0) {
            guardStop(mc, "§cFarm Bot parado: você recebeu um mapa (possível CAPTCHA)! Resolva-o.");
            return false;
        }
        // 2) A ferramenta de algum slot foi trocada por outra coisa (ou sumiu)
        for (int i = 0; i < 9; i++) {
            if (toolSnap[i] == null) continue;
            ItemStack st = p.getInventory().getStack(i);
            if (st.isOf(toolSnap[i])) { toolBad[i] = 0; continue; }
            if (++toolBad[i] >= TOOL_MISMATCH_TICKS) {
                guardStop(mc, "§cFarm Bot parado: a ferramenta do slot " + (i + 1) + " foi trocada ou sumiu.");
                return false;
            }
        }
        return true;
    }

    /** Guarda a "foto" do estado no momento em que o bot é ligado. */
    private static void armGuard(MinecraftClient mc) {
        guardPlayer = mc.player;
        guardWorld = mc.world;
        guardPos = mc.player.getPos();
        guardWasInside = nearArea(guardPos);
        snapshotItems(mc.player);
    }

    private static boolean nearArea(Vec3d v) {
        return v.x >= FarmConfig.minX() - SLACK_XZ && v.x <= FarmConfig.maxX() + 1 + SLACK_XZ
                && v.z >= FarmConfig.minZ() - SLACK_XZ && v.z <= FarmConfig.maxZ() + 1 + SLACK_XZ
                && v.y >= FarmConfig.minY() - SLACK_Y && v.y <= FarmConfig.maxY() + 1 + SLACK_Y;
    }

    /** @return true se pode continuar; false se o bot foi parado. */
    private static boolean safetyGuard(MinecraftClient mc, ClientPlayerEntity p) {
        // 1) Servidor reiniciou / troca de servidor via proxy / respawn / mudou de dimensão:
        //    o jogo cria instâncias novas de jogador e mundo, mesmo sem desconectar.
        if (p != guardPlayer || mc.world != guardWorld) {
            guardStop(mc, "§cFarm Bot parado: o servidor reiniciou ou você mudou de mundo/respawnou.");
            return false;
        }

        Vec3d pos = p.getPos();

        // 2) Teleporte: salto grande de posição entre dois ticks.
        if (guardPos != null && pos.squaredDistanceTo(guardPos) > TELEPORT_DIST * TELEPORT_DIST) {
            guardStop(mc, "§cFarm Bot parado: você foi teleportado.");
            return false;
        }
        guardPos = pos;

        // 2b) Ferramenta trocada / mapa de captcha
        if (!itemGuard(mc, p)) return false;

        // 3) Fora da área demarcada. Só vale depois de já ter estado dentro, para não
        //    desligar o bot se você ligar de longe e ele ainda estiver indo até a área.
        if (nearArea(pos)) {
            guardWasInside = true;
        } else if (guardWasInside) {
            guardStop(mc, "§cFarm Bot parado: você saiu da área demarcada.");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    public static void tick(MinecraftClient mc) {
        tickAlert(mc);
        if (!FarmConfig.enabled) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            FarmConfig.enabled = false;
            releaseKeys(mc);
            alert();   // conexão perdida (servidor reiniciou/caiu): avisa também
            return;
        }

        // Antes do check de tela: teleporte/troca de mundo abrem a tela de carregamento.
        if (FarmConfig.guardEnabled && !safetyGuard(mc, p)) return;

        if (mc.currentScreen != null) {
            cmdVel = Vec3d.ZERO;
            releaseKeys(mc);
            HumanLook.reset();
            return;
        }

        tickCounter++;
        if (tickCounter % 600 == 0) { badTargets.clear(); breakCount.clear(); }
        boolean mining = FarmConfig.task == FarmConfig.Task.MINE;
        aimPoint = null;

        if (mining && !p.getAbilities().allowFlying) {
            stop(mc, "§cMineração parada: você perdeu a permissão de voar.");
            return;
        }

        if (--scanTimer <= 0) {
            if (mining) {
                scanTimer = scanMine(mc, p);
                if (mineCount == 0) {
                    // mina vazia: espera regenerar (minas de servidor resetam) antes de desistir
                    mineEmptyTicks += scanTimer;
                    if (mineEmptyTicks >= MINE_REGEN_WAIT) {
                        stop(mc, "§aMineração concluída: nada novo para minerar há 5 minutos.");
                        return;
                    }
                } else {
                    mineEmptyTicks = 0;
                }
            } else {
                scanFarm(mc);
                scanTimer = 10;
            }
        }

        if (++statusTimer >= 40) { statusTimer = 0; reportStatus(mc); }

        if (mining) {
            breakingSlow = false;
            if (prCorridor()) runCorridor(mc, p);  // PR corredor: sobe/desce em diagonais minerando a parede
            else runMine(mc, p);                   // voo contínuo (W + sprint) com a câmera olhando para baixo
            mineAct(mc, p);                        // quebra o que estiver sob a mira, TODO tick
        } else {
            boolean holdForBreak = breakingSlow;   // veio do tick anterior
            breakingSlow = false;
            if (wantsFly(mc, p)) {
                releaseKeys(mc);
                move(mc, p, false);                // voando: movimento por velocidade
                setFarmAim(p);
                updateLook(p);
            } else {
                cmdVel = Vec3d.ZERO;
                runFarm(mc, p, holdForBreak);      // corrida de verdade (W + sprint) + câmera
            }
            farmAct(mc, p);                        // quebra o que estiver sob a mira, TODO tick
        }
    }

    private static void reportStatus(MinecraftClient mc) {
        if (FarmConfig.task == FarmConfig.Task.MINE) {
            String zone = "[" + FarmConfig.mineZone + "] ";
            if (mineCount == 0) {
                String hint = "";
                if (mc.player != null) {
                    int fy = floorYUnder(mc, mc.player);
                    if (fy != Integer.MIN_VALUE && (fy < mineLowY() || fy > FarmConfig.maxY())) {
                        hint = " Chão em Y=" + fy + ", fora da área (Y " + mineLowY() + " a " + FarmConfig.maxY() + "). Ajuste o Y.";
                    }
                }
                msg(mc, "§e" + zone + "Aguardando a mina regenerar..." + hint);
            } else if (isPR()) {
                msg(mc, "§b" + zone + mineLayerInfo() + " | Alvos: " + mineCount);
            } else {
                msg(mc, "§b" + zone + "Alvos: " + mineCount);
            }
        } else if (ready.isEmpty() && replantQueue.isEmpty()) {
            msg(mc, "§eNenhuma planta pronta na área (Y " + (FarmConfig.minY() - V_TOL)
                    + " a " + (FarmConfig.maxY() + V_TOL) + "). Confira as coordenadas.");
        } else {
            msg(mc, "§aProntas: " + ready.size() + " | Replantar: " + replantQueue.size());
        }
    }

    // ==================================================================
    //                              FARM
    // ==================================================================
    private static boolean inFarmArea(BlockPos pos) {
        return pos.getX() >= FarmConfig.minX() && pos.getX() <= FarmConfig.maxX()
                && pos.getZ() >= FarmConfig.minZ() && pos.getZ() <= FarmConfig.maxZ()
                && pos.getY() >= FarmConfig.minY() - V_TOL && pos.getY() <= FarmConfig.maxY() + V_TOL;
    }

    private static void scanFarm(MinecraftClient mc) {
        ready.clear();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = FarmConfig.minX(); x <= FarmConfig.maxX(); x++)
            for (int y = FarmConfig.minY() - V_TOL; y <= FarmConfig.maxY() + V_TOL; y++)
                for (int z = FarmConfig.minZ(); z <= FarmConfig.maxZ(); z++) {
                    BlockState s = mc.world.getBlockState(m.set(x, y, z));
                    if (s.isAir()) continue;
                    for (Crop c : FarmConfig.crops) {
                        if (c.isReady(mc.world, m, s)) { ready.add(m.toImmutable()); break; }
                    }
                }
    }

    private static Crop matchCrop(MinecraftClient mc, BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        for (Crop c : FarmConfig.crops) if (c.isReady(mc.world, pos, s)) return c;
        return null;
    }

    /** Define o ponto de mira: a planta alvo (ou a terra onde vai replantar) quando está perto. */
    private static void setFarmAim(ClientPlayerEntity p) {
        if (targetBlock == null) return;
        Vec3d c;
        if (replantQueue.containsKey(targetBlock)) {
            c = Vec3d.ofCenter(targetBlock.down()).add(aimOffset.x, 0.5, aimOffset.z);
        } else {
            c = Vec3d.ofCenter(targetBlock).add(aimOffset);
        }
        if (p.getEyePos().squaredDistanceTo(c) <= AIM_RANGE * AIM_RANGE) aimPoint = c;
    }

    /**
     * Age SOMENTE no bloco que está sob a mira (como segurar o botão de ataque / clicar).
     * Se a mira não estiver sobre uma planta pronta, não faz nada.
     */
    private static void farmAct(MinecraftClient mc, ClientPlayerEntity p) {
        HitResult hr = p.raycast(BREAK_RANGE, 1.0f, false);
        if (hr.getType() != HitResult.Type.BLOCK) return;
        BlockHitResult bhr = (BlockHitResult) hr;
        BlockPos pos = bhr.getBlockPos();

        // 1) replantar: mira na terra, face de cima, onde a planta foi colhida
        BlockPos above = pos.up();
        Crop rc = replantQueue.get(above);
        if (rc != null && bhr.getSide() == Direction.UP && mc.world.getBlockState(above).isAir()) {
            int slot = findHotbarSlot(p, rc.seed);
            if (slot < 0) {
                replantQueue.clear();
                msg(mc, "§cSem sementes na hotbar: " + rc.label + " (não vou replantar).");
                return;
            }
            p.getInventory().selectedSlot = slot;
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, bhr);
            p.swingHand(Hand.MAIN_HAND);
            replantQueue.remove(above);
            return;
        }

        // 2) colher o bloco sob a mira
        if (!inFarmArea(pos)) return;
        Crop crop = matchCrop(mc, pos);
        if (crop == null) return;

        BlockState bs = mc.world.getBlockState(pos);
        if (crop.usesShears()) {
            int sh = findHotbarSlot(p, Items.SHEARS);
            if (sh >= 0) p.getInventory().selectedSlot = sh;
        } else if (crop.category == Crop.Category.CORAL) {
            // Corais: só machado ou enxada (planta/leque têm dureza 0, então vem antes do check de dureza).
            if (!selectAxeOrHoe(p, bs)) {
                stop(mc, "§cColoque um machado ou enxada na hotbar para quebrar corais.");
                return;
            }
        } else if (bs.getHardness(mc.world, pos) > 0) {
            selectBestTool(p, bs);
        }

        if (crop.usesRightClick()) {
            if (rightClickCooldown > 0) { rightClickCooldown--; return; } // evita spam enquanto o servidor responde
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, bhr);
            p.swingHand(Hand.MAIN_HAND);
            ready.remove(pos);
            rightClickCooldown = 4;
            return;
        }

        mc.interactionManager.updateBlockBreakingProgress(pos, bhr.getSide());
        p.swingHand(Hand.MAIN_HAND);

        if (mc.world.getBlockState(pos).isAir()) {
            ready.remove(pos);
            if (FarmConfig.replant && crop.seed != null) {
                if (replantQueue.size() > 2000) replantQueue.clear();
                replantQueue.put(pos, crop);
            }
        } else if (!p.getAbilities().creativeMode && bs.getHardness(mc.world, pos) > 0) {
            breakingSlow = true; // ainda quebrando (melancia, abóbora...): para de andar até terminar
        }
    }

    private static int findHotbarSlot(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!st.isEmpty() && st.isOf(item)) return i;
        }
        return -1;
    }

    // ==================================================================
    //                           MINERAÇÃO
    // ==================================================================

    private static boolean isOre(BlockState s) {
        return s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.GOLD_ORES)
                || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.REDSTONE_ORES)
                || s.isIn(BlockTags.LAPIS_ORES) || s.isIn(BlockTags.DIAMOND_ORES)
                || s.isIn(BlockTags.EMERALD_ORES)
                || s.isOf(Blocks.NETHER_QUARTZ_ORE) || s.isOf(Blocks.NETHER_GOLD_ORE)
                || s.isOf(Blocks.ANCIENT_DEBRIS);
    }

    private static final Set<Block> TERRACOTTA = Set.of(
            Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
            Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA,
            Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA,
            Blocks.BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
            Blocks.BLACK_TERRACOTTA,
            Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.MAGENTA_GLAZED_TERRACOTTA,
            Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA,
            Blocks.PINK_GLAZED_TERRACOTTA, Blocks.GRAY_GLAZED_TERRACOTTA, Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA,
            Blocks.CYAN_GLAZED_TERRACOTTA, Blocks.PURPLE_GLAZED_TERRACOTTA, Blocks.BLUE_GLAZED_TERRACOTTA,
            Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA, Blocks.RED_GLAZED_TERRACOTTA,
            Blocks.BLACK_GLAZED_TERRACOTTA);

    /** Terracota lisa ou vitrificada, de qualquer cor. */
    private static boolean isTerracotta(BlockState s) {
        return TERRACOTTA.contains(s.getBlock());
    }

    private static boolean isExposed(World w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (!w.getBlockState(n).isSolidBlock(w, n)) return true;
        }
        return false;
    }

    private static boolean isMinable(World w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.hasBlockEntity()) return false;
        if (s.getHardness(w, pos) < 0) return false;
        if (FarmConfig.oresOnly()) {
            // no PR, "só minérios" também quebra terracota (o piso das minas de prisão)
            if (isPR() ? !(isOre(s) || isTerracotta(s)) : !isOre(s)) return false;
        }
        for (Direction d : Direction.values()) {
            if (!w.getFluidState(pos.offset(d)).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Varredura de alvos. EC = a área inteira (zona plana).
    //  PR "em cima" = só a superfície atual (mapa de alturas): quando a camada de cima é quebrada
    //  (por encantamentos), a superfície desce e o bot desce junto.
    //  PR "corredor" = a faixa da parede da mina ao lado do jogador (ver mais abaixo).
    // ------------------------------------------------------------------
    private static final int MINE_WINDOW = 40;        // raio (blocos) da varredura local em volta do jogador
    private static final int MINE_NEAR_K = 24;        // quantos alvos próximos guardar
    private static final int MINE_REGEN_WAIT = 6000;  // ticks (~5 min) esperando a mina regenerar antes de parar
    private static final int PR_LAYERS = 3;           // camadas abaixo da superfície que o braço alcança (modo em cima)
    private static int mineEmptyTicks;
    private static int surfaceY = Integer.MIN_VALUE;  // PR em cima: altura da superfície sólida logo abaixo/à frente do jogador
    private static int surfaceTimer;

    private static boolean isPR() {
        return FarmConfig.mineZone == FarmConfig.MineZone.PR;
    }

    private static boolean prTop() {
        return isPR() && FarmConfig.prMode == FarmConfig.PrMode.TOP;
    }

    private static boolean prCorridor() {
        return isPR() && FarmConfig.prMode == FarmConfig.PrMode.CORRIDOR;
    }

    private static long columns() {
        return (long) (FarmConfig.maxX() - FarmConfig.minX() + 1) * (FarmConfig.maxZ() - FarmConfig.minZ() + 1);
    }

    private static int slabTopY() {
        return FarmConfig.maxY();
    }

    private static int slabBottomY() {
        return mineLowY();
    }

    /** Menor Y aceito na mineração (EC tem folga para baixo; PR usa o Y marcado). */
    private static int mineLowY() {
        return isPR() ? FarmConfig.minY() : FarmConfig.minY() - MINE_V_TOL;
    }

    private static boolean inMineArea(int x, int y, int z) {
        return x >= FarmConfig.minX() && x <= FarmConfig.maxX()
                && z >= FarmConfig.minZ() && z <= FarmConfig.maxZ()
                && y >= mineLowY() && y <= FarmConfig.maxY();
    }

    /** Y do primeiro bloco não-vazio sob o jogador (até 16 abaixo); MIN_VALUE se não houver. */
    private static int floorYUnder(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        int x = p.getBlockPos().getX(), z = p.getBlockPos().getZ(), y0 = p.getBlockPos().getY();
        for (int y = y0; y >= y0 - 16; y--) {
            m.set(x, y, z);
            if (!mc.world.getBlockState(m).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Texto do modo/camada atual (modo PR) para o menu e o status. */
    public static String mineLayerInfo() {
        if (FarmConfig.task != FarmConfig.Task.MINE || !isPR()) return "";
        if (prCorridor()) return "Corredor";
        return surfaceY == Integer.MIN_VALUE ? "Em cima" : "Em cima, superfície Y " + surfaceY;
    }

    /**
     * Procura alvos: primeiro numa janela em volta do jogador (barato) e, se estiver vazia, na área inteira.
     *
     * @return em quantos ticks escanear de novo
     */
    private static int scanMine(MinecraftClient mc, ClientPlayerEntity p) {
        if (prCorridor()) return scanCorridor(mc, p);
        int px = p.getBlockPos().getX(), pz = p.getBlockPos().getZ();
        scanFlat(mc, p, Math.max(FarmConfig.minX(), px - MINE_WINDOW), Math.min(FarmConfig.maxX(), px + MINE_WINDOW),
                Math.max(FarmConfig.minZ(), pz - MINE_WINDOW), Math.min(FarmConfig.maxZ(), pz + MINE_WINDOW));
        if (mineCount > 0) return 40;
        scanFlat(mc, p, FarmConfig.minX(), FarmConfig.maxX(), FarmConfig.minZ(), FarmConfig.maxZ());
        long cells = columns() * (isPR() ? PR_LAYERS : Math.max(1, FarmConfig.maxY() - mineLowY() + 1));
        return (int) Math.max(40, Math.min(400, cells / 5000));
    }

    private static void scanFlat(MinecraftClient mc, ClientPlayerEntity p, int x0, int x1, int z0, int z1) {
        if (prTop()) scanTop(mc, p, x0, x1, z0, z1);
        else scanSlab(mc, p, x0, x1, z0, z1, mineLowY(), FarmConfig.maxY());
    }

    /** Guarda o alvo entre os MINE_NEAR_K de menor chave (lista ordenada). */
    private static void offer(BlockPos pos, double key, double[] keys) {
        int n = mineCandidates.size();
        if (n == MINE_NEAR_K) {
            if (key >= keys[n - 1]) return;
            mineCandidates.remove(n - 1);
            n--;
        }
        int i = n;
        while (i > 0 && keys[i - 1] > key) {
            keys[i] = keys[i - 1];
            i--;
        }
        keys[i] = key;
        mineCandidates.add(i, pos.toImmutable());
    }

    /** Conta os blocos quebráveis e expostos da região e guarda os MINE_NEAR_K mais bem posicionados (EC). */
    private static void scanSlab(MinecraftClient mc, ClientPlayerEntity p, int x0, int x1, int z0, int z1, int yLo, int yHi) {
        mineCandidates.clear();
        mineCount = 0;
        int topY = Integer.MIN_VALUE;
        final double px = p.getX(), pz = p.getZ();
        final double[] keys = new double[MINE_NEAR_K];
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = yHi; y >= yLo; y--) {
                    m.set(x, y, z);
                    if (!isMinable(mc.world, m)) continue;
                    if (badTargets.contains(m)) continue;   // já tentei e não deu para quebrar: ignora por um tempo
                    if (!isExposed(mc.world, m)) continue;
                    mineCount++;
                    if (y > topY) topY = y;
                    double dx = x + 0.5 - px, dz = z + 0.5 - pz, dy = yHi - y;
                    offer(m, dx * dx + dz * dz + 9.0 * dy * dy, keys);   // perto primeiro; camada de cima primeiro
                }
        if (mineCount > 0) mineTopY = topY;
    }

    /** Y do primeiro bloco não-vazio da coluna, de cima para baixo; MIN_VALUE se a coluna estiver vazia. */
    private static int columnTop(World w, BlockPos.Mutable m, int x, int z, int yHi, int yLo) {
        for (int y = yHi; y >= yLo; y--) {
            m.set(x, y, z);
            if (!w.getBlockState(m).isAir()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * PR "em cima": mapa de alturas. Em cada coluna acha a superfície e olha só as PR_LAYERS camadas de
     * cima (o que o braço alcança voando logo acima). Se a camada de cima foi quebrada por um encantamento,
     * a superfície daquela coluna já é a camada de baixo, e é nela que o bot passa a trabalhar.
     */
    private static void scanTop(MinecraftClient mc, ClientPlayerEntity p, int x0, int x1, int z0, int z1) {
        mineCandidates.clear();
        mineCount = 0;
        final double px = p.getX(), pz = p.getZ();
        final double[] keys = new double[MINE_NEAR_K];
        final int yHi = FarmConfig.maxY(), yLo = mineLowY();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                int ys = columnTop(mc.world, m, x, z, yHi, yLo);
                if (ys == Integer.MIN_VALUE) continue;
                for (int y = ys; y >= Math.max(yLo, ys - (PR_LAYERS - 1)); y--) {
                    m.set(x, y, z);
                    if (!isMinable(mc.world, m)) continue;
                    if (badTargets.contains(m)) continue;
                    if (!isExposed(mc.world, m)) continue;
                    mineCount++;
                    double dx = x + 0.5 - px, dz = z + 0.5 - pz, dy = ys - y;
                    offer(m, dx * dx + dz * dz + 9.0 * dy * dy, keys);
                }
            }
    }

    /** Maior superfície sólida nas colunas (dentro da área) num raio r em volta do ponto. */
    private static int topAround(MinecraftClient mc, BlockPos.Mutable m, double cx, double cz, int r) {
        int best = Integer.MIN_VALUE;
        int bx = MathHelper.floor(cx), bz = MathHelper.floor(cz);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx, z = bz + dz;
                if (x < FarmConfig.minX() || x > FarmConfig.maxX() || z < FarmConfig.minZ() || z > FarmConfig.maxZ()) continue;
                int y = columnTop(mc.world, m, x, z, FarmConfig.maxY(), mineLowY());
                if (y > best) best = y;
            }
        return best;
    }

    /**
     * Altura da superfície sólida perto do jogador e um pouco à frente. O voo "em cima" paira
     * logo acima disso: sobe se o terreno sobe e DESCE quando a camada de baixo do bot foi quebrada.
     */
    private static int localSolidTop(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        int best = topAround(mc, m, p.getX(), p.getZ(), 3);
        best = Math.max(best, topAround(mc, m, p.getX() + dirX(heading) * 5, p.getZ() + dirZ(heading) * 5, 2));
        return best;
    }

    private static BlockHitResult rayTo(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        Vec3d eye = p.getEyePos();
        Vec3d to = Vec3d.ofCenter(pos);
        BlockHitResult r = mc.world.raycast(new RaycastContext(eye, to,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(pos)) return r;
        return null;
    }

    private static boolean playerAllows(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos, BlockState s) {
        BlockPos feet = p.getBlockPos();
        if (pos.equals(feet.down()) && !p.getAbilities().flying) {
            BlockPos below = pos.down();
            if (!mc.world.getBlockState(below).isSolidBlock(mc.world, below)) return false;
        }
        if (s.getBlock() instanceof FallingBlock
                && pos.getX() == feet.getX() && pos.getZ() == feet.getZ() && pos.getY() > feet.getY() + 1) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  MINERAÇÃO VOANDO, no estilo do farm: escolhe um rumo, voa com W + sprint, a câmera acompanha o
    //  rumo olhando para baixo e o bot quebra o bloco que estiver sob a mira, a cada tick.
    // ------------------------------------------------------------------

    /** Aplica o multiplicador de velocidade de voo (o sprint dobra o valor base). Restaurado em stop(). */
    private static void boostFly(ClientPlayerEntity p) {
        if (!flyBoosted) {
            origFlySpeed = p.getAbilities().getFlySpeed();
            flyBoosted = true;
        }
        p.getAbilities().setFlySpeed(origFlySpeed * FarmConfig.mineFlySpeed);
    }

    private static void restoreFly(MinecraftClient mc) {
        if (flyBoosted && mc.player != null) mc.player.getAbilities().setFlySpeed(origFlySpeed);
        flyBoosted = false;
    }

    /**
     * Fator medido em simulação da câmera (HumanLook, 18°/tick máx) com a velocidade acompanhando a
     * câmera: numa curva de 90° o bot ainda avança ~6,5 x velocidade (blocos/tick) no rumo antigo
     * antes de curvar de vez. Usamos 8 para cobrir cantos (duas paredes ao mesmo tempo).
     */
    private static final double TURN_ADVANCE = 8.0;
    /** Folga extra: balanço da câmera + 1 tick de atraso. */
    private static final double TURN_MARGIN = 1.5;
    /** Teto absoluto de velocidade de voo na mineração (blocos/tick; 1.5 = 30 blocos/s). */
    private static final double MAX_MINE_SPEED = 1.5;
    /** Multiplicador de segurança (0.25–1): freio de emergência quando nenhum arco cabe na área. */
    private static double mineSlow = 1.0;

    /** Velocidade horizontal atual (blocos/tick). */
    private static double flatSpeed(ClientPlayerEntity p) {
        Vec3d v = p.getVelocity();
        return Math.hypot(v.x, v.z);
    }

    /**
     * Velocidade máxima de voo (blocos/tick) para a área: o arco de curva precisa caber nela, senão em
     * área pequena o bot passaria da borda. Área grande = só vale o teto e o multiplicador do menu.
     */
    private static double mineSpeedCap() {
        double cap = (0.28 * areaMinSide() - TURN_MARGIN) / TURN_ADVANCE;
        return MathHelper.clamp(cap, 0.15, MAX_MINE_SPEED) * mineSlow;
    }

    /** Velocidade terminal prevista do voo com W + sprint (vanilla ~1.0 bloco/tick por 1.0x). */
    private static double terminalSpeed() {
        float base = flyBoosted ? origFlySpeed : 0.05f;
        return 1.02 * (base / 0.05) * FarmConfig.mineFlySpeed;
    }

    /** Velocidade usada para prever curvas: a que o bot terá ao longo do arco (ainda acelerando). */
    private static double simSpeed(ClientPlayerEntity p) {
        return Math.min(mineSpeedCap(), Math.max(flatSpeed(p), terminalSpeed()));
    }

    /** Distância livre à frente que o bot precisa ter para fazer uma curva sem passar da borda. */
    private static double turnNeed(double sp) {
        return TURN_ADVANCE * (sp + 0.15) + TURN_MARGIN;
    }

    /** Velocidade vertical que paira na altura certa da superfície. */
    private static double hoverVy(ClientPlayerEntity p) {
        double lim = prTop() ? 0.4 : 0.5;   // em cima: desce mais devagar, sem despencar
        return MathHelper.clamp((mineHoverY() - p.getY()) * 0.35, -lim, lim);
    }

    /**
     * A velocidade acompanha a câmera: mantém a rapidez, mas aponta sempre para onde o jogador olha.
     * Sem isso o voo desliza de lado quando o yaw gira (inércia do voo). Com isso, as curvas viram
     * arcos suaves, sem soltar o W nem frear. Chamar DEPOIS de girar a câmera no tick.
     */
    private static void steerFlight(ClientPlayerEntity p) {
        double s = Math.min(flatSpeed(p), mineSpeedCap());
        float yaw = p.getYaw();
        p.setVelocity(dirX(yaw) * s, hoverVy(p), dirZ(yaw) * s);
    }

    /** Distância (blocos) do ponto até a borda mais próxima da área, já descontada a margem de borda. */
    private static double edgeDist(double x, double z) {
        double sx = FarmConfig.maxX() + 1 - FarmConfig.minX();
        double sz = FarmConfig.maxZ() + 1 - FarmConfig.minZ();
        double mx = Math.min(EDGE_MARGIN, sx / 3.0), mz = Math.min(EDGE_MARGIN, sz / 3.0);
        return Math.min(Math.min(x - (FarmConfig.minX() + mx), FarmConfig.maxX() + 1 - mx - x),
                Math.min(z - (FarmConfig.minZ() + mz), FarmConfig.maxZ() + 1 - mz - z));
    }

    /**
     * Simula a curva até o rumo {@code target} (mesma dinâmica da câmera, velocidade seguindo o yaw).
     *
     * @return {menor distância até a borda durante o arco, espaço livre à frente quando a curva termina}
     */
    private static double[] arcOutcome(ClientPlayerEntity p, float target, double sp) {
        float yaw = p.getYaw();
        float vel = HumanLook.yawVelocity();
        double x = p.getX(), z = p.getZ();
        double m = edgeDist(x, z);
        for (int i = 0; i < 60; i++) {
            float err = MathHelper.wrapDegrees(target - yaw);
            if (Math.abs(err) < 2f && Math.abs(vel) < 1f) break;
            vel = HumanLook.simYawVel(vel, err);
            yaw += vel;
            x += dirX(yaw) * sp;
            z += dirZ(yaw) * sp;
            m = Math.min(m, edgeDist(x, z));
        }
        return new double[] { m, freeDist(x, z, target, EDGE_MARGIN) };
    }

    /** Y (índice do bloco) da superfície que está sendo minerada. */
    private static int mineSurfaceY() {
        if (prTop()) return surfaceY != Integer.MIN_VALUE ? surfaceY : slabTopY();
        if (isPR()) return slabTopY();
        return (mineCount > 0 && mineTopY != Integer.MIN_VALUE) ? mineTopY : FarmConfig.maxY();
    }

    /** Altura (dos pés) do voo: logo acima da superfície, para a mira cair no chão a ~1 bloco à frente. */
    private static double mineHoverY() {
        return mineSurfaceY() + 1.3;
    }

    /** Quantos blocos quebráveis há na superfície ao longo deste rumo (até 14 blocos à frente). */
    private static int minableAlong(MinecraftClient mc, double px, double pz, float yaw) {
        double fx = dirX(yaw), fz = dirZ(yaw);
        int top = mineSurfaceY();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int n = 0;
        if (prTop()) {
            // em cima: a superfície de cada coluna à frente (já considera camadas quebradas por encantamentos)
            for (int i = 1; i <= 14; i++) {
                int bx = MathHelper.floor(px + fx * i), bz = MathHelper.floor(pz + fz * i);
                if (bx < FarmConfig.minX() || bx > FarmConfig.maxX() || bz < FarmConfig.minZ() || bz > FarmConfig.maxZ()) continue;
                int ys = columnTop(mc.world, m, bx, bz, FarmConfig.maxY(), mineLowY());
                if (ys == Integer.MIN_VALUE) continue;
                for (int y = ys; y >= Math.max(mineLowY(), ys - (PR_LAYERS - 1)); y--) {
                    m.set(bx, y, bz);
                    if (isMinable(mc.world, m)) { n++; break; }
                }
            }
            return n;
        }
        for (int i = 1; i <= 14; i++) {
            int bx = (int) Math.floor(px + fx * i), bz = (int) Math.floor(pz + fz * i);
            for (int y = top; y >= top - 1; y--) {
                if (!inMineArea(bx, y, bz) || y < slabBottomY()) continue;
                m.set(bx, y, bz);
                if (isMinable(mc.world, m)) { n++; break; }
            }
        }
        return n;
    }

    /**
     * Escolhe um novo rumo de voo, preferindo o que passa por mais blocos ainda não minerados
     * e, quando o entorno já está limpo, o que vai em direção aos blocos que restam.
     * Só aceita rumos cujo ARCO de curva (simulado) cabe dentro da área.
     * mode 0 = troca normal; 1 = chegando na borda; 2 = travou em algo.
     */
    private static void newMineHeading(MinecraftClient mc, ClientPlayerEntity p, int mode) {
        final double px = p.getX(), pz = p.getZ();
        final double sp = simSpeed(p);
        final double need = turnNeed(sp);
        float cur = heading;

        List<Float> options = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            float turn;
            if (mode == 0) {
                float mag = RNG.nextInt(100) < 70 ? 10f + RNG.nextFloat() * 35f : 60f + RNG.nextFloat() * 40f;
                turn = mag * (RNG.nextBoolean() ? 1 : -1);
            } else if (mode == 2) {
                turn = (90f + RNG.nextFloat() * 90f) * (RNG.nextBoolean() ? 1 : -1);
            } else {
                turn = (RNG.nextFloat() * 2f - 1f) * 180f;
            }
            options.add(MathHelper.wrapDegrees(cur + turn));
        }
        // em direção aos blocos que ainda restam (os mais próximos do último scan)
        List<BlockPos> near = new ArrayList<>(mineCandidates);
        near.sort(Comparator.comparingDouble(b -> {
            double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
            return dx * dx + dz * dz;
        }));
        int added = 0;
        for (BlockPos b : near) {
            double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
            if (dx * dx + dz * dz < 4.0) continue;
            options.add(yawOf(dx, dz));
            if (++added >= 3) break;
        }

        float best = cur, fallback = cur;
        double bestScore = -1.0e9, fallbackMargin = -1.0e9;
        boolean found = false;
        for (float yaw : options) {
            double[] o = arcOutcome(p, yaw, sp);
            if (o[0] > fallbackMargin) { fallbackMargin = o[0]; fallback = yaw; }
            if (o[0] < 0.2 || o[1] < 0.6 * need + 1.0) continue;   // o arco passa da borda / não sobra espaço
            float turn = MathHelper.wrapDegrees(yaw - cur);
            double score = minableAlong(mc, px, pz, yaw) + RNG.nextDouble() * 1.5 - Math.abs(turn) / 45.0;
            if (score > bestScore) { bestScore = score; best = yaw; found = true; }
        }
        if (found) {
            heading = best;
            mineSlow = Math.min(1.0, mineSlow + 0.05);
        } else if (mode >= 1) {
            // nenhum arco cabe: pega o que mais se afasta da borda e FREIA a velocidade (mantendo W + sprint)
            heading = fallback;
            mineSlow = Math.max(0.25, mineSlow * 0.6);
        }   // mode 0 sem opção válida: mantém o rumo atual (ele já está seguro)
        headingTicks = 40 + RNG.nextInt(31);   // reavalia a cada 2–3,5 s (dá tempo de completar o arco)
    }

    private static void runMine(MinecraftClient mc, ClientPlayerEntity p) {
        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
        final double px = p.getX(), pz = p.getZ();
        boostFly(p);
        if (prTop() && --surfaceTimer <= 0) {
            // segue a superfície: se a camada de baixo do bot foi quebrada, a altura do voo desce junto
            surfaceTimer = 3;
            int sy = localSolidTop(mc, p);
            if (sy != Integer.MIN_VALUE) surfaceY = sy;
        }

        // mina vazia: paira (freando) olhando para baixo, esperando regenerar
        if (mineCount == 0) {
            Vec3d v = p.getVelocity();
            p.setVelocity(v.x * 0.6, hoverVy(p), v.z * 0.6);
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            keysHeld = true;
            if (p.isSprinting()) p.setSprinting(false);
            HumanLook.step(p, p.getYaw(), MINE_PITCH, false);
            return;
        }

        // travou em algo (tentou avançar e quase não saiu do lugar): vira bastante
        if (++stuckTimer >= 20) {
            stuckTimer = 0;
            if (lastCheckPos != null && forwardTicks >= 16
                    && lastCheckPos.squaredDistanceTo(p.getPos()) < 0.25) {
                newMineHeading(mc, p, 2);
            }
            lastCheckPos = p.getPos();
            forwardTicks = 0;
        }

        // rumo: fora da área volta ao centro; chegando na borda escolhe outro (começando a virar cedo
        // o bastante para o arco caber); senão reavalia de tempos em tempos
        if (!insideArea(px, pz, 0)) {
            double cx = (FarmConfig.minX() + FarmConfig.maxX() + 1) / 2.0;
            double cz = (FarmConfig.minZ() + FarmConfig.maxZ() + 1) / 2.0;
            heading = yawOf(cx - px, cz - pz);
            headingTicks = 30;
            mineSlow = Math.max(0.25, mineSlow * 0.85);
        } else {
            // o rumo atual ainda é seguro? (arco simulado cabe na área e sobra espaço depois da curva)
            double sp = simSpeed(p);
            double[] o = arcOutcome(p, heading, sp);
            if (o[0] < 0.2 || o[1] < turnNeed(sp)) {
                newMineHeading(mc, p, 1);
            } else {
                mineSlow = Math.min(1.0, mineSlow + 0.02);
                if (--headingTicks <= 0) newMineHeading(mc, p, 0);
            }
        }

        // W + sprint SEMPRE pressionados: voo contínuo, nunca solta para virar nem para quebrar
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(true);
        keysHeld = true;
        forwardTicks++;
        if (!p.isSprinting() && !p.horizontalCollision) p.setSprinting(true);

        // câmera: segue o rumo, olhando para baixo, com um leve balanço
        float ty = heading + (float) (Math.sin(tickCounter * 0.13) * 5.0);
        float tp = MINE_PITCH + (float) (Math.sin(tickCounter * 0.07 + 1.0) * 4.0);
        HumanLook.step(p, ty, tp, true);

        // a velocidade gira junto com a câmera (sem deslizar de lado) e mantém a altura
        steerFlight(p);
    }

    // ------------------------------------------------------------------
    //  PR "CORREDOR": o bot fica no espaço entre a bedrock e a mina (ligue-o ali, de frente para a mina).
    //  A parede da mina é a "frente". Ele sobe e desce em diagonais, ora para a direita, ora para a
    //  esquerda ao longo da parede, mirando e quebrando o que está ao alcance. Tudo com aceleração
    //  suave, pausas curtas e velocidades que variam a cada pernada, para parecer um jogador.
    // ------------------------------------------------------------------
    private static int corNX, corNZ;          // direção da mina vista do corredor (um dos dois é 0)
    private static double corN0;              // posição inicial no eixo da frente (limite para trás)
    private static int corLegT, corLegLeft, corPause, corDir = 1, corVertHint, corTargetAge;
    private static double corY0, corYT, corLatSpeed;
    private static Vec3d corVel = Vec3d.ZERO, corAim = Vec3d.ZERO;
    private static BlockPos corTarget;

    private static boolean isBreakableSolid(World w, BlockPos pos, BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && !s.hasBlockEntity() && s.getHardness(w, pos) >= 0;
    }

    /**
     * Descobre para que lado está a mina: olha nas 4 direções e escolhe a que tem um bloco quebrável
     * mais perto (a bedrock não conta: é a parede de fora do corredor).
     */
    private static boolean initCorridor(MinecraftClient mc, ClientPlayerEntity p) {
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int bx = MathHelper.floor(p.getX()), by = MathHelper.floor(p.getY()), bz = MathHelper.floor(p.getZ());
        int best = Integer.MAX_VALUE, bi = -1;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int i = 0; i < 4; i++) {
            scan:
            for (int step = 1; step <= 14; step++) {
                for (int h = 0; h <= 1; h++) {
                    m.set(bx + dirs[i][0] * step, by + h, bz + dirs[i][1] * step);
                    BlockState st = mc.world.getBlockState(m);
                    if (st.isAir()) continue;
                    if (isBreakableSolid(mc.world, m, st) && step < best) { best = step; bi = i; }
                    break scan;   // o primeiro sólido da direção decide
                }
            }
        }
        if (bi < 0) return false;
        corNX = dirs[bi][0];
        corNZ = dirs[bi][1];
        corN0 = p.getX() * corNX + p.getZ() * corNZ;
        corLegLeft = 0;
        corLegT = 1;
        corPause = 0;
        corVertHint = 0;
        corTargetAge = 0;
        corDir = RNG.nextBoolean() ? 1 : -1;
        corVel = Vec3d.ZERO;
        corTarget = null;
        return true;
    }

    private static double latPos(ClientPlayerEntity p) {
        return corNX != 0 ? p.getZ() : p.getX();
    }

    /** Faixa (ao longo da parede) onde o bot pode andar: a da área marcada, com folga nas pontas. */
    private static double[] latRange() {
        double lo = (corNX != 0 ? FarmConfig.minZ() : FarmConfig.minX()) + 1.0;
        double hi = (corNX != 0 ? FarmConfig.maxZ() : FarmConfig.maxX());
        if (hi - lo < 2.0) {
            double c = (lo + hi) / 2.0;
            lo = c;
            hi = c;
        }
        return new double[]{lo, hi};
    }

    /** Alvos da parede: janela em volta do jogador e, se estiver vazia, a faixa inteira da parede. */
    private static int scanCorridor(MinecraftClient mc, ClientPlayerEntity p) {
        int bx = p.getBlockPos().getX(), by = p.getBlockPos().getY(), bz = p.getBlockPos().getZ();
        final int latR = 24, depth = 14, back = 2, vR = 10;
        int x0, x1, z0, z1;
        if (corNX != 0) {
            int a = bx - corNX * back, b = bx + corNX * depth;
            x0 = Math.min(a, b);
            x1 = Math.max(a, b);
            z0 = bz - latR;
            z1 = bz + latR;
        } else {
            int a = bz - corNZ * back, b = bz + corNZ * depth;
            z0 = Math.min(a, b);
            z1 = Math.max(a, b);
            x0 = bx - latR;
            x1 = bx + latR;
        }
        scanNear(mc, p, Math.max(x0, FarmConfig.minX()), Math.min(x1, FarmConfig.maxX()),
                Math.max(z0, FarmConfig.minZ()), Math.min(z1, FarmConfig.maxZ()),
                Math.max(mineLowY(), by - vR), Math.min(FarmConfig.maxY(), by + vR));
        if (mineCount > 0) return 20;
        // nada por perto: procura na parede inteira, em todas as alturas
        if (corNX != 0) { z0 = FarmConfig.minZ(); z1 = FarmConfig.maxZ(); }
        else { x0 = FarmConfig.minX(); x1 = FarmConfig.maxX(); }
        scanNear(mc, p, Math.max(x0, FarmConfig.minX()), Math.min(x1, FarmConfig.maxX()),
                Math.max(z0, FarmConfig.minZ()), Math.min(z1, FarmConfig.maxZ()),
                mineLowY(), FarmConfig.maxY());
        return 80;
    }

    /** Como o scanSlab, mas ordenando pela distância 3D até o olho do jogador. */
    private static void scanNear(MinecraftClient mc, ClientPlayerEntity p, int x0, int x1, int z0, int z1, int yLo, int yHi) {
        mineCandidates.clear();
        mineCount = 0;
        final double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
        final double[] keys = new double[MINE_NEAR_K];
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = yHi; y >= yLo; y--) {
                    m.set(x, y, z);
                    if (!isMinable(mc.world, m)) continue;
                    if (badTargets.contains(m)) continue;
                    if (!isExposed(mc.world, m)) continue;
                    mineCount++;
                    double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
                    offer(m, dx * dx + dy * dy + dz * dz, keys);
                }
    }

    /** Escolhe (entre os 3 mais próximos) um bloco visível e ao alcance para a câmera mirar. */
    private static void updateCorridorTarget(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        if (corTarget != null) {
            corTargetAge++;
            double r = REACH + 0.6;
            if (mc.world.getBlockState(corTarget).isAir() || badTargets.contains(corTarget) || corTargetAge > 60
                    || eye.squaredDistanceTo(Vec3d.ofCenter(corTarget)) > r * r) {
                corTarget = null;
            }
        }
        if (corTarget != null) return;
        List<BlockPos> ok = new ArrayList<>();
        for (BlockPos b : mineCandidates) {
            if (mc.world.getBlockState(b).isAir() || badTargets.contains(b)) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(b)) > REACH * REACH) continue;
            if (rayTo(mc, p, b) == null) continue;
            ok.add(b);
            if (ok.size() >= 3) break;
        }
        if (!ok.isEmpty()) {
            corTarget = ok.get(RNG.nextInt(ok.size()));
            corTargetAge = 0;
            corAim = new Vec3d((RNG.nextDouble() - 0.5) * 0.5, (RNG.nextDouble() - 0.5) * 0.5, (RNG.nextDouble() - 0.5) * 0.5);
        } else if (tickCounter % 5 == 0) {
            scanTimer = 0;   // a lista acabou (encantamentos quebraram tudo): reescaneia logo
        }
    }

    /** Distância (no eixo da frente) até o bloco mais próximo que está bem à frente do bot; MAX_VALUE = nenhum. */
    private static double faceDistAhead(ClientPlayerEntity p) {
        double best = Double.MAX_VALUE;
        double lat = latPos(p);
        for (BlockPos b : mineCandidates) {
            double blat = corNX != 0 ? b.getZ() + 0.5 : b.getX() + 0.5;
            if (Math.abs(blat - lat) > 2.5 || Math.abs(b.getY() + 0.5 - p.getEyeY()) > 4.0) continue;
            double dn = (b.getX() + 0.5 - p.getX()) * corNX + (b.getZ() + 0.5 - p.getZ()) * corNZ;
            if (dn < best) best = dn;
        }
        return best;
    }

    /** O espaço à frente (corpo inteiro) está livre para andar em direção à mina? */
    private static boolean clearAhead(MinecraftClient mc, ClientPlayerEntity p) {
        int x = MathHelper.floor(p.getX() + corNX * 0.9), z = MathHelper.floor(p.getZ() + corNZ * 0.9);
        int y = MathHelper.floor(p.getY());
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int h = 0; h <= 1; h++) {
            m.set(x, y + h, z);
            if (!mc.world.getBlockState(m).isAir()) return false;
        }
        return true;
    }

    /** Começa uma pernada: um trecho subindo ou descendo, com deriva para um dos lados. */
    private static void startCorridorLeg(ClientPlayerEntity p, double[] lr) {
        double y0 = p.getY();
        double lat = latPos(p);
        BlockPos anchor = mineCandidates.isEmpty() ? null : mineCandidates.get(0);
        double cy = anchor != null ? anchor.getY() - 0.6 : y0;   // pés um pouco abaixo do alvo (o olho fica a ~1,6)

        // faixa vertical em volta de onde há blocos
        double half = 2.5 + RNG.nextDouble() * 2.0;
        double yMin = Math.max(FarmConfig.minY() + 1.0, cy - half);
        double yMax = Math.min(FarmConfig.maxY() + 1.5, cy + half);
        if (yMax - yMin < 2.0) {
            yMin = Math.max(FarmConfig.minY() + 1.0, cy - 1.5);
            yMax = yMin + 3.0;
        }
        boolean up;
        if (corVertHint != 0) {
            up = corVertHint > 0;
            corVertHint = 0;
        } else {
            up = y0 < (yMin + yMax) / 2.0 ? RNG.nextInt(100) < 85 : RNG.nextInt(100) < 15;
        }
        double yt = up ? yMax - RNG.nextDouble() : yMin + RNG.nextDouble();
        if (Math.abs(yt - y0) < 1.5) yt = y0 + (up ? 2.5 : -2.5);
        corY0 = y0;
        corYT = yt;
        corLegT = (int) MathHelper.clamp(Math.abs(yt - y0) / (0.09 + RNG.nextDouble() * 0.06), 30, 120);

        // lado da diagonal: vai em direção ao trabalho; perto dele, às vezes inverte (direita/esquerda)
        if (anchor != null) {
            double alat = corNX != 0 ? anchor.getZ() + 0.5 : anchor.getX() + 0.5;
            if (Math.abs(alat - lat) > 5.0) corDir = alat > lat ? 1 : -1;
            else if (RNG.nextInt(100) < 30) corDir = -corDir;
        } else if (RNG.nextInt(100) < 30) {
            corDir = -corDir;
        }
        if (lat > lr[1] - 2.0) corDir = -1;
        else if (lat < lr[0] + 2.0) corDir = 1;
        corLatSpeed = 0.07 + RNG.nextDouble() * 0.15;
        corLegLeft = corLegT;
        corPause = RNG.nextInt(100) < 30 ? 3 + RNG.nextInt(14) : 0;   // às vezes uma pausinha entre as pernadas
    }

    private static void runCorridor(MinecraftClient mc, ClientPlayerEntity p) {
        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        keysHeld = true;
        if (p.isSprinting()) p.setSprinting(false);

        updateCorridorTarget(mc, p);

        // ---- câmera: mira no bloco escolhido; sem alvo, olha para a parede com um leve balanço
        float ty, tp;
        if (corTarget != null) {
            Vec3d eye = p.getEyePos();
            Vec3d to = Vec3d.ofCenter(corTarget).add(corAim);
            double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
            ty = yawOf(dx, dz);
            tp = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        } else {
            ty = yawOf(corNX, corNZ) + (float) (Math.sin(tickCounter * 0.05) * 6.0);
            tp = 6f + (float) (Math.sin(tickCounter * 0.07 + 1.0) * 4.0) - (float) (corVel.y * 40.0);
        }
        HumanLook.step(p, ty, MathHelper.clamp(tp, -80f, 80f), corTarget != null);

        // mina vazia: para devagar e espera regenerar
        if (mineCount == 0) {
            corVel = corVel.multiply(0.6);
            p.setVelocity(corVel);
            return;
        }

        // ---- movimento
        double[] lr = latRange();
        double lat = latPos(p);
        boolean moving = corVel.lengthSquared() > 0.003;
        if (moving && p.horizontalCollision && corLegLeft > 0) {          // bateu de lado: inverte a diagonal
            corDir = -corDir;
            corLegLeft = 0;
            corPause = 4 + RNG.nextInt(8);
        }
        if (moving && p.verticalCollision && corLegLeft > 0 && Math.abs(corVel.y) > 0.04) {   // teto/chão: inverte
            corVertHint = corVel.y > 0 ? -1 : 1;
            corLegLeft = 0;
            corPause = 3 + RNG.nextInt(6);
        }

        Vec3d want = Vec3d.ZERO;
        if (corPause > 0) {
            corPause--;
        } else {
            if (corLegLeft <= 0) startCorridorLeg(p, lr);
            corLegLeft--;
            double u = 1.0 - (double) corLegLeft / corLegT;                  // 0..1 ao longo da pernada
            double vy = (corYT - corY0) * 6.0 * u * (1.0 - u) / corLegT;     // sobe/desce em "S" (começa e termina devagar)
            double vlat = corDir * corLatSpeed * (0.35 + 0.65 * Math.sin(Math.PI * u));
            if (lr[1] - lr[0] < 0.01 || (vlat > 0 && lat >= lr[1]) || (vlat < 0 && lat <= lr[0])) vlat = 0;
            if (p.getY() >= FarmConfig.maxY() + 1.5 && vy > 0) vy = 0;
            if (p.getY() <= FarmConfig.minY() + 1.0 && vy < 0) vy = 0;

            // distância da parede: se ela recuou (blocos quebrados), chega mais perto; se está colado, afasta
            double vn = 0;
            double dn = faceDistAhead(p);
            double pn = p.getX() * corNX + p.getZ() * corNZ;
            if (dn != Double.MAX_VALUE) {
                if (dn > 3.2 && clearAhead(mc, p)) vn = 0.14;
                else if (dn < 1.2 && pn > corN0 - 0.8) vn = -0.08;
            }
            want = corNX != 0 ? new Vec3d(corNX * vn, vy, vlat) : new Vec3d(vlat, vy, corNZ * vn);
        }
        corVel = corVel.add(want.subtract(corVel).multiply(0.3));   // aceleração suave
        if (corVel.lengthSquared() < 1.0e-5) corVel = Vec3d.ZERO;
        p.setVelocity(corVel);
    }

    /** Quebra o bloco que está sob a mira (como quem segura o botão de ataque), todo tick. */
    private static void mineAct(MinecraftClient mc, ClientPlayerEntity p) {
        HitResult cr = p.raycast(BREAK_RANGE, 1.0f, false);
        if (cr.getType() != HitResult.Type.BLOCK) return;
        BlockHitResult cb = (BlockHitResult) cr;
        BlockPos pos = cb.getBlockPos();
        if (badTargets.contains(pos)) return;
        BlockState st = mc.world.getBlockState(pos);
        if (!inMineArea(pos.getX(), pos.getY(), pos.getZ()) || pos.getY() < slabBottomY()) return;
        if (!isMinable(mc.world, pos) || !playerAllows(mc, p, pos, st)) return;

        selectBestTool(p, st);
        mc.interactionManager.updateBlockBreakingProgress(pos, cb.getSide());
        p.swingHand(Hand.MAIN_HAND);

        if (mc.world.getBlockState(pos).isAir()) {
            // se o servidor devolve o bloco (protegido), não fica quebrando o mesmo para sempre
            int n = breakCount.merge(pos, 1, Integer::sum);
            if (n >= 4) badTargets.add(pos);
        } else {
            // bloco lento: o voo NÃO para (igual ao farm); só desiste dele se ficar preso na mira por muito tempo
            if (pos.equals(slowPos)) {
                if (++slowTicks > 40) { badTargets.add(pos); slowTicks = 0; }
            } else {
                slowPos = pos;
                slowTicks = 0;
            }
        }
    }

    private static void selectBestTool(ClientPlayerEntity p, BlockState state) {
        int bestSlot = p.getInventory().selectedSlot;
        float bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isDamageable() && st.getMaxDamage() - st.getDamage() <= 2) continue;
            float score = st.isEmpty() ? 1f : st.getMiningSpeedMultiplier(state);
            if (!st.isEmpty() && st.isSuitableFor(state)) score += 1000f;
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        p.getInventory().selectedSlot = bestSlot;
    }

    /** Seleciona, na hotbar, o machado ou enxada mais rápido para o bloco. false = não tem nenhum. */
    private static boolean selectAxeOrHoe(ClientPlayerEntity p, BlockState state) {
        int best = -1;
        float bestSpeed = -1f;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isEmpty()) continue;
            if (!(st.isIn(ItemTags.AXES) || st.isIn(ItemTags.HOES))) continue;
            // mesma regra do selectBestTool: não usar ferramenta quase quebrando
            if (st.isDamageable() && st.getMaxDamage() - st.getDamage() <= 2) continue;
            float speed = st.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; best = i; }
        }
        if (best < 0) return false;
        p.getInventory().selectedSlot = best;
        return true;
    }

    // ==================================================================
    //     MOVIMENTO POR VELOCIDADE (voo / mineração) + CÂMERA
    // ==================================================================
    private static void move(MinecraftClient mc, ClientPlayerEntity p, boolean hold) {
        boolean farm = FarmConfig.task == FarmConfig.Task.FARM;
        boolean fly = wantsFly(mc, p);

        boolean needNew = target == null
                || ++targetAge > 300
                || reached(p, fly)
                || (targetBlock != null && !targetStillValid(mc));
        if (needNew) {
            if (target != null && targetBlock != null && targetAge > 300) badTargets.add(targetBlock);
            pickTarget(p, fly);
        }

        // detector de travamento
        if (++stuckTimer >= 20) {
            stuckTimer = 0;
            if (lastCheckPos != null && target != null && !hold && idleTicks <= 0
                    && lastCheckPos.squaredDistanceTo(p.getPos()) < 0.09) {
                if (targetBlock != null) badTargets.add(targetBlock);
                target = null;
            }
            lastCheckPos = p.getPos();
        }

        // velocidade desejada: gira primeiro, corre depois (reduz se estiver olhando para outro lado)
        Vec3d desired = Vec3d.ZERO;
        if (target != null) {
            Vec3d to = target.subtract(p.getPos());
            Vec3d flat = new Vec3d(to.x, 0, to.z);
            double dist = fly ? to.length() : flat.length();
            if (idleTicks > 0) {
                idleTicks--;
            } else if (!hold && dist > 0.05) {
                Vec3d dir = (fly ? to : flat).normalize();
                float wantYaw = yawOf(to.x, to.z);
                float dyaw = Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw()));
                double turn = MathHelper.clamp(1.0 - dyaw / 100.0, 0.15, 1.0);
                double slow = MathHelper.clamp(dist / 2.0, 0.3, 1.0);
                double wobble = 1.0 + 0.06 * Math.sin(tickCounter * 0.09);
                desired = dir.multiply((fly ? FLY_SPEED : WALK_SPEED) * turn * slow * wobble);
            }
        }

        cmdVel = cmdVel.add(desired.subtract(cmdVel).multiply(0.18));
        if (cmdVel.lengthSquared() < 1.0E-5) cmdVel = Vec3d.ZERO;

        if (fly) {
            if (!p.getAbilities().flying) {
                p.getAbilities().flying = true;
                p.sendAbilitiesUpdate();
            }
            Vec3d v = cmdVel;
            if (p.horizontalCollision && farm && target != null) v = v.add(0, 0.25, 0);
            p.setVelocity(v);
        } else {
            if (p.getAbilities().flying) {
                p.getAbilities().flying = false;
                p.sendAbilitiesUpdate();
            }
            p.setVelocity(cmdVel.x, p.getVelocity().y, cmdVel.z);
            double hSq = cmdVel.x * cmdVel.x + cmdVel.z * cmdVel.z;
            if (p.horizontalCollision && p.isOnGround() && hSq > 0.0016) {
                p.setVelocity(cmdVel.x, 0.42, cmdVel.z); // pulo
            }
        }
    }

    /** Voar só se o jogador escolheu voar E pode voar (criativo). */
    private static boolean wantsFly(MinecraftClient mc, ClientPlayerEntity p) {
        // mineração (EC e PR) sempre voa, independente do modo de movimento do farm
        if (FarmConfig.task == FarmConfig.Task.MINE) return p.getAbilities().allowFlying;
        if (FarmConfig.mode != FarmConfig.Mode.FLY) return false;
        if (p.getAbilities().allowFlying) return true;
        if (!warnedFly) {
            msg(mc, "§eVocê não pode voar (precisa de modo criativo). Usando modo correndo.");
            warnedFly = true;
        }
        return false;
    }

    /** Solta as teclas que o bot estava segurando (para não ficar andando sozinho). */
    private static void releaseKeys(MinecraftClient mc) {
        if (!keysHeld) return;
        keysHeld = false;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    // ==================================================================
    //   CORRIDA HUMANIZADA (farm correndo): rumo aleatório + mira varrendo
    // ==================================================================
    // O bot NÃO persegue plantas (isso faz o personagem orbitar a planta, já que a câmera gira
    // devagar e a corrida é rápida). Ele escolhe um RUMO e corre reto nele por 3–5 s; depois faz
    // uma virada leve (ou para o lado) e segue em outro rumo, sempre dentro da área. A mira varre o
    // chão à frente e quebra/replanta o que estiver sob ela, a cada tick, sem delay.

    private static final double EDGE_MARGIN = 0.5;

    private static boolean insideArea(double x, double z, double margin) {
        double sx = FarmConfig.maxX() + 1 - FarmConfig.minX();
        double sz = FarmConfig.maxZ() + 1 - FarmConfig.minZ();
        double mx = Math.min(margin, sx / 3.0), mz = Math.min(margin, sz / 3.0);
        return x >= FarmConfig.minX() + mx && x <= FarmConfig.maxX() + 1 - mx
                && z >= FarmConfig.minZ() + mz && z <= FarmConfig.maxZ() + 1 - mz;
    }

    /** Quanto dá para correr neste rumo antes de sair da área (0 se já está fora). */
    private static double freeDist(double x, double z, float yaw, double margin) {
        double sx = FarmConfig.maxX() + 1 - FarmConfig.minX();
        double sz = FarmConfig.maxZ() + 1 - FarmConfig.minZ();
        double mx = Math.min(margin, sx / 3.0), mz = Math.min(margin, sz / 3.0);
        double loX = FarmConfig.minX() + mx, hiX = FarmConfig.maxX() + 1 - mx;
        double loZ = FarmConfig.minZ() + mz, hiZ = FarmConfig.maxZ() + 1 - mz;
        if (x < loX || x > hiX || z < loZ || z > hiZ) return 0;
        double fx = dirX(yaw), fz = dirZ(yaw);
        double t = 1.0e9;
        if (fx > 1e-6) t = Math.min(t, (hiX - x) / fx);
        else if (fx < -1e-6) t = Math.min(t, (loX - x) / fx);
        if (fz > 1e-6) t = Math.min(t, (hiZ - z) / fz);
        else if (fz < -1e-6) t = Math.min(t, (loZ - z) / fz);
        return t;
    }

    private static double areaMinSide() {
        return Math.min(FarmConfig.maxX() + 1 - FarmConfig.minX(), FarmConfig.maxZ() + 1 - FarmConfig.minZ());
    }

    /** Distância da borda em que o bot decide mudar de rumo. */
    private static double wallTrigger() { return MathHelper.clamp(0.25 * areaMinSide(), 1.5, 4.0); }

    /** Distância livre desejada ao escolher um rumo novo (atravessar a área, não ficar rente à borda). */
    private static double wantRun() { return Math.min(12.0, 0.55 * areaMinSide()); }

    private static double dirX(float yaw) { return -Math.sin(Math.toRadians(yaw)); }
    private static double dirZ(float yaw) { return Math.cos(Math.toRadians(yaw)); }

    /** Quantas plantas/replantes existem num corredor à frente deste rumo (prefere rumos com plantas). */
    private static int cropsAlong(double px, double pz, float yaw) {
        double fx = dirX(yaw), fz = dirZ(yaw);
        int n = 0;
        for (BlockPos b : ready) n += corridor(b, px, pz, fx, fz);
        for (BlockPos b : replantQueue.keySet()) n += corridor(b, px, pz, fx, fz);
        return Math.min(n, 15);
    }

    private static int corridor(BlockPos b, double px, double pz, double fx, double fz) {
        double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
        double f = dx * fx + dz * fz;                 // distância à frente
        if (f < 1.0 || f > 9.0) return 0;
        double l = Math.abs(-dx * fz + dz * fx);      // desvio lateral
        return l <= 2.0 ? 1 : 0;
    }

    /**
     * Escolhe um novo rumo.
     * mode 0 = troca normal: virada leve (15–55°) ou para o lado (60–100°), alternando o lado para não dar círculo
     * mode 1 = chegando na borda: escolhe um rumo que atravessa a área (diagonal longa)
     * mode 2 = travou em algo: vira bastante (90–180°)
     */
    private static void newHeading(ClientPlayerEntity p, int mode) {
        double px = p.getX(), pz = p.getZ();
        double trig = wallTrigger(), want = wantRun();
        float cur = heading;
        float best = cur;
        double bestScore = -1.0e9;
        int tries = mode == 0 ? 8 : 18;

        for (int i = 0; i < tries; i++) {
            float turn;
            if (mode == 0) {
                float mag = RNG.nextInt(100) < 70 ? 15f + RNG.nextFloat() * 40f : 60f + RNG.nextFloat() * 40f;
                int sign = RNG.nextInt(100) < 65 ? -lastTurnSign : lastTurnSign;
                turn = mag * sign;
            } else if (mode == 2) {
                turn = (90f + RNG.nextFloat() * 90f) * (RNG.nextBoolean() ? 1 : -1);
            } else {
                turn = (RNG.nextFloat() * 2f - 1f) * 180f;
            }
            float yaw = MathHelper.wrapDegrees(cur + turn);
            double free = freeDist(px, pz, yaw, EDGE_MARGIN);

            double score = cropsAlong(px, pz, yaw) * 0.4 + RNG.nextDouble() * 2.5
                    - Math.abs(turn) / (mode == 0 ? 60.0 : 50.0);
            if (free < want) score -= (want - free) * 2.5;
            if (free < trig + 1.0) score -= 1000;
            if (score > bestScore) { bestScore = score; best = yaw; }
        }

        if (bestScore < -500) { // nenhum rumo serve: vai em direção ao centro da área
            double cx = (FarmConfig.minX() + FarmConfig.maxX() + 1) / 2.0;
            double cz = (FarmConfig.minZ() + FarmConfig.maxZ() + 1) / 2.0;
            best = yawOf(cx - px, cz - pz);
        }
        lastTurnSign = MathHelper.wrapDegrees(best - cur) >= 0 ? 1 : -1;
        heading = best;
        headingTicks = 60 + RNG.nextInt(41); // 3 a 5 segundos
    }

    private static void runFarm(MinecraftClient mc, ClientPlayerEntity p, boolean holdForBreak) {
        // se estava voando, volta para a física normal
        if (p.getAbilities().flying) {
            p.getAbilities().flying = false;
            p.sendAbilitiesUpdate();
        }
        double px = p.getX(), pz = p.getZ();

        // travou em algo (tentou avançar quase o tempo todo e quase não saiu do lugar): vira bastante
        if (++stuckTimer >= 20) {
            stuckTimer = 0;
            if (lastCheckPos != null && forwardTicks >= 16
                    && lastCheckPos.squaredDistanceTo(p.getPos()) < 0.25) {
                newHeading(p, 2);
            }
            lastCheckPos = p.getPos();
            forwardTicks = 0;
        }

        // rumo: fora da área volta ao centro; chegando na borda atravessa; senão troca a cada 3–5 s
        if (!insideArea(px, pz, 0)) {
            double cx = (FarmConfig.minX() + FarmConfig.maxX() + 1) / 2.0;
            double cz = (FarmConfig.minZ() + FarmConfig.maxZ() + 1) / 2.0;
            heading = yawOf(cx - px, cz - pz);
            headingTicks = 30;
        } else if (freeDist(px, pz, heading, EDGE_MARGIN) < wallTrigger()) {
            newHeading(p, 1);
        } else if (--headingTicks <= 0) {
            newHeading(p, 0);
        }

        // anda / corre: caminha em viradas médias e gira parado só nas muito grandes
        float yawErr = Math.abs(MathHelper.wrapDegrees(heading - p.getYaw()));
        boolean forward = !holdForBreak && yawErr < 90f;
        if (forward && freeDist(px, pz, p.getYaw(), 0.2) < 0.8 && insideArea(px, pz, 0.2)) {
            forward = false;   // a câmera ainda aponta para a borda: termina de virar antes de andar
        }
        boolean sprint = forward && yawErr < 50f;

        if (sprint && p.getHungerManager().getFoodLevel() <= 6) {
            if (!warnedHunger) {
                msg(mc, "§eFome baixa (6 ou menos): o personagem não consegue correr. Coma algo.");
                warnedHunger = true;
            }
            sprint = false;
        }

        mc.options.forwardKey.setPressed(forward);
        mc.options.jumpKey.setPressed(forward && p.horizontalCollision && p.isOnGround()); // sobe degraus
        keysHeld = true;
        if (forward) forwardTicks++;

        if (sprint) {
            if (!p.isSprinting() && !p.horizontalCollision) p.setSprinting(true);
        } else if (p.isSprinting()) {
            p.setSprinting(false);
        }

        // câmera: segue o rumo e varre o chão à frente (vai-e-vem lateral e de altura),
        // passando a mira sobre as plantas; quem quebra é o farmAct, em todo tick
        if (holdForBreak) { HumanLook.coast(p); return; }   // quebrando algo demorado: mira parada
        float ty = heading + (float) (Math.sin(tickCounter * 0.13) * 12.0);
        float tp = 36f + (float) (Math.sin(tickCounter * 0.07 + 1.0) * 8.0);
        HumanLook.step(p, ty, tp, true);
    }

    /** Escolhe o próximo alvo à FRENTE: perto, mas penalizando quem exige uma virada grande. */
    private static BlockPos pickAhead(ClientPlayerEntity p, List<BlockPos> cands) {
        double px = p.getX(), pz = p.getZ();
        float yaw = p.getYaw();
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos b : cands) {
            double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double turn = dist < 1.0 ? 0 : Math.abs(MathHelper.wrapDegrees(yawOf(dx, dz) - yaw));
            double cost = dist + turn / 22.0 + RNG.nextDouble() * 1.2; // 180° custa ~8 blocos
            if (cost < bestCost) { bestCost = cost; best = b; }
        }
        return best;
    }

    /** Escolha usada na mineração: aleatória, favorecendo os mais próximos. */
    private static BlockPos pickNearRandom(ClientPlayerEntity p, List<BlockPos> candidates) {
        final double px = p.getX(), pz = p.getZ();
        candidates.sort(Comparator.comparingDouble(b -> {
            double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
            return dx * dx + dz * dz;
        }));
        int k = Math.min(candidates.size(), 8);
        int idx = (int) (Math.pow(RNG.nextDouble(), 1.6) * k);
        return candidates.get(Math.min(idx, candidates.size() - 1));
    }

    private static boolean targetStillValid(MinecraftClient mc) {
        if (FarmConfig.task == FarmConfig.Task.MINE) {
            return !mc.world.getBlockState(targetBlock).isAir();
        }
        return ready.contains(targetBlock) || replantQueue.containsKey(targetBlock);
    }

    private static boolean reached(ClientPlayerEntity p, boolean fly) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        if (fly) {
            double dy = target.y - p.getY();
            return dx * dx + dy * dy + dz * dz < 0.64;
        }
        return dx * dx + dz * dz < 0.64;
    }

    /** Próximo alvo: aleatório, mas favorecendo os mais próximos (corrida contínua, sem zigue-zague). */
    private static void pickTarget(ClientPlayerEntity p, boolean fly) {
        targetAge = 0;
        targetBlock = null;

        List<BlockPos> candidates = new ArrayList<>();
        boolean mine = FarmConfig.task == FarmConfig.Task.MINE;
        if (mine) {
            candidates.addAll(mineCandidates);
        } else {
            candidates.addAll(ready);
            candidates.addAll(replantQueue.keySet());
        }
        candidates.removeIf(badTargets::contains);

        if (!candidates.isEmpty()) {
            BlockPos c = mine ? pickNearRandom(p, candidates) : pickAhead(p, candidates);
            targetBlock = c;
            aimOffset = new Vec3d((RNG.nextDouble() - 0.5) * 0.4,
                    (RNG.nextDouble() - 0.5) * 0.2, (RNG.nextDouble() - 0.5) * 0.4);
            double y = (fly && !mine) ? c.getY() + 1.0 : c.getY();
            target = new Vec3d(c.getX() + 0.5, y, c.getZ() + 0.5);
            return;
        }

        // nada para fazer: passeia pela área e às vezes para um pouco
        double x = FarmConfig.minX() + RNG.nextDouble() * (FarmConfig.maxX() - FarmConfig.minX() + 1);
        double z = FarmConfig.minZ() + RNG.nextDouble() * (FarmConfig.maxZ() - FarmConfig.minZ() + 1);
        double y = fly ? FarmConfig.maxY() + 1.0 + RNG.nextDouble() : FarmConfig.minY();
        target = new Vec3d(x, y, z);
        idleTicks = RNG.nextInt(100) < 30 ? 15 + RNG.nextInt(35) : 0;
    }

    // ------------------------------------------------------------------
    // Câmera suave
    private static float yawOf(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static void updateLook(ClientPlayerEntity p) {
        float ty, tp;
        boolean aiming = aimPoint != null;
        if (aiming) {
            Vec3d e = p.getEyePos();
            double dx = aimPoint.x - e.x, dy = aimPoint.y - e.y, dz = aimPoint.z - e.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            ty = yawOf(dx, dz);
            tp = (float) -Math.toDegrees(Math.atan2(dy, h));
        } else if (target != null && idleTicks <= 0) {
            // longe da planta: olha na direção da corrida, levemente para baixo
            ty = yawOf(target.x - p.getX(), target.z - p.getZ());
            tp = 22f;
        } else {
            HumanLook.coast(p);
            return;
        }
        HumanLook.step(p, ty, tp, aiming);
    }

    static void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[FarmBot] " + s), true);
    }
}
