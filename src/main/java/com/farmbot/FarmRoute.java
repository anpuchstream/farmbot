package com.farmbot;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Planejador da rota do voo do Farm (não depende do Minecraft).
 *
 * Recebe um mapa da área (1 célula = 1 bloco, true = obstáculo na altura do voo) e monta uma rota em
 * "faixas" (serpentina), com tudo sorteado a cada volta (eixo, espaçamento, deslocamento e ponta de partida).
 * Faixas cortadas por estruturas viram vários trechos, e a ligação entre trechos contorna os obstáculos
 * com A* (caminho suavizado por linha de visada). Faixas sem plantas prontas podem ser puladas.
 */
final class FarmRoute {
    final int x0, z0, w, h;
    private final boolean[] blocked;          // obstáculos já engordados (folga de voo)

    // A* (reaproveitado entre chamadas com carimbo para não realocar)
    private final float[] cost;
    private final int[] parent, stamp;
    private int curStamp;

    /** @param raw    obstáculos crus (índice cz * w + cx)
     *  @param dilate folga em blocos ao redor dos obstáculos */
    FarmRoute(int x0, int z0, int w, int h, boolean[] raw, int dilate) {
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.h = h;
        this.blocked = new boolean[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                if (!raw[z * w + x]) continue;
                for (int dz = -dilate; dz <= dilate; dz++) {
                    for (int dx = -dilate; dx <= dilate; dx++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx >= 0 && nx < w && nz >= 0 && nz < h) blocked[nz * w + nx] = true;
                    }
                }
            }
        }
        this.cost = new float[w * h];
        this.parent = new int[w * h];
        this.stamp = new int[w * h];
    }

    boolean isBlockedCell(int cx, int cz) {
        return cx < 0 || cz < 0 || cx >= w || cz >= h || blocked[cz * w + cx];
    }

    int blockedCount() {
        int n = 0;
        for (boolean b : blocked) if (b) n++;
        return n;
    }

    /** Coordenada de mundo livre? (fora do mapa conta como bloqueado) */
    boolean freeAt(double x, double z) {
        return !isBlockedCell((int) Math.floor(x - x0), (int) Math.floor(z - z0));
    }

    /** Linha reta livre entre dois pontos (o ponto de partida não é testado: dá para sair de dentro de uma folga). */
    boolean los(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.ceil(len / 0.25);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            if (!freeAt(x1 + dx * t, z1 + dz * t)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ A*

    /** Célula livre mais próxima (busca em anéis); -1 se não achar. */
    private int nearestFree(int cx, int cz) {
        cx = Math.max(0, Math.min(w - 1, cx));
        cz = Math.max(0, Math.min(h - 1, cz));
        if (!isBlockedCell(cx, cz)) return cz * w + cx;
        for (int r = 1; r <= 20; r++) {
            int bestIdx = -1;
            double bestD = 1e18;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (isBlockedCell(nx, nz)) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bestD) { bestD = d; bestIdx = nz * w + nx; }
                }
            }
            if (bestIdx >= 0) return bestIdx;
        }
        return -1;
    }

    private static final class Node implements Comparable<Node> {
        final int idx;
        final float f;
        Node(int idx, float f) { this.idx = idx; this.f = f; }
        @Override public int compareTo(Node o) { return Float.compare(f, o.f); }
    }

    /**
     * Caminho (coordenadas de mundo, sem o ponto de partida, com o destino no fim) que contorna os obstáculos.
     * null se não houver caminho.
     */
    List<double[]> path(double sx, double sz, double gx, double gz) {
        int s = nearestFree((int) Math.floor(sx - x0), (int) Math.floor(sz - z0));
        int g = nearestFree((int) Math.floor(gx - x0), (int) Math.floor(gz - z0));
        if (s < 0 || g < 0) return null;
        int gcx = g % w, gcz = g / w;

        curStamp++;
        PriorityQueue<Node> open = new PriorityQueue<Node>();
        stamp[s] = curStamp;
        cost[s] = 0f;
        parent[s] = -1;
        open.add(new Node(s, heur(s % w, s / w, gcx, gcz)));

        int expansions = 0;
        boolean found = false;
        while (!open.isEmpty()) {
            Node n = open.poll();
            int cur = n.idx;
            int cx = cur % w, cz = cur / w;
            if (n.f - heur(cx, cz, gcx, gcz) > cost[cur] + 1e-3f) continue;   // entrada velha da fila
            if (cur == g) { found = true; break; }
            if (++expansions > 120000) break;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (isBlockedCell(nx, nz)) continue;
                    if (dx != 0 && dz != 0 && (isBlockedCell(cx + dx, cz) || isBlockedCell(cx, cz + dz))) continue;
                    int ni = nz * w + nx;
                    float nc = cost[cur] + (dx != 0 && dz != 0 ? 1.41421f : 1f);
                    if (stamp[ni] != curStamp || nc < cost[ni]) {
                        stamp[ni] = curStamp;
                        cost[ni] = nc;
                        parent[ni] = cur;
                        open.add(new Node(ni, nc + heur(nx, nz, gcx, gcz)));
                    }
                }
            }
        }
        if (!found) return null;

        // reconstrói (do destino até o início) e inverte
        List<int[]> cells = new ArrayList<int[]>();
        for (int c = g; c != -1; c = parent[c]) cells.add(0, new int[]{c % w, c / w});

        // suaviza: do ponto atual, vai direto ao ponto mais distante que ainda enxerga
        List<double[]> out = new ArrayList<double[]>();
        double ax = sx, az = sz;
        int i = 0, n = cells.size() - 1;
        while (i < n) {
            int j = n;
            while (j > i + 1 && !los(ax, az, x0 + cells.get(j)[0] + 0.5, z0 + cells.get(j)[1] + 0.5)) j--;
            ax = x0 + cells.get(j)[0] + 0.5;
            az = z0 + cells.get(j)[1] + 0.5;
            out.add(new double[]{ax, az});
            i = j;
        }
        if (out.isEmpty()) out.add(new double[]{x0 + gcx + 0.5, z0 + gcz + 0.5});
        // destino exato, se der para chegar nele em linha reta
        double[] last = out.get(out.size() - 1);
        double[] prev = out.size() >= 2 ? out.get(out.size() - 2) : new double[]{sx, sz};
        if (freeAt(gx, gz) && los(prev[0], prev[1], gx, gz)) { last[0] = gx; last[1] = gz; }
        return out;
    }

    private static float heur(int ax, int az, int bx, int bz) {
        int dx = Math.abs(ax - bx), dz = Math.abs(az - bz);
        return (float) (Math.max(dx, dz) + 0.41421 * Math.min(dx, dz));
    }

    // ------------------------------------------------------------------ rota em faixas

    private static final class Seg {
        final int lane, a0, a1;
        final int dens;
        Seg(int lane, int a0, int a1, int dens) { this.lane = lane; this.a0 = a0; this.a1 = a1; this.dens = dens; }
    }

    /**
     * Monta a rota.
     *
     * @param alongX  faixas paralelas ao eixo X (senão, ao Z)
     * @param spacing distância entre faixas, em blocos
     * @param phase   deslocamento da primeira faixa (0..spacing-1)
     * @param density plantas prontas por célula (índice cz * w + cx) ou null
     * @return pontos de mundo {x, z} na ordem do voo (vazio se não achou nada)
     */
    List<double[]> plan(double px, double pz, boolean alongX, int spacing, int phase, int[] density, Random rng) {
        final int perp = alongX ? h : w;       // quantas faixas cabem
        final int along = alongX ? w : h;      // comprimento de cada faixa

        long total = 0;
        if (density != null) for (int d : density) total += d;
        final boolean skipEmpty = total >= 6;

        List<List<Seg>> lanes = new ArrayList<List<Seg>>();
        for (int r = 1 + phase; r <= perp - 2; r += spacing) {
            List<Seg> segs = new ArrayList<Seg>();
            int a = 1;
            while (a <= along - 2) {
                if (cellBlocked(alongX, a, r)) { a++; continue; }
                int a0 = a;
                while (a + 1 <= along - 2 && !cellBlocked(alongX, a + 1, r)) a++;
                int a1 = a;
                a++;
                if (a1 - a0 + 1 < 4) continue;                       // trecho curto demais
                int dens = density == null ? 1 : bandDensity(density, alongX, r, a0, a1, 2);
                if (skipEmpty && dens == 0) continue;                // faixa sem nada pronto
                segs.add(new Seg(r, a0, a1, dens));
            }
            if (!segs.isEmpty()) lanes.add(segs);
        }
        List<double[]> route = new ArrayList<double[]>();
        if (lanes.isEmpty()) return route;

        // começa por uma das pontas (a mais perto de quem voa) e anda em ordem
        double pPerp = alongX ? pz - z0 : px - x0;
        double dFirst = Math.abs(pPerp - lanes.get(0).get(0).lane);
        double dLast = Math.abs(pPerp - lanes.get(lanes.size() - 1).get(0).lane);
        boolean forward = dFirst < dLast;
        if (Math.abs(dFirst - dLast) < 0.2 * Math.max(dFirst, dLast)) forward = rng.nextBoolean();

        double curX = px, curZ = pz;
        double pAlong = alongX ? px - x0 : pz - z0;
        // 1ª faixa: começa pela ponta mais perto
        boolean dirPos = true;
        {
            List<Seg> f = lanes.get(forward ? 0 : lanes.size() - 1);
            double toStart = Math.abs(pAlong - f.get(0).a0);
            double toEnd = Math.abs(pAlong - f.get(f.size() - 1).a1);
            dirPos = toStart <= toEnd;
        }

        for (int li = 0; li < lanes.size(); li++) {
            List<Seg> segs = lanes.get(forward ? li : lanes.size() - 1 - li);
            double jitter = (rng.nextDouble() - 0.5) * 0.7;          // ±0.35 dentro da própria célula
            for (int si = 0; si < segs.size(); si++) {
                Seg sg = segs.get(dirPos ? si : segs.size() - 1 - si);
                double inA = rng.nextDouble() * 0.8, inB = rng.nextDouble() * 0.8;
                double aStart = dirPos ? sg.a0 + 0.5 + inA : sg.a1 + 0.5 - inA;
                double aEnd = dirPos ? sg.a1 + 0.5 - inB : sg.a0 + 0.5 + inB;
                double lane = sg.lane + 0.5 + jitter;
                double sx = alongX ? x0 + aStart : x0 + lane;
                double sz = alongX ? z0 + lane : z0 + aStart;
                double ex = alongX ? x0 + aEnd : x0 + lane;
                double ez = alongX ? z0 + lane : z0 + aEnd;

                // ligação (reta se der; senão contorna)
                if (!appendTransit(route, curX, curZ, sx, sz)) continue;   // inalcançável: pula o trecho
                route.add(new double[]{ex, ez});
                curX = ex;
                curZ = ez;
            }
            dirPos = !dirPos;     // serpentina
        }
        return route;
    }

    private boolean appendTransit(List<double[]> route, double fx, double fz, double tx, double tz) {
        double d = Math.hypot(tx - fx, tz - fz);
        if (d < 1.0) { route.add(new double[]{tx, tz}); return true; }
        if (los(fx, fz, tx, tz)) { route.add(new double[]{tx, tz}); return true; }
        List<double[]> p = path(fx, fz, tx, tz);
        if (p == null) return false;
        route.addAll(p);
        double[] last = p.get(p.size() - 1);
        if (Math.hypot(last[0] - tx, last[1] - tz) > 0.3) route.add(new double[]{tx, tz});
        return true;
    }

    private boolean cellBlocked(boolean alongX, int a, int r) {
        return alongX ? isBlockedCell(a, r) : isBlockedCell(r, a);
    }

    private int bandDensity(int[] density, boolean alongX, int r, int a0, int a1, int half) {
        int sum = 0;
        for (int q = r - half; q <= r + half; q++) {
            for (int a = a0; a <= a1; a++) {
                int cx = alongX ? a : q, cz = alongX ? q : a;
                if (cx < 0 || cz < 0 || cx >= w || cz >= h) continue;
                sum += density[cz * w + cx];
            }
        }
        return sum;
    }
}
