package com.farmbot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Câmera "humana". Em vez de pular para o ângulo desejado, ela controla a VELOCIDADE angular:
 * acelera, chega a uma velocidade máxima de giro normal (~360°/s) e desacelera antes de parar
 * no alvo (movimento em "S", como o mouse de uma pessoa). Também tem um leve balanço natural.
 */
public final class HumanLook {
    private static final Random RNG = new Random();

    /** Velocidade máxima de giro (graus por tick). 18 = ~360°/s (era 6.5 = ~130°/s, parecia câmera lenta). */
    private static final float MAX_YAW_SPEED = 18.0f;
    private static final float MAX_PITCH_SPEED = 4.0f;
    /** Aceleração máxima (graus por tick²): quanto menor, mais suave o início/fim do giro. */
    private static final float YAW_ACCEL = 3.5f;
    private static final float PITCH_ACCEL = 0.7f;
    /** Quão rápido a velocidade desejada cai conforme o erro diminui (frenagem suave). */
    private static final float GAIN = 0.25f;

    private static float yawVel, pitchVel;
    private static float phaseA, phaseB;
    private static long ticks;

    private HumanLook() {}

    public static void reset() {
        yawVel = 0;
        pitchVel = 0;
        phaseA = RNG.nextFloat() * 6.2832f;
        phaseB = RNG.nextFloat() * 6.2832f;
    }

    /**
     * Gira a câmera um passo em direção ao alvo.
     *
     * @param aiming true quando está mirando numa planta (balanço menor, para não sair de cima dela)
     */
    public static void step(ClientPlayerEntity p, float targetYaw, float targetPitch, boolean aiming) {
        ticks++;
        float sway = aiming ? 0.35f : 1.0f;
        float swayYaw = (float) (Math.sin(ticks * 0.037 + phaseA) * 0.9
                + Math.sin(ticks * 0.093 + phaseB) * 0.4) * sway;
        float swayPitch = (float) (Math.sin(ticks * 0.045 + phaseB) * 0.7) * sway;

        float dy = MathHelper.wrapDegrees(targetYaw + swayYaw - p.getYaw());
        float dp = targetPitch + swayPitch - p.getPitch();

        yawVel = approach(yawVel, MathHelper.clamp(dy * GAIN, -MAX_YAW_SPEED, MAX_YAW_SPEED), YAW_ACCEL);
        pitchVel = approach(pitchVel, MathHelper.clamp(dp * GAIN, -MAX_PITCH_SPEED, MAX_PITCH_SPEED), PITCH_ACCEL);

        apply(p);
    }

    /** Sem alvo: só deixa a câmera desacelerar até parar (sem freada seca). */
    public static void coast(ClientPlayerEntity p) {
        yawVel *= 0.7f;
        pitchVel *= 0.7f;
        if (Math.abs(yawVel) < 0.01f) yawVel = 0;
        if (Math.abs(pitchVel) < 0.01f) pitchVel = 0;
        apply(p);
    }

    /** Velocidade angular atual do yaw (graus/tick). Usada para prever o arco das curvas. */
    public static float yawVelocity() {
        return yawVel;
    }

    /**
     * Um passo da dinâmica do yaw SEM balanço: dado o erro até o alvo, devolve a nova velocidade angular.
     * É a mesma regra do step(), para o bot simular a curva antes de fazê-la.
     */
    public static float simYawVel(float vel, float err) {
        return approach(vel, MathHelper.clamp(err * GAIN, -MAX_YAW_SPEED, MAX_YAW_SPEED), YAW_ACCEL);
    }

    private static void apply(ClientPlayerEntity p) {
        p.setYaw(p.getYaw() + yawVel);
        p.setPitch(MathHelper.clamp(p.getPitch() + pitchVel, -90f, 90f));
    }

    private static float approach(float current, float desired, float maxDelta) {
        return current + MathHelper.clamp(desired - current, -maxDelta, maxDelta);
    }
}
