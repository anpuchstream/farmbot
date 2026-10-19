package com.farmbot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
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
    /** Voo da mineração (blocos/tick). 0.5 = ~10 blocos/s, perto do voo padrão do criativo. */
    private static final double FLY_SPEED_MINE = 0.5;
    /** EC: aceita blocos até 2 abaixo do Y mínimo da área (quem marca os cantos em pé/voando fica acima do chão). */
    private static final int MINE_V_TOL = 2;

    // --- farm ---
    private static final List<BlockPos> ready = new ArrayList<>();
    private static final Map<BlockPos, Crop> replantQueue = new LinkedHashMap<>();

    // --- mineração ---
    private static final List<BlockPos> mineCandidates = new ArrayList<>();
    private static int mineCount = 0;
    private static BlockPos currentMine;
    private static Vec3d mineAnchor;        // centro do último bloco quebrado (a mira continua por perto)
    private static int lastForcedScan;

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
        ready.clear();
        replantQueue.clear();
        mineCandidates.clear();
        badTargets.clear();
        mineCount = 0;
        currentMine = null;
        slabTop = FarmConfig.maxY();
        mineEmptyTicks = 0;
        mineAnchor = null;
        lastForcedScan = 0;
        target = null; targetBlock = null; lastCheckPos = null; aimPoint = null;
        cmdVel = Vec3d.ZERO;
        idleTicks = 0;
        scanTimer = 0; cooldown = 0; statusTimer = 0; warnedFly = false; warnedHunger = false;
        forwardTicks = 0; breakingSlow = false; headingTicks = 0; rightClickCooldown = 0;
        heading = mc.player.getYaw();
        HumanLook.reset();
        armGuard(mc);
        FarmConfig.enabled = true;
        msg(mc, mining ? "§aMineração " + FarmConfig.mineZone + " LIGADA" : "§aFarm LIGADO");
    }

    public static void stop(MinecraftClient mc, String message) {
        FarmConfig.enabled = false;
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

    // --- alerta sonoro ---
    private static final int ALERT_REPEATS = 3;     // quantos toques de sino
    private static final int ALERT_INTERVAL = 12;   // ticks entre os toques (~0,6 s)
    private static int alertLeft, alertDelay;

    /** Para o bot por um motivo da proteção e dispara o som de alerta. */
    private static void guardStop(MinecraftClient mc, String message) {
        stop(mc, message);
        alert();
    }

    private static void alert() {
        if (!FarmConfig.alertSound) return;
        alertLeft = ALERT_REPEATS;
        alertDelay = 0;
    }

    /** Toca um toque do alerta (botão de teste do menu). */
    public static void testAlert(MinecraftClient mc) {
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_BELL_USE, 1.0f, 1.0f));
    }

    /** Toca os toques pendentes do alerta. Roda todo tick, mesmo com o bot desligado. */
    private static void tickAlert(MinecraftClient mc) {
        if (alertLeft <= 0) return;
        if (alertDelay > 0) { alertDelay--; return; }
        // categoria MASTER, sem posição: toca no volume cheio, mesmo em tela de menu/carregamento
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_BELL_USE, 1.0f, 1.0f));
        alertLeft--;
        alertDelay = ALERT_INTERVAL;
    }

    /** Guarda a "foto" do estado no momento em que o bot é ligado. */
    private static void armGuard(MinecraftClient mc) {
        guardPlayer = mc.player;
        guardWorld = mc.world;
        guardPos = mc.player.getPos();
        guardWasInside = nearArea(guardPos);
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
        if (tickCounter % 600 == 0) badTargets.clear();
        boolean mining = FarmConfig.task == FarmConfig.Task.MINE;
        aimPoint = null;

        if (mining && !p.getAbilities().allowFlying) {
            stop(mc, "§cMineração parada: você perdeu a permissão de voar.");
            return;
        }

        if (--scanTimer <= 0) {
            if (mining) {
                scanTimer = scanMine(mc, p);
                if (mineCount == 0 && currentMine == null) {
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
            boolean hold = tryMine(mc, p);   // true só ao quebrar bloco lento; senão voa direto
            move(mc, p, hold);
            updateLook(p);
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
            if (mineCount == 0 && currentMine == null) {
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
        if (FarmConfig.oresOnly() && !isOre(s)) return false;
        for (Direction d : Direction.values()) {
            if (!w.getFluidState(pos.offset(d)).isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Fatias de mineração. EC = a área inteira (zona plana).
    //  PR = uma fatia de SLAB_DEPTH camadas que desce do topo ao fundo (zona funda).
    // ------------------------------------------------------------------
    private static final int SLAB_DEPTH = 2;          // camadas por fatia no modo PR (alcance real do braço ~3 blocos)
    private static final int MINE_WINDOW = 40;        // raio (blocos) da varredura local em volta do jogador
    private static final int MINE_NEAR_K = 24;        // quantos alvos próximos guardar
    private static final int MINE_REGEN_WAIT = 6000;  // ticks (~5 min) esperando a mina regenerar antes de parar
    private static int slabTop;                        // PR: Y mais alto da fatia atual
    private static int mineEmptyTicks;

    private static boolean isPR() {
        return FarmConfig.mineZone == FarmConfig.MineZone.PR;
    }

    private static long columns() {
        return (long) (FarmConfig.maxX() - FarmConfig.minX() + 1) * (FarmConfig.maxZ() - FarmConfig.minZ() + 1);
    }

    private static int slabTopY() {
        return isPR() ? Math.min(slabTop, FarmConfig.maxY()) : FarmConfig.maxY();
    }

    private static int slabBottomY() {
        return isPR() ? Math.max(slabTopY() - SLAB_DEPTH + 1, FarmConfig.minY()) : mineLowY();
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

    /** Texto da camada atual (modo PR) para o menu e o status. */
    public static String mineLayerInfo() {
        if (FarmConfig.task != FarmConfig.Task.MINE || !isPR()) return "";
        return "Camada Y " + slabTopY() + " a " + slabBottomY();
    }

    /**
     * Procura alvos na fatia atual: primeiro numa janela em volta do jogador (barato) e, se estiver
     * vazia, na fatia inteira. No modo PR, fatia limpa = desce para a próxima.
     *
     * @return em quantos ticks escanear de novo
     */
    private static int scanMine(MinecraftClient mc, ClientPlayerEntity p) {
        int px = p.getBlockPos().getX(), pz = p.getBlockPos().getZ();
        boolean global = false;
        for (int guard = 0; guard < 12; guard++) {
            int yLo = slabBottomY(), yHi = slabTopY();
            scanSlab(mc, p, Math.max(FarmConfig.minX(), px - MINE_WINDOW), Math.min(FarmConfig.maxX(), px + MINE_WINDOW),
                    Math.max(FarmConfig.minZ(), pz - MINE_WINDOW), Math.min(FarmConfig.maxZ(), pz + MINE_WINDOW), yLo, yHi);
            if (mineCount == 0) {
                scanSlab(mc, p, FarmConfig.minX(), FarmConfig.maxX(), FarmConfig.minZ(), FarmConfig.maxZ(), yLo, yHi);
                global = true;
            }
            if (mineCount > 0 || !isPR()) break;
            if (yLo <= FarmConfig.minY()) {          // chegou ao fundo: recomeça do topo (a mina pode ter regenerado)
                slabTop = FarmConfig.maxY();
                break;
            }
            slabTop = yLo - 1;                        // fatia limpa: desce
        }
        if (!global) return 40;
        long cells = columns() * Math.max(1, slabTopY() - slabBottomY() + 1);
        return (int) Math.max(40, Math.min(400, cells / 5000));
    }

    /** Conta os blocos quebráveis e expostos da região e guarda os MINE_NEAR_K mais bem posicionados. */
    private static void scanSlab(MinecraftClient mc, ClientPlayerEntity p, int x0, int x1, int z0, int z1, int yLo, int yHi) {
        mineCandidates.clear();
        mineCount = 0;
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
                    double dx = x + 0.5 - px, dz = z + 0.5 - pz, dy = yHi - y;
                    double key = dx * dx + dz * dz + 9.0 * dy * dy;   // perto primeiro; camada de cima primeiro
                    int n = mineCandidates.size();
                    if (n == MINE_NEAR_K) {
                        if (key >= keys[n - 1]) continue;
                        mineCandidates.remove(n - 1);
                        n--;
                    }
                    int i = n;
                    while (i > 0 && keys[i - 1] > key) {
                        keys[i] = keys[i - 1];
                        i--;
                    }
                    keys[i] = key;
                    mineCandidates.add(i, m.toImmutable());
                }
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

    /**
     * Mineração no estilo do farm: escolhe o próximo bloco para mirar (perto de onde a mira acabou de
     * quebrar, para a câmera varrer o chão sem pular) e, TODO tick, quebra o que estiver sob a mira,
     * como quem segura o botão de ataque. O movimento nunca para por causa da mira.
     *
     * @return true só se está quebrando um bloco lento (precisa segurar a posição até quebrar)
     */
    private static boolean tryMine(MinecraftClient mc, ClientPlayerEntity p) {
        final int yFloor = slabBottomY();   // PR: de cima para baixo, nunca abaixo da fatia atual
        boolean hold = false;

        // 1) quebra o que está sob a mira
        HitResult cr = p.raycast(BREAK_RANGE, 1.0f, false);
        if (cr.getType() == HitResult.Type.BLOCK) {
            BlockHitResult cb = (BlockHitResult) cr;
            BlockPos pos = cb.getBlockPos();
            BlockState st = mc.world.getBlockState(pos);
            if (inMineArea(pos.getX(), pos.getY(), pos.getZ()) && pos.getY() >= yFloor
                    && isMinable(mc.world, pos) && playerAllows(mc, p, pos, st)) {
                selectBestTool(p, st);
                mc.interactionManager.updateBlockBreakingProgress(pos, cb.getSide());
                p.swingHand(Hand.MAIN_HAND);
                if (mc.world.getBlockState(pos).isAir()) {
                    mineAnchor = Vec3d.ofCenter(pos);
                    if (pos.equals(currentMine)) currentMine = null;
                } else {
                    hold = true;   // bloco lento: fica parado até quebrar (senão o progresso zera)
                }
            }
        }

        // 2) próximo alvo de mira
        BlockHitResult hit = null;
        if (currentMine != null) {
            BlockState cs = mc.world.getBlockState(currentMine);
            boolean ok = inMineArea(currentMine.getX(), currentMine.getY(), currentMine.getZ())
                    && currentMine.getY() >= yFloor
                    && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(currentMine)) <= REACH * REACH
                    && isMinable(mc.world, currentMine)
                    && playerAllows(mc, p, currentMine, cs);
            if (ok) hit = rayTo(mc, p, currentMine);
            if (hit == null) currentMine = null;
        }

        if (currentMine == null) {
            final Vec3d eye = p.getEyePos();
            int r = (int) Math.ceil(REACH);
            BlockPos base = BlockPos.ofFloored(eye);
            List<BlockPos> cands = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos pos = base.add(dx, dy, dz);
                        if (!inMineArea(pos.getX(), pos.getY(), pos.getZ())) continue;
                        if (pos.getY() < yFloor) continue;
                        if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH * REACH) continue;
                        if (!isMinable(mc.world, pos)) continue;
                        if (!isExposed(mc.world, pos)) continue;
                        if (!playerAllows(mc, p, pos, mc.world.getBlockState(pos))) continue;
                        cands.add(pos);
                    }
            if (!cands.isEmpty()) {
                // a mira "pinta" o chão: prefere blocos perto do último quebrado (câmera quase não se mexe)
                final Vec3d ref = (mineAnchor != null && mineAnchor.squaredDistanceTo(eye) < 36.0) ? mineAnchor : eye;
                final double yRef = slabTopY() + 0.5;
                cands.sort(Comparator.comparingDouble(b -> {
                    Vec3d c = Vec3d.ofCenter(b);
                    double s = Math.sqrt(ref.squaredDistanceTo(c)) + 0.15 * Math.sqrt(eye.squaredDistanceTo(c));
                    s += 0.5 * Math.max(0.0, yRef - (b.getY() + 0.5));      // prefere as camadas de cima
                    if (isOre(mc.world.getBlockState(b))) s -= 1.5;          // minérios primeiro
                    return s;
                }));
                int tries = 0;
                for (BlockPos c : cands) {
                    if (++tries > 40) break;
                    BlockHitResult h = rayTo(mc, p, c);
                    if (h != null) { currentMine = c; hit = h; break; }
                }
            }
        }

        if (currentMine != null && hit != null) aimPoint = hit.getPos();
        return hold;
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
            if (!farm && target != null && targetBlock != null && reached(p, fly)
                    && !mc.world.getBlockState(targetBlock).isAir()) {
                badTargets.add(targetBlock);   // chegou e o bloco continua aí (não dá para quebrar dali): tenta outro
            }
            pickTarget(mc, p, fly);
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
                // farm: reduz se a câmera não está virada para onde corre. Mineração voando: a câmera
                // olha para os blocos e o voo não depende dela, então não reduz.
                double turn = farm ? MathHelper.clamp(1.0 - dyaw / 100.0, 0.15, 1.0) : 1.0;
                double slow = MathHelper.clamp(dist / 2.0, 0.3, 1.0);
                double wobble = 1.0 + 0.06 * Math.sin(tickCounter * 0.09);
                double speed = fly ? (farm ? FLY_SPEED : FLY_SPEED_MINE) : WALK_SPEED;
                desired = dir.multiply(speed * turn * slow * wobble);
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

    /** Mineração: vai até o bloco restante mais próximo, de preferência à frente e nas camadas de cima. */
    private static BlockPos pickMineTarget(ClientPlayerEntity p, List<BlockPos> cands) {
        double px = p.getX(), pz = p.getZ();
        int top = slabTopY();
        boolean moving = cmdVel.x * cmdVel.x + cmdVel.z * cmdVel.z > 0.0025;
        float heading = moving ? yawOf(cmdVel.x, cmdVel.z) : 0f;
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos b : cands) {
            double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
            double h2 = dx * dx + dz * dz;
            if (h2 < 4.0) continue;   // já está colado: a mira cuida (ou não dá para quebrar dali)
            double cost = Math.sqrt(h2) + 3.0 * (top - b.getY());
            if (moving) cost += Math.abs(MathHelper.wrapDegrees(yawOf(dx, dz) - heading)) / 30.0;   // 180° custa ~6 blocos
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
    private static void pickTarget(MinecraftClient mc, ClientPlayerEntity p, boolean fly) {
        targetAge = 0;
        targetBlock = null;

        List<BlockPos> candidates = new ArrayList<>();
        boolean mine = FarmConfig.task == FarmConfig.Task.MINE;
        if (mine) {
            candidates.addAll(mineCandidates);
            candidates.removeIf(b -> mc.world.getBlockState(b).isAir());   // já quebrados
        } else {
            candidates.addAll(ready);
            candidates.addAll(replantQueue.keySet());
        }
        candidates.removeIf(badTargets::contains);

        if (mine && candidates.isEmpty() && mineCount > 0) {
            // a lista acabou (os alvos foram quebrados voando): pede um novo scan e paira até chegar
            if (tickCounter - lastForcedScan >= 10) {
                lastForcedScan = tickCounter;
                scanTimer = 0;
            }
            target = p.getPos();
            return;
        }

        if (!candidates.isEmpty()) {
            BlockPos c = mine ? pickMineTarget(p, candidates) : pickAhead(p, candidates);
            if (c != null) {
                targetBlock = c;
                aimOffset = new Vec3d((RNG.nextDouble() - 0.5) * 0.4,
                        (RNG.nextDouble() - 0.5) * 0.2, (RNG.nextDouble() - 0.5) * 0.4);
                double y;
                if (mine) y = fly ? (isPR() ? slabTopY() : c.getY()) + 1.3 : c.getY();   // pairar logo acima do chão/fatia
                else y = fly ? c.getY() + 1.0 : c.getY();
                target = new Vec3d(c.getX() + 0.5, y, c.getZ() + 0.5);
                return;
            }
            if (mine) {
                // só restam blocos colados no jogador que não dá para quebrar daqui (ex.: o chão sob o pé):
                // dá um passo de lado para quebrá-los de outro ponto
                double ang = RNG.nextDouble() * Math.PI * 2.0;
                double tx = MathHelper.clamp(p.getX() + Math.cos(ang) * 4.0, FarmConfig.minX() + 0.5, FarmConfig.maxX() + 0.5);
                double tz = MathHelper.clamp(p.getZ() + Math.sin(ang) * 4.0, FarmConfig.minZ() + 0.5, FarmConfig.maxZ() + 0.5);
                target = new Vec3d(tx, p.getY(), tz);
                return;
            }
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
