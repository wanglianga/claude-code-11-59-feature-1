package com.citydrain.capacity.service.evolution;

import java.util.Arrays;

/**
 * 最大权二分匹配（匈牙利算法 / Jonker-Volgenant 简化版，O(n^3)）。
 * 用于路线B“整体约束”：在每个相邻观测期对上求解一对一指派的最优轨迹集合，
 * 而非逐个观测贪心挑选——从而减少局部贪心在高密度裂缝区产生的错连。
 * 输入 weight[i][j]（行=前期观测，列=后期观测，越小/不等长也可）；返回匹配对。
 */
public final class HungarianMatcher {

    private HungarianMatcher() {}

    public record Pair(int row, int col) {}

    /**
     * @param weight 非负权重矩阵，行数≤列数或反之均可（内部转为方阵补零）
     * @return 最大权匹配的行列对（每个行/列至多使用一次）
     */
    public static java.util.List<Pair> maxWeightMatching(double[][] weight) {
        int rows = weight.length;
        if (rows == 0) {
            return java.util.List.of();
        }
        int cols = weight[0].length;
        if (cols == 0) {
            return java.util.List.of();
        }
        int n = Math.max(rows, cols);
        // 构造方阵：补零（补零不会贡献正值，等价于允许不匹配）
        double[][] a = new double[n][n];
        for (double[] row : a) {
            Arrays.fill(row, 0.0);
        }
        for (int i = 0; i < rows; i++) {
            System.arraycopy(weight[i], 0, a[i], 0, cols);
        }

        // 最小化 -a 的指派（经典 O(n^3) 势函数实现，1-indexed）
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            boolean[] used = new boolean[n + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        double cur = -a[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        java.util.List<Pair> matches = new java.util.ArrayList<>();
        for (int j = 1; j <= n; j++) {
            int i = p[j];
            if (i >= 1 && i <= rows && j <= cols && weight[i - 1][j - 1] > 0) {
                matches.add(new Pair(i - 1, j - 1));
            }
        }
        return matches;
    }
}
