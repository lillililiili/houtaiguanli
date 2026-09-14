package com.uav.lowaltitude.modules.fusion.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.uav.lowaltitude.modules.fusion.domain.AssociationCost.Candidate;
import com.uav.lowaltitude.modules.fusion.domain.AssociationCost.Result;

/**
 * 同帧同源观测与候选目标的一对一关联：n ≤ 64 用匈牙利算法取总代价最小，否则按代价贪心。
 * 同一来源同一帧的两条观测不能进同一目标；跨分区（source_mode / 归属元组不同）的候选永不匹配；
 * 次优与最优代价差小于契约固定的 1.0 时记为歧义（进 association_pending），但最优匹配仍返回给管线以维持原始层连续。
 */
public final class Associator {
    /** 契约：匈牙利匹配的规模上限（超过改贪心）；1.0 为契约固定的歧义边距。 */
    public static final int HUNGARIAN_MAX_N = 64;
    public static final double AMBIGUITY_MARGIN = 1.0;

    private final AssociationCost cost;

    public Associator(AssociationCost cost) { this.cost = cost; }

    public record Match(int observationIndex, String targetId, double cost) { }
    public record Ambiguity(int observationIndex, List<String> candidateTargetIds, String bestTargetId, double bestCost, double secondCost) { }
    public record Result(List<Match> matches, List<Integer> unmatchedObservations, List<String> unmatchedTargets, List<Ambiguity> ambiguities) { }

    public Result associate(List<SourceObservation> observations, List<Double> observationAccuracies, List<Candidate> candidates) {
        int rows = observations.size(), cols = candidates.size();
        double[][] matrix = new double[rows][cols];
        boolean[][] gated = new boolean[rows][cols];
        for (int i = 0; i < rows; i++) {
            SourceObservation observation = observations.get(i);
            for (int j = 0; j < cols; j++) {
                Candidate candidate = candidates.get(j);
                // 跨分区永不关联：不同 source_mode（回放/模拟/实测）或不同归属元组的目标不是同一物理对象的证据。
                if (!observation.domain().equals(candidate.domain())) { gated[i][j] = false; matrix[i][j] = Double.POSITIVE_INFINITY; continue; }
                AssociationCost.Result r = cost.evaluate(observation, observationAccuracies.get(i), candidate);
                gated[i][j] = r.gated();
                matrix[i][j] = r.gated() ? r.cost() : Double.POSITIVE_INFINITY;
            }
        }
        int[] assignment = rows == 0 || cols == 0 ? new int[rows] : (Math.max(rows, cols) <= HUNGARIAN_MAX_N ? hungarian(matrix, rows, cols) : greedy(matrix, rows, cols));
        if (rows == 0 || cols == 0) Arrays.fill(assignment, -1);

        List<Match> matches = new ArrayList<>();
        List<Integer> unmatchedObservations = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        Set<String> matchedTargets = new HashSet<>();
        for (int i = 0; i < rows; i++) {
            int j = assignment[i];
            if (j < 0 || !gated[i][j]) { unmatchedObservations.add(i); continue; }
            String targetId = candidates.get(j).targetId();
            matches.add(new Match(i, targetId, matrix[i][j]));
            matchedTargets.add(targetId);
            // 歧义：同一观测还有别的门限内候选且代价与最优相差不足 1.0。
            double second = Double.POSITIVE_INFINITY;
            List<String> within = new ArrayList<>();
            within.add(targetId);
            for (int k = 0; k < cols; k++) {
                if (k == j || !gated[i][k]) continue;
                within.add(candidates.get(k).targetId());
                second = Math.min(second, matrix[i][k]);
            }
            if (within.size() > 1 && second - matrix[i][j] < AMBIGUITY_MARGIN) ambiguities.add(new Ambiguity(i, List.copyOf(within), targetId, matrix[i][j], second));
        }
        List<String> unmatchedTargets = new ArrayList<>();
        for (Candidate candidate : candidates) if (!matchedTargets.contains(candidate.targetId())) unmatchedTargets.add(candidate.targetId());
        return new Result(List.copyOf(matches), List.copyOf(unmatchedObservations), List.copyOf(unmatchedTargets), List.copyOf(ambiguities));
    }

    /** 贪心：按代价从小到大取，观测与目标各用一次。 */
    private static int[] greedy(double[][] matrix, int rows, int cols) {
        record Cell(int i, int j, double c) { }
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) if (matrix[i][j] < Double.POSITIVE_INFINITY) cells.add(new Cell(i, j, matrix[i][j]));
        cells.sort(Comparator.comparingDouble(Cell::c));
        int[] assignment = new int[rows];
        Arrays.fill(assignment, -1);
        boolean[] usedTarget = new boolean[cols];
        for (Cell cell : cells) {
            if (assignment[cell.i()] >= 0 || usedTarget[cell.j()]) continue;
            assignment[cell.i()] = cell.j();
            usedTarget[cell.j()] = true;
        }
        return assignment;
    }

    /** 匈牙利算法（Kuhn-Munkres，方阵补齐）；不可匹配格子用"任何合法整体方案都到不了"的有限大数代替无穷。 */
    private static int[] hungarian(double[][] matrix, int rows, int cols) {
        int n = Math.max(rows, cols);
        double finiteMax = 0;
        for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) if (matrix[i][j] < Double.POSITIVE_INFINITY) finiteMax = Math.max(finiteMax, matrix[i][j]);
        double unmatchable = (finiteMax + 1) * (n + 1);
        double[][] a = new double[n + 1][n + 1];
        for (int i = 1; i <= n; i++) for (int j = 1; j <= n; j++) {
            boolean real = i <= rows && j <= cols && matrix[i - 1][j - 1] < Double.POSITIVE_INFINITY;
            a[i][j] = real ? matrix[i - 1][j - 1] : unmatchable;
        }
        double[] u = new double[n + 1], v = new double[n + 1];
        int[] p = new int[n + 1], way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[n + 1];
            do {
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) continue;
                    double cur = a[i0][j] - u[i0] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0; }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j; }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; } else minv[j] -= delta;
                }
                j0 = j1;
            } while (p[j0] != 0);
            do { int j1 = way[j0]; p[j0] = p[j1]; j0 = j1; } while (j0 != 0);
        }
        int[] assignment = new int[rows];
        Arrays.fill(assignment, -1);
        for (int j = 1; j <= n; j++) {
            int i = p[j];
            if (i >= 1 && i <= rows && j <= cols && matrix[i - 1][j - 1] < Double.POSITIVE_INFINITY) assignment[i - 1] = j - 1;
        }
        return assignment;
    }
}
