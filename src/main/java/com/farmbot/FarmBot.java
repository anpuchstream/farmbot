package com.farmbot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Lógica principal: farm (colher/replantar) e mineração, com movimento suave e aleatório. */
public class FarmBot {
    private static final Random RNG = new Random();
    private static final double REACH = 4.0;
    /** Tolerância vertical do farm: escaneia alguns blocos acima/abaixo da área marcada. */
    private static final int V_TOL = 2;
    private static final int MAX_VOLUME_FARM = 250_000;
    private static final int MAX_VOLUME_MINE = 100_000;
    private static final int MINE_SAMPLE = 200;
    private static final double WALK_SPEED = 0.20; // blocos por tick (~4 blocos/s)
    private static final double FLY_SPEED = 0.30;

    // --- farm ---
    private static final List<BlockPos> ready = new ArrayList<>();
    private static final Map<BlockPos, Crop> replantQueue = new LinkedHashMap<>();

    // --- mineração ---
    private static final List<BlockPos> mineCandidates = new ArrayList<>();
    private static int mineCount = 0;
    private static BlockPos currentMine;

    // --- movimento / câmera ---
    private static BlockPos targetBlock;
    private static Vec3d target;
    private static Vec3d cmdVel = Vec3d.ZERO;          // velocidade comandada (suavizada)
    private static Vec3d gaze;                          // ponto para onde olhar (ao colher/minerar)
    private static int gazeTicks;
    private static int idleTicks;
    private static final Set<BlockPos> badTargets = new HashSet<>(); // alvos inalcançáveis
    private static int scanTimer, cooldown, targetAge, stuckTimer, statusTimer, tickCounter;
    private static Vec3d lastCheckPos;
    private static boolean warnedFly;

