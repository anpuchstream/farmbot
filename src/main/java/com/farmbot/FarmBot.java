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
        farmFlySpeed = 0; farmHoverY = Double.NaN;
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

    /**
     * PR: o servidor resetou a mina e teleportou o personagem para o topo dela (dentro da área marcada).
     * Descarta o estado antigo (alvos, superfície, velocidades) para o bot recomeçar dali.
     */
    private static void prResetResync(MinecraftClient mc, ClientPlayerEntity p) {
        mineCandidates.clear();
        mineCount = 0;
        badTargets.clear();
        breakCount.clear();
        scanTimer = 0;
        mineEmptyTicks = 0;
        mineTopY = Integer.MIN_VALUE;
        surfaceY = Integer.MIN_VALUE;
        surfaceTimer = 0;
        slowPos = null;
        slowTicks = 0;
        cmdVel = Vec3d.ZERO;
        corVel = Vec3d.ZERO;
        HumanLook.reset();
        msg(mc, "§eMina resetada: você foi levado para o topo da mina. O bot continua.");
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
            if (FarmConfig.task == FarmConfig.Task.MINE && prTop() && nearArea(pos)) {
                // PR "em cima": a mina resetou e o servidor levou o personagem para o topo da mina (dentro da área).
                // Não é motivo de alerta: o bot só se reajusta e continua. (O corredor fica fora da mina e não é teleportado.)
                prResetResync(mc, p);
            } else {
                guardStop(mc, "§cFarm Bot parado: você foi teleportado.");
                return false;
            }
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
                cmdVel = Vec3d.ZERO;
                runFarmFly(mc, p, holdForBreak);   // voando baixo: mesmo rumo aleatório do farm correndo
            } else {
                farmFlySpeed = 0;
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
        if (ffLastTick == tickCounter && ffCropY != Integer.MIN_VALUE && Math.abs(pos.getY() - ffCropY) > 1) return;   // voo otimizado: ignora enfeites altos
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

    /** Blocos que o PR "Todos os blocos" nunca quebra: cercas, placas (comuns e suspensas) e bedrock. */
    private static boolean isProtectedBlock(BlockState s) {
        return s.isOf(Blocks.BEDROCK)
                || s.isIn(BlockTags.FENCES)
                || s.isIn(BlockTags.ALL_SIGNS);
    }

    private static boolean isMinable(World w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.hasBlockEntity()) return false;
        if (s.getHardness(w, pos) < 0) return false;
        if (isPR()) {
            if (FarmConfig.allBlocksPR) {
                // PR "Todos os blocos": quebra tudo, menos cerca, placa e bedrock
                if (isProtectedBlock(s)) return false;
            } else if (!(isOre(s) || isTerracotta(s))) {
                return false;   // PR "Só minérios": minérios + terracota (o piso das minas de prisão)
            }
        } else if (FarmConfig.oresOnlyEC && !isOre(s)) {
            return false;
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
    //  A parede da mina é a "frente". Como um jogador segurando SHIFT + A (ou D) + botão de quebrar:
    //  desce em diagonal, rápido, sempre para o mesmo lado; ao chegar na última camada inverte e SOBE
    //  na diagonal do mesmo lado, e assim por diante, rodeando a mina. A mira fica TRAVADA na parede
    //  (quebra o que aparece no crosshair) e só gira quando ele contorna o canto para a próxima parede.
    // ------------------------------------------------------------------
    private static int corNX, corNZ;          // direção da mina vista do corredor (um dos dois é 0)
    private static double corN0;              // posição inicial no eixo da frente (limite para trás)
    /** Lado da diagonal em relação ao "direita" do jogador (+1 direita / -1 esquerda). Mantido nos cantos. */
    private static int corDir = 1;
    /** Vertical: -1 desce, +1 sobe. */
    private static int corVDir = -1;
    private static int corTurnTicks, corBlockedTicks;
    private static double corLastLat;
    private static Vec3d corVel = Vec3d.ZERO;
    /** Pitch fixo da câmera no corredor (0 = olhando reto para a parede). */
    private static final float COR_PITCH = 0f;

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
        corTurnTicks = 0;
        corBlockedTicks = 0;
        corVel = Vec3d.ZERO;
        corLastLat = latPos(p);
        // começa DESCENDO (a menos que já esteja embaixo) e para o lado onde há mais espaço
        corVDir = p.getY() <= corBottomY() + 1.0 ? 1 : -1;
        double[] lr = latRange();
        double lat = latPos(p);
        int rs = corNX - corNZ;                       // sinal (no mundo) do "direita" do jogador
        int worldSign = (lr[1] - lat) >= (lat - lr[0]) ? 1 : -1;
        corDir = worldSign * rs;
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

    /** Velocidade por eixo (blocos/tick) da diagonal: o multiplicador do menu manda (1.5X = 0.6 por eixo). */
    private static double corSpeed() {
        return Math.min(0.40 * FarmConfig.mineFlySpeed, MAX_MINE_SPEED / 1.4142);
    }

    /** Altura mais baixa (pés) da descida: a última camada da área. */
    private static double corBottomY() {
        return FarmConfig.minY();
    }

    /** Altura mais alta (pés) da subida: o olho fica na última camada de cima. */
    private static double corTopY() {
        return Math.max(FarmConfig.maxY() - 1.0, corBottomY() + 2.5);
    }

    /** Sentido (+1/-1) da diagonal no eixo X/Z do mundo, a partir do lado (direita/esquerda) escolhido. */
    private static int latWorldSign() {
        return corDir * (corNX - corNZ);
    }

    /** Tem parede/bloco sólido logo à frente, na direção lateral (corpo inteiro)? */
    private static boolean lateralBlocked(MinecraftClient mc, ClientPlayerEntity p, int ws, double speed) {
        double d = 0.45 + speed;
        double x = p.getX() + (corNX == 0 ? ws * d : 0);
        double z = p.getZ() + (corNX != 0 ? ws * d : 0);
        int by = MathHelper.floor(p.getY());
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int h = 0; h <= 1; h++) {
            m.set(MathHelper.floor(x), by + h, MathHelper.floor(z));
            if (!mc.world.getBlockState(m).getCollisionShape(mc.world, m).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Chegou no fim da parede (canto do corredor): contorna a mina. O lado da diagonal é mantido; a nova
     * "frente" gira 90° e o bot passa a andar na direção da antiga frente. Só vira se o corredor continuar
     * (3 blocos livres); senão devolve false e o bot apenas inverte o lado (corredor de uma parede só).
     */
    private static boolean cornerTurn(MinecraftClient mc, ClientPlayerEntity p) {
        int by = MathHelper.floor(p.getY());
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int k = 1; k <= 3; k++) {
            for (int h = 0; h <= 1; h++) {
                m.set(MathHelper.floor(p.getX() + corNX * k), by + h, MathHelper.floor(p.getZ() + corNZ * k));
                if (!mc.world.getBlockState(m).getCollisionShape(mc.world, m).isEmpty()) return false;
            }
        }
        int nx = corDir * corNZ, nz = -corDir * corNX;
        corNX = nx;
        corNZ = nz;
        corN0 = p.getX() * corNX + p.getZ() * corNZ;
        mineCandidates.clear();
        scanTimer = 0;     // reescaneia já a nova parede
        return true;
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

        // ---- câmera TRAVADA na parede (sem balanço): só gira quando o bot contorna um canto
        HumanLook.stepLocked(p, yawOf(corNX, corNZ), COR_PITCH);

        final double s = corSpeed();

        if (corTurnTicks > 0) corTurnTicks--;

        // ---- vertical: desce até a última camada, inverte e sobe (e vice-versa)
        if (corVDir < 0 && (p.getY() <= corBottomY() || (p.verticalCollision && corVel.y < -0.05))) corVDir = 1;
        else if (corVDir > 0 && (p.getY() >= corTopY() || (p.verticalCollision && corVel.y > 0.05))) corVDir = -1;

        // ---- lateral: fim da parede = canto (contorna a mina) ou, sem corredor adiante, inverte o lado
        double[] lr = latRange();
        double lat = latPos(p);
        int ws = latWorldSign();
        if (corTurnTicks == 0) {
            boolean blocked = (ws > 0 && lat >= lr[1]) || (ws < 0 && lat <= lr[0]) || lateralBlocked(mc, p, ws, s);
            double moved = (lat - corLastLat) * ws;
            corBlockedTicks = moved < s * 0.3 ? corBlockedTicks + 1 : 0;   // empacado de lado
            if (corBlockedTicks >= 5) blocked = true;
            if (blocked) {
                if (!cornerTurn(mc, p)) corDir = -corDir;
                ws = latWorldSign();
                corTurnTicks = 8;
                corBlockedTicks = 0;
            }
        }
        corLastLat = latPos(p);

        // ---- distância da parede: ela recua quando os blocos quebram; chega mais perto se ficou longe
        double vn = 0;
        if (corTurnTicks == 0 && mineCount > 0) {
            double dn = faceDistAhead(p);
            double pn = p.getX() * corNX + p.getZ() * corNZ;
            if (dn != Double.MAX_VALUE) {
                if (dn > 3.2 && clearAhead(mc, p)) vn = s * 0.7;
                else if (dn < 1.2 && pn > corN0 - 0.8) vn = -s * 0.4;
            }
        }

        double vlat = ws * s;
        double vy = corVDir * s;
        corVel = corNX != 0 ? new Vec3d(corNX * vn, vy, vlat) : new Vec3d(vlat, vy, corNZ * vn);
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
        HumanLook.stepFarm(p, ty, tp, true);   // perfil suave do farm (a mineração usa o rápido)
    }

    // ==================================================================
    //   VOO NO FARM: a mesma lógica do farm correndo (rumo aleatório de 3–5 s, mira varrendo,
    //   quebra a cada tick, sem perseguir plantas), só que voando baixo logo acima das plantas.
    // ==================================================================

    /** Velocidade de cruzeiro do voo no farm (blocos/tick; 0.85 = 17 blocos/s). Era 0.40 (8 blocos/s). */
    private static final double FARM_FLY_SPEED = 0.85;
    /** Rampa de aceleração e de frenagem (blocos/tick a cada tick): sem arrancada nem freada seca. */
    private static final double FARM_FLY_ACCEL = 0.05;
    private static final double FARM_FLY_DECEL = 0.11;
    /** Frenagem usada para parar antes da borda da área (limita a velocidade pela distância livre à frente). */
    private static final double FARM_FLY_BRAKE = 0.08;
    /** Parada rápida quando está quebrando algo demorado (melancia, abóbora): a mira não sai do bloco. */
    private static final double FARM_FLY_HOLD_DECEL = 0.30;
    /** Altura dos pés acima do topo das plantas (1.0 = pés na altura do topo; aumente para voar mais alto). */
    private static final double FARM_FLY_CLEARANCE = 1.0;

    private static double farmFlySpeed;
    private static double farmHoverY = Double.NaN;

    /** Altura de voo: logo acima da planta mais alta que o bot conhece (ou do topo da área, se não há nenhuma). */
    private static void updateFarmHoverY() {
        if (ffCropY != Integer.MIN_VALUE) {          // nível das plantas medido no escaneamento da área
            farmHoverY = ffCropY + FARM_FLY_CLEARANCE;
            return;
        }
        int top = Integer.MIN_VALUE;
        for (BlockPos b : ready) if (b.getY() > top) top = b.getY();
        for (BlockPos b : replantQueue.keySet()) if (b.getY() > top) top = b.getY();
        if (top == Integer.MIN_VALUE) top = FarmConfig.maxY();
        farmHoverY = top + FARM_FLY_CLEARANCE;
    }

    /** Voo antigo (rumo aleatório): reserva automática quando não dá para montar uma rota. */
    private static void runFarmFlyLegacy(MinecraftClient mc, ClientPlayerEntity p, boolean holdForBreak) {
        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
        releaseKeys(mc);                                  // o voo é por velocidade: nenhuma tecla fica presa
        if (p.isSprinting()) p.setSprinting(false);
        if (tickCounter % 5 == 0 || Double.isNaN(farmHoverY)) updateFarmHoverY();

        final double px = p.getX(), pz = p.getZ();

        // travou em algo (quis avançar quase o tempo todo e quase não saiu do lugar): vira bastante
        if (++stuckTimer >= 20) {
            stuckTimer = 0;
            if (lastCheckPos != null && forwardTicks >= 14
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

        // velocidade desejada: cruzeiro quando a câmera está alinhada com o rumo, reduz nas viradas
        // grandes e freia a tempo de não passar da borda
        float yawErr = Math.abs(MathHelper.wrapDegrees(heading - p.getYaw()));
        double want = FARM_FLY_SPEED;
        if (holdForBreak || yawErr >= 100f) want = 0;
        else if (yawErr >= 40f) want = FARM_FLY_SPEED * (100f - yawErr) / 60.0;
        if (insideArea(px, pz, 0.2)) {
            double free = freeDist(px, pz, p.getYaw(), 0.2);
            want = Math.min(want, Math.sqrt(2 * FARM_FLY_BRAKE * Math.max(0.0, free - 0.4)));
        }
        if (want < farmFlySpeed) farmFlySpeed = Math.max(want, farmFlySpeed - FARM_FLY_DECEL);
        else farmFlySpeed = Math.min(want, farmFlySpeed + FARM_FLY_ACCEL);
        if (want > 0.1) forwardTicks++;

        // câmera: igual à do farm correndo (segue o rumo e varre o chão à frente)
        if (holdForBreak) {
            HumanLook.coast(p);                           // quebrando algo demorado: mira parada
        } else {
            float ty = heading + (float) (Math.sin(tickCounter * 0.13) * 12.0);
            float tp = 36f + (float) (Math.sin(tickCounter * 0.07 + 1.0) * 8.0);
            HumanLook.stepFarm(p, ty, tp, true);
        }

        // a velocidade segue a câmera (curvas em arco, sem deslizar de lado) e mantém a altura
        float yaw = p.getYaw();
        double vy = MathHelper.clamp((farmHoverY - p.getY()) * 0.25, -0.3, 0.3);
        p.setVelocity(dirX(yaw) * farmFlySpeed, vy, dirZ(yaw) * farmFlySpeed);
    }


    // ==================================================================
    //   VOO NO FARM OTIMIZADO: escaneia a área, monta uma rota em faixas (serpentina sorteada a cada volta),
    //   contorna estruturas no meio do campo e mira nas plantas prontas ao alcance.
    // ==================================================================

    /** Distância ao ponto da rota em que o bot considera que chegou. */
    private static final double FARM_FLY_ARRIVE = 0.9;
    /** Giro máximo do rumo de VOO (graus/tick). A câmera é independente e gira no ritmo humano. */
    private static final float FARM_FLY_TURN = 14f;
    /** Alcance (olhos até a planta) usado para escolher alvos; menor que o alcance real (4.5). */
    private static final double FARM_AIM_REACH = 4.1;

    private static FarmRoute ffGrid;
    private static int ffGridSig, ffGridTick = -100000;
    private static int ffCropY = Integer.MIN_VALUE;          // nível (Y) das plantas, medido no escaneamento
    private static List<double[]> ffRoute;
    private static int ffIdx, ffPlanTick, ffLastTick = -10, ffRetryTick;
    private static float ffYaw;                              // rumo de voo (independe da câmera)
    private static int ffStuckTimer, ffStuckForward, ffStuckCount, ffLegacyUntil;
    private static Vec3d ffStuckPos;
    private static BlockPos ffAim;
    private static int ffAimTicks;
    private static float ffAimYaw, ffAimPitch;
    private static final Map<BlockPos, Integer> ffIgnore = new HashMap<>();
    private static final List<int[]> ffExtra = new ArrayList<>();   // {x, z, expira}: obstáculos achados voando

    private static void runFarmFly(MinecraftClient mc, ClientPlayerEntity p, boolean holdForBreak) {
        final double px = p.getX(), pz = p.getZ();
        final boolean resumed = ffLastTick != tickCounter - 1;

        // fora da área, rota impossível ou preso várias vezes: voo antigo (rumo aleatório)
        if (tickCounter < ffLegacyUntil || !insideArea(px, pz, 0) || !ffEnsureRoute(mc, p, resumed)) {
            ffLastTick = -10;
            runFarmFlyLegacy(mc, p, holdForBreak);
            return;
        }

        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
        releaseKeys(mc);                                  // o voo é por velocidade: nenhuma tecla fica presa
        if (p.isSprinting()) p.setSprinting(false);
        if (tickCounter % 5 == 0 || Double.isNaN(farmHoverY)) updateFarmHoverY();

        // ---- avança nos pontos da rota
        double[] wp = ffRoute.get(ffIdx);
        double dx = wp[0] - px, dz = wp[1] - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean ended = false;
        while (dist < FARM_FLY_ARRIVE) {
            ffIdx++;
            if (ffIdx >= ffRoute.size()) { ended = true; break; }
            if (tickCounter - ffPlanTick > 1800) { ended = true; break; }   // replaneja (as plantas amadurecem)
            wp = ffRoute.get(ffIdx);
            dx = wp[0] - px;
            dz = wp[1] - pz;
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        if (ended) {
            ffRoute = null;                                // replaneja no próximo tick; este tick segue reto
            wp = new double[]{px + dirX(ffYaw) * 6.0, pz + dirZ(ffYaw) * 6.0};
            dx = wp[0] - px;
            dz = wp[1] - pz;
            dist = 6.0;
        }
        final float bearing = dist < 1.0e-6 ? ffYaw : yawOf(dx, dz);
        if (resumed) ffYaw = bearing;

        // ---- velocidade: cruzeiro nas retas; reduz antes de curvas fechadas (retorno de faixa)
        double cornerV = FARM_FLY_SPEED * 0.2;
        if (!ended && ffRoute != null && ffIdx + 1 < ffRoute.size()) {
            double[] nx = ffRoute.get(ffIdx + 1);
            float out = yawOf(nx[0] - wp[0], nx[1] - wp[1]);
            float theta = Math.abs(MathHelper.wrapDegrees(out - bearing));
            cornerV = FARM_FLY_SPEED * MathHelper.clamp(1.0 - theta / 110.0, 0.2, 1.0);
        }
        if (ended) cornerV = FARM_FLY_SPEED;
        double want = Math.min(FARM_FLY_SPEED,
                Math.sqrt(cornerV * cornerV + 2 * FARM_FLY_BRAKE * Math.max(0.0, dist - FARM_FLY_ARRIVE)));
        if (holdForBreak) farmFlySpeed = Math.max(0.0, farmFlySpeed - FARM_FLY_HOLD_DECEL);
        else if (want < farmFlySpeed) farmFlySpeed = Math.max(want, farmFlySpeed - FARM_FLY_DECEL);
        else farmFlySpeed = Math.min(want, farmFlySpeed + FARM_FLY_ACCEL);

        // ---- rumo de voo: acompanha o ponto da rota com giro limitado (curvas em arco)
        float yerr = MathHelper.wrapDegrees(bearing - ffYaw);
        ffYaw = MathHelper.wrapDegrees(ffYaw + (float) MathHelper.clamp(yerr, -FARM_FLY_TURN, FARM_FLY_TURN));
        heading = ffYaw;

        // ---- preso em algo (quis avançar e quase não saiu do lugar): marca o obstáculo e replaneja
        if (farmFlySpeed > 0.3) ffStuckForward++;
        if (++ffStuckTimer >= 20) {
            ffStuckTimer = 0;
            if (ffStuckPos != null && ffStuckForward >= 14 && ffStuckPos.squaredDistanceTo(p.getPos()) < 0.25) {
                ffOnStuck(p);
            } else if (ffStuckPos != null && ffStuckPos.squaredDistanceTo(p.getPos()) > 4.0) {
                ffStuckCount = 0;
            }
            ffStuckPos = p.getPos();
            ffStuckForward = 0;
        }

        // ---- câmera: mira nas plantas prontas ao alcance; sem alvo, varre o chão à frente
        ffAimUpdate(p, holdForBreak);
        if (holdForBreak) {
            HumanLook.coast(p);                           // quebrando algo demorado: mira parada
        } else if (ffAim != null) {
            HumanLook.stepFarm(p, ffAimYaw, ffAimPitch, true);
        } else {
            float ty = ffYaw + (float) (Math.sin(tickCounter * 0.13) * 12.0);
            float tp = 36f + (float) (Math.sin(tickCounter * 0.07 + 1.0) * 8.0);
            HumanLook.stepFarm(p, ty, tp, true);
        }

        // ---- a velocidade segue a ROTA (não a câmera) e mantém a altura
        double vy = MathHelper.clamp((farmHoverY - p.getY()) * 0.25, -0.3, 0.3);
        p.setVelocity(dirX(ffYaw) * farmFlySpeed, vy, dirZ(ffYaw) * farmFlySpeed);
        ffLastTick = tickCounter;
    }

    /** Garante grade e rota válidas. false = não deu (usa o voo antigo). */
    private static boolean ffEnsureRoute(MinecraftClient mc, ClientPlayerEntity p, boolean resumed) {
        if (tickCounter < ffRetryTick) return false;
        int sig = java.util.Objects.hash(FarmConfig.minX(), FarmConfig.maxX(), FarmConfig.minY(), FarmConfig.maxY(),
                FarmConfig.minZ(), FarmConfig.maxZ(), FarmConfig.crops.hashCode());
        boolean needGrid = ffGrid == null || sig != ffGridSig || tickCounter - ffGridTick > 3000;
        if (needGrid) {
            ffGrid = ffBuildGrid(mc);
            ffGridSig = sig;
            ffGridTick = tickCounter;
            ffRoute = null;
            if (ffGrid == null) {
                ffCropY = Integer.MIN_VALUE;
                ffRetryTick = tickCounter + 200;
                return false;
            }
            msg(mc, "§bFarm voando: área escaneada (" + ffGrid.w + "x" + ffGrid.h + ", "
                    + ffGrid.blockedCount() + " células com obstáculo"
                    + (ffCropY == Integer.MIN_VALUE ? "" : ", plantas em Y=" + ffCropY) + ").");
        }
        if (ffRoute == null || ffIdx >= ffRoute.size() || resumed) {
            if (!ffPlan(p)) {
                ffRetryTick = tickCounter + 100;
                return false;
            }
        }
        return true;
    }

    /** Escaneia a área: acha o nível das plantas e marca as colunas com bloco sólido na altura do voo. */
    private static FarmRoute ffBuildGrid(MinecraftClient mc) {
        final int x0 = FarmConfig.minX(), z0 = FarmConfig.minZ();
        final int w = FarmConfig.maxX() - x0 + 1, h = FarmConfig.maxZ() - z0 + 1;
        if (w < 6 || h < 6 || (long) w * h > 250000L) return null;
        final int yLo = FarmConfig.minY() - V_TOL, yHi = FarmConfig.maxY() + V_TOL;

        // 1) nível das plantas: o mais ALTO que tenha pelo menos 30% do nível mais cheio
        //    (enfeites com poucas plantas, como uma estátua de abóboras, ficam de fora)
        int[] hist = new int[yHi - yLo + 1];
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x < x0 + w; x++)
            for (int z = z0; z < z0 + h; z++)
                for (int y = yLo; y <= yHi; y++) {
                    BlockState s = mc.world.getBlockState(m.set(x, y, z));
                    if (s.isAir()) continue;
                    for (Crop c : FarmConfig.crops) {
                        if (c.matches(s)) { hist[y - yLo]++; break; }
                    }
                }
        int max = 0;
        for (int v : hist) max = Math.max(max, v);
        int cropY = Integer.MIN_VALUE;
        if (max > 0) {
            for (int i = hist.length - 1; i >= 0; i--) {
                if (hist[i] * 10 >= max * 3) { cropY = yLo + i; break; }
            }
        }
        ffCropY = cropY;
        final int level = cropY != Integer.MIN_VALUE ? cropY : FarmConfig.maxY();

        // 2) obstáculos: colunas com bloco de colisão onde ficam os pés e a cabeça (voo = topo das plantas)
        final int yFeet = level + 1;
        boolean[] raw = new boolean[w * h];
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++)
                for (int dy = 0; dy <= 1; dy++) {
                    BlockState s = mc.world.getBlockState(m.set(x0 + x, yFeet + dy, z0 + z));
                    if (!s.isAir() && !s.getCollisionShape(mc.world, m).isEmpty()) { raw[z * w + x] = true; break; }
                }
        // 3) obstáculos descobertos voando (o bot ficou preso ali)
        for (int i = ffExtra.size() - 1; i >= 0; i--) {
            int[] e = ffExtra.get(i);
            if (e[2] <= tickCounter) { ffExtra.remove(i); continue; }
            int cx = e[0] - x0, cz = e[1] - z0;
            if (cx >= 0 && cz >= 0 && cx < w && cz < h) raw[cz * w + cx] = true;
        }
        return new FarmRoute(x0, z0, w, h, raw, 1);
    }

    /** Monta uma rota nova a partir da posição atual. */
    private static boolean ffPlan(ClientPlayerEntity p) {
        FarmRoute g = ffGrid;
        if (g == null) return false;
        int[] dens = new int[g.w * g.h];
        for (BlockPos b : ready) ffCount(dens, g, b);
        for (BlockPos b : replantQueue.keySet()) ffCount(dens, g, b);

        int sp = 4 + RNG.nextInt(2);                                        // espaço entre faixas: 4 ou 5
        boolean alongX;
        if (g.w >= g.h * 1.3) alongX = RNG.nextInt(100) < 80;               // prefere faixas no lado maior
        else if (g.h >= g.w * 1.3) alongX = RNG.nextInt(100) < 20;
        else alongX = RNG.nextBoolean();

        List<double[]> r = g.plan(p.getX(), p.getZ(), alongX, sp, RNG.nextInt(sp), dens, RNG);
        if (r.isEmpty()) r = g.plan(p.getX(), p.getZ(), alongX, sp, RNG.nextInt(sp), null, RNG);   // sem pular faixas
        if (r.isEmpty()) return false;
        ffRoute = r;
        ffIdx = 0;
        ffPlanTick = tickCounter;
        return true;
    }

    private static void ffCount(int[] dens, FarmRoute g, BlockPos b) {
        if (ffCropY != Integer.MIN_VALUE && Math.abs(b.getY() - ffCropY) > 1) return;
        int cx = b.getX() - g.x0, cz = b.getZ() - g.z0;
        if (cx >= 0 && cz >= 0 && cx < g.w && cz < g.h) dens[cz * g.w + cx]++;
    }

    /** Preso: marca as células à frente como obstáculo, replaneja e, se repetir, volta ao voo antigo por um tempo. */
    private static void ffOnStuck(ClientPlayerEntity p) {
        double fx = dirX(ffYaw), fz = dirZ(ffYaw);
        for (double d = 1.0; d <= 2.0; d += 0.5) {
            ffExtra.add(new int[]{(int) Math.floor(p.getX() + fx * d), (int) Math.floor(p.getZ() + fz * d), tickCounter + 3000});
        }
        ffGridTick = -100000;     // refaz a grade (já com esses obstáculos) e a rota
        ffRoute = null;
        if (++ffStuckCount >= 4) {
            ffLegacyUntil = tickCounter + 300;
            ffStuckCount = 0;
        }
    }

    /** Escolhe/mantém o alvo da mira: planta pronta (ou terra a replantar) ao alcance, com o menor giro. */
    private static void ffAimUpdate(ClientPlayerEntity p, boolean hold) {
        if (tickCounter % 300 == 0) ffIgnore.values().removeIf(t -> t <= tickCounter);
        final Vec3d eye = p.getEyePos();
        final float yaw = p.getYaw(), pitch = p.getPitch();

        if (ffAim != null) {
            boolean listed = replantQueue.containsKey(ffAim) || ready.contains(ffAim);
            if (!hold && ++ffAimTicks > 14) {              // não está quebrando (algo no caminho?): desiste dele
                ffIgnore.put(ffAim, tickCounter + 200);
                ffAim = null;
            } else if (!listed || Double.isNaN(ffAimCost(eye, yaw, pitch, ffAimPoint(ffAim)))) {
                ffAim = null;
            }
        }
        if (ffAim == null) {
            ffAimTicks = 0;
            double best = Double.MAX_VALUE;
            BlockPos bestPos = null;
            for (BlockPos b : ready) {
                double c = ffAimScore(eye, yaw, pitch, b);
                if (c < best) { best = c; bestPos = b; }
            }
            for (BlockPos b : replantQueue.keySet()) {
                double c = ffAimScore(eye, yaw, pitch, b);
                if (c < best) { best = c; bestPos = b; }
            }
            ffAim = bestPos;
        }
        if (ffAim != null) {
            Vec3d pt = ffAimPoint(ffAim);
            double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            ffAimYaw = hd < 0.05 ? yaw : yawOf(dx, dz);
            ffAimPitch = (float) Math.toDegrees(Math.atan2(-dy, hd));
        }
    }

    private static double ffAimScore(Vec3d eye, float yaw, float pitch, BlockPos b) {
        if (Math.abs(b.getX() + 0.5 - eye.x) > FARM_AIM_REACH || Math.abs(b.getZ() + 0.5 - eye.z) > FARM_AIM_REACH) {
            return Double.MAX_VALUE;                                   // longe demais (descarte rápido)
        }
        if (ffCropY != Integer.MIN_VALUE && Math.abs(b.getY() - ffCropY) > 1) return Double.MAX_VALUE;  // enfeite/estrutura
        Integer ig = ffIgnore.get(b);
        if (ig != null && ig > tickCounter) return Double.MAX_VALUE;
        double c = ffAimCost(eye, yaw, pitch, ffAimPoint(b));
        return Double.isNaN(c) ? Double.MAX_VALUE : c;
    }

    /** Ponto de mira: o meio da planta; para replantar, o topo da terra logo abaixo. */
    private static Vec3d ffAimPoint(BlockPos b) {
        if (replantQueue.containsKey(b)) return new Vec3d(b.getX() + 0.5, b.getY() - 0.12, b.getZ() + 0.5);
        return new Vec3d(b.getX() + 0.5, b.getY() + 0.4, b.getZ() + 0.5);
    }

    /** Custo (giro necessário) para mirar o ponto; NaN se estiver fora do alcance ou num ângulo ruim. */
    private static double ffAimCost(Vec3d eye, float yaw, float pitch, Vec3d pt) {
        double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (Math.sqrt(hd * hd + dy * dy) > FARM_AIM_REACH) return Double.NaN;
        float tp = (float) Math.toDegrees(Math.atan2(-dy, hd));
        if (tp < 12f || tp > 78f) return Double.NaN;                   // quase na horizontal ou bem embaixo
        float ty = hd < 0.05 ? yaw : yawOf(dx, dz);
        float rel = Math.abs(MathHelper.wrapDegrees(ty - ffYaw));      // ângulo em relação ao rumo de voo
        if (rel > 85f) return Double.NaN;                              // ficou para trás
        float turn = Math.abs(MathHelper.wrapDegrees(ty - yaw));
        return turn + 0.6 * Math.abs(tp - pitch) + 0.15 * rel;
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
        HumanLook.stepFarm(p, ty, tp, aiming);
    }

    static void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[FarmBot] " + s), true);
    }
}