    /** Quantidade de alvos conhecidos (plantas prontas ou blocos a minerar). */
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
        long max = mining ? MAX_VOLUME_MINE : MAX_VOLUME_FARM;
        if (FarmConfig.volume() > max) {
            msg(mc, "§cÁrea grande demais (" + FarmConfig.volume() + " blocos, máx " + max + ").");
            return;
        }
        ready.clear();
        replantQueue.clear();
        mineCandidates.clear();
        badTargets.clear();
        mineCount = 0;
        currentMine = null;
        target = null; targetBlock = null; lastCheckPos = null; gaze = null;
        cmdVel = Vec3d.ZERO;
        gazeTicks = 0; idleTicks = 0;
        scanTimer = 0; cooldown = 0; statusTimer = 0; warnedFly = false;
        FarmConfig.enabled = true;
        msg(mc, mining ? "§aMineração LIGADA" : "§aFarm LIGADO");
    }

    public static void stop(MinecraftClient mc, String message) {
        FarmConfig.enabled = false;
        cmdVel = Vec3d.ZERO;
        if (message != null) msg(mc, message);
    }

    // ------------------------------------------------------------------
    public static void tick(MinecraftClient mc) {
        if (!FarmConfig.enabled) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            FarmConfig.enabled = false;
            return;
        }
        if (mc.currentScreen != null) { cmdVel = Vec3d.ZERO; return; }

        tickCounter++;
        if (tickCounter % 600 == 0) badTargets.clear();
        boolean mining = FarmConfig.task == FarmConfig.Task.MINE;

        if (--scanTimer <= 0) {
            if (mining) {
                scanMine(mc);
                scanTimer = 100;
                if (mineCount == 0 && currentMine == null) {
                    stop(mc, "§aMineração concluída: nada mais para minerar na área.");
                    return;
                }
            } else {
                scanFarm(mc);
                scanTimer = 10;
            }
        }

        if (++statusTimer >= 40) { statusTimer = 0; reportStatus(mc); }

        boolean acted = false;
        if (cooldown > 0) {
            cooldown--;
        } else {
            acted = mining ? tryMine(mc, p) : (tryReplant(mc, p) || tryBreak(mc, p));
        }

        // Farm: continua andando enquanto colhe. Mineração: para suavemente enquanto quebra.
        move(mc, p, mining && acted);
        updateLook(p);
    }

    private static void reportStatus(MinecraftClient mc) {
        if (FarmConfig.task == FarmConfig.Task.MINE) {
            msg(mc, "§bAlvos: " + Math.max(mineCount, 0));
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

    private static boolean tryBreak(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        BlockPos best = null;
        double bd = REACH * REACH;
        Iterator<BlockPos> it = ready.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (matchCrop(mc, pos) == null) { it.remove(); continue; }
            double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < bd) { bd = d; best = pos; }
        }
        if (best == null) return false;

        Crop crop = matchCrop(mc, best);
        BlockState bs = mc.world.getBlockState(best);

        // ferramenta certa: tesoura para gramas/grama marinha, melhor ferramenta para blocos duros
        if (crop != null && crop.usesShears()) {
            int sh = findHotbarSlot(p, Items.SHEARS);
            if (sh >= 0) p.getInventory().selectedSlot = sh;
        } else if (bs.getHardness(mc.world, best) > 0) {
            selectBestTool(p, bs);
        }

        setGaze(Vec3d.ofCenter(best));

        if (crop != null && crop.usesRightClick()) {
            // colhe com botão direito (a planta continua no lugar)
            Vec3d hp = Vec3d.ofCenter(best);
            BlockHitResult hit = new BlockHitResult(hp, Direction.UP, best, false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            p.swingHand(Hand.MAIN_HAND);
            ready.remove(best);
            cooldown = 2;
            return true;
        }

        mc.interactionManager.updateBlockBreakingProgress(best, Direction.UP);
        p.swingHand(Hand.MAIN_HAND);

        if (mc.world.getBlockState(best).isAir()) {
            ready.remove(best);
            if (FarmConfig.replant && crop != null && crop.seed != null) {
                if (replantQueue.size() > 2000) replantQueue.clear();
                replantQueue.put(best, crop);
            }
        }
        return true;
    }

    private static boolean tryReplant(MinecraftClient mc, ClientPlayerEntity p) {
        if (replantQueue.isEmpty()) return false;
        Vec3d eye = p.getEyePos();
        Iterator<Map.Entry<BlockPos, Crop>> it = replantQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Crop> e = it.next();
            BlockPos pos = e.getKey();
            Crop crop = e.getValue();

            if (!mc.world.getBlockState(pos).isAir()) { it.remove(); continue; }
            if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH * REACH) continue;

            int slot = findHotbarSlot(p, crop.seed);
            if (slot < 0) {
                replantQueue.clear();
                msg(mc, "§cSem sementes na hotbar: " + crop.label + " (não vou replantar).");
                return false;
            }
            p.getInventory().selectedSlot = slot;

            BlockPos below = pos.down();
            Vec3d hitPos = Vec3d.ofCenter(below).add(0, 0.5, 0);
            setGaze(hitPos);
            BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, below, false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            p.swingHand(Hand.MAIN_HAND);
            it.remove();
            cooldown = 1;
            return true;
        }
        return false;
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

    /** O bloco tem alguma face em contato com ar/bloco não sólido? */
    private static boolean isExposed(World w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (!w.getBlockState(n).isSolidBlock(w, n)) return true;
        }
        return false;
    }

    /** Regras de segurança: o bloco pode ser minerado? */
    private static boolean isMinable(World w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.hasBlockEntity()) return false;
        if (s.getHardness(w, pos) < 0) return false;
        if (FarmConfig.mineMode == FarmConfig.MineMode.ORES && !isOre(s)) return false;
        for (Direction d : Direction.values()) {
            if (!w.getFluidState(pos.offset(d)).isEmpty()) return false;
        }
        return true;
    }

    /** Varredura da área inteira (a cada 5s): guarda uma amostra de alvos para o bot navegar. */
    private static void scanMine(MinecraftClient mc) {
        mineCandidates.clear();
        mineCount = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        boolean oresOnly = FarmConfig.mineMode == FarmConfig.MineMode.ORES;
        for (int x = FarmConfig.minX(); x <= FarmConfig.maxX(); x++)
            for (int y = FarmConfig.minY(); y <= FarmConfig.maxY(); y++)
                for (int z = FarmConfig.minZ(); z <= FarmConfig.maxZ(); z++) {
                    m.set(x, y, z);
                    if (!isMinable(mc.world, m)) continue;
                    if (oresOnly && !isExposed(mc.world, m)) continue;
                    mineCount++;
                    if (mineCandidates.size() < MINE_SAMPLE) {
                        mineCandidates.add(m.toImmutable());
                    } else {
                        int j = RNG.nextInt(mineCount);
                        if (j < MINE_SAMPLE) mineCandidates.set(j, m.toImmutable());
                    }
                }
    }

    /** Raycast do olho até o bloco; só retorna se o primeiro bloco atingido for ele (sem x-ray). */
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

    private static boolean tryMine(MinecraftClient mc, ClientPlayerEntity p) {
        BlockHitResult hit = null;

        if (currentMine != null) {
            BlockState cs = mc.world.getBlockState(currentMine);
            boolean ok = FarmConfig.inArea(currentMine.getX(), currentMine.getY(), currentMine.getZ())
                    && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(currentMine)) <= REACH * REACH
                    && isMinable(mc.world, currentMine)
                    && playerAllows(mc, p, currentMine, cs);
            if (ok) hit = rayTo(mc, p, currentMine);
            if (hit == null) currentMine = null;
        }

        if (currentMine == null) {
            Vec3d eye = p.getEyePos();
            int r = (int) Math.ceil(REACH);
            BlockPos base = BlockPos.ofFloored(eye);
            List<BlockPos> cands = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos pos = base.add(dx, dy, dz);
                        if (!FarmConfig.inArea(pos.getX(), pos.getY(), pos.getZ())) continue;
                        if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH * REACH) continue;
                        if (!isMinable(mc.world, pos)) continue;
                        if (!isExposed(mc.world, pos)) continue;
                        if (!playerAllows(mc, p, pos, mc.world.getBlockState(pos))) continue;
                        cands.add(pos);
                    }
            if (cands.isEmpty()) return false;
            cands.sort(Comparator.comparingDouble(b -> eye.squaredDistanceTo(Vec3d.ofCenter(b))));

            int tries = 0;
            for (BlockPos c : cands) {
                if (++tries > 40) break;
                BlockHitResult h = rayTo(mc, p, c);
                if (h != null) { currentMine = c; hit = h; break; }
            }
            if (currentMine == null) return false;
        }

        BlockState s = mc.world.getBlockState(currentMine);
        selectBestTool(p, s);
        setGaze(hit.getPos());
        mc.interactionManager.updateBlockBreakingProgress(currentMine, hit.getSide());
        p.swingHand(Hand.MAIN_HAND);

        if (mc.world.getBlockState(currentMine).isAir()) currentMine = null;
        return true;
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

    // ==================================================================
    //            MOVIMENTO SUAVE (andando/voando) + CÂMERA
    // ==================================================================
    private static void move(MinecraftClient mc, ClientPlayerEntity p, boolean hold) {
        boolean farm = FarmConfig.task == FarmConfig.Task.FARM;
        boolean fly = FarmConfig.mode == FarmConfig.Mode.FLY;
        if (fly && !p.getAbilities().allowFlying) {
            if (!warnedFly) {
                msg(mc, "§eVocê não pode voar (precisa de modo criativo). Usando modo andando.");
                warnedFly = true;
            }
            fly = false;
        }

        boolean needNew = target == null
                || ++targetAge > 300
                || reached(p, fly)
                || (targetBlock != null && !targetStillValid(mc));
        if (needNew) {
            if (target != null && targetBlock != null && targetAge > 300) badTargets.add(targetBlock);
            pickTarget(p, fly);
        }

        // detector de travamento: se ficou parado, marca o alvo como inalcançável
        if (++stuckTimer >= 20) {
            stuckTimer = 0;
            if (lastCheckPos != null && target != null && !hold && idleTicks <= 0
                    && lastCheckPos.squaredDistanceTo(p.getPos()) < 0.09) {
                if (targetBlock != null) badTargets.add(targetBlock);
                target = null;
            }
            lastCheckPos = p.getPos();
        }

        // velocidade desejada (desacelera perto do alvo, leve variação natural)
        Vec3d desired = Vec3d.ZERO;
        if (target != null) {
            Vec3d to = target.subtract(p.getPos());
            Vec3d flat = new Vec3d(to.x, 0, to.z);
            double dist = fly ? to.length() : flat.length();
            if (idleTicks > 0) {
                idleTicks--;
            } else if (!hold && dist > 0.05) {
                Vec3d dir = (fly ? to : flat).normalize();
                double slow = MathHelper.clamp(dist / 2.5, 0.25, 1.0);
                double wobble = 1.0 + 0.07 * Math.sin(tickCounter * 0.09);
                desired = dir.multiply((fly ? FLY_SPEED : WALK_SPEED) * slow * wobble);
            }
        }

        // aceleração suave (ease-in / ease-out)
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
            double cur = p.getVelocity().y;
            p.setVelocity(cmdVel.x, cur, cmdVel.z);
            double hSq = cmdVel.x * cmdVel.x + cmdVel.z * cmdVel.z;
            if (p.horizontalCollision && p.isOnGround() && hSq > 0.0016) {
                p.setVelocity(cmdVel.x, 0.42, cmdVel.z); // pulo
            }
        }
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

    /** Escolhe o próximo alvo: aleatório, mas favorecendo os mais próximos (menos zigue-zague). */
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
            final double px = p.getX(), pz = p.getZ();
            candidates.sort(Comparator.comparingDouble(b -> {
                double dx = b.getX() + 0.5 - px, dz = b.getZ() + 0.5 - pz;
                return dx * dx + dz * dz;
            }));
            int k = Math.min(candidates.size(), 8);
            int idx = (int) (Math.pow(RNG.nextDouble(), 1.6) * k);
            BlockPos c = candidates.get(Math.min(idx, candidates.size() - 1));
            targetBlock = c;
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
    private static void setGaze(Vec3d g) {
        if (gazeTicks <= 0) { gaze = g; gazeTicks = 8; }
    }

    private static float yawOf(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static void updateLook(ClientPlayerEntity p) {
        float ty, tp;
        if (gaze != null && gazeTicks > 0) {
            gazeTicks--;
            Vec3d e = p.getEyePos();
            double dx = gaze.x - e.x, dy = gaze.y - e.y, dz = gaze.z - e.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            ty = yawOf(dx, dz);
            tp = (float) -Math.toDegrees(Math.atan2(dy, h));
        } else if (cmdVel.x * cmdVel.x + cmdVel.z * cmdVel.z > 0.0009) {
            ty = yawOf(cmdVel.x, cmdVel.z) + (float) (Math.sin(tickCounter * 0.05) * 1.5);
            tp = 14f + (float) (Math.sin(tickCounter * 0.07) * 2.0);
        } else {
            return;
        }
        smoothLook(p, ty, tp, 9f, 5f);
    }

    /** Gira a câmera em direção ao alvo com easing e limite de velocidade por tick. */
    private static void smoothLook(ClientPlayerEntity p, float targetYaw, float targetPitch,
                                   float maxYawStep, float maxPitchStep) {
        float dy = MathHelper.wrapDegrees(targetYaw - p.getYaw());
        float dp = targetPitch - p.getPitch();
        float stepY = MathHelper.clamp(dy * 0.22f, -maxYawStep, maxYawStep);
        float stepP = MathHelper.clamp(dp * 0.22f, -maxPitchStep, maxPitchStep);
        p.setYaw(p.getYaw() + stepY);
        p.setPitch(MathHelper.clamp(p.getPitch() + stepP, -90f, 90f));
    }

    static void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[FarmBot] " + s), true);
    }
}
