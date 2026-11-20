package com.citydrain.capacity.service.evolution;

import com.citydrain.capacity.domain.DefectObservation;

/**
 * 缺陷几何/区间纯函数（确定性、可逐项复核）。
 * 里程区间按定位不确定度扩张；长度/宽度为测量区间，变化判定以“测量边界”（两期检测分辨率的较大者）为准，
 * 区间重叠即视为测量边界内，不得据此宣称扩展。
 */
public final class DefectGeometryMath {

    private DefectGeometryMath() {}

    public static final double EPS = 1e-9;

    /** 一维闭区间。 */
    public record Interval(double lo, double hi) {
        public double span() {
            return hi - lo;
        }

        public boolean contains(double x) {
            return x >= lo - EPS && x <= hi + EPS;
        }
    }

    /** 观测沿管轴的里程区间 [start, end]。 */
    public static Interval chainage(DefectObservation o) {
        return new Interval(o.chainageStartM(), o.chainageEndM());
    }

    /** 里程区间按定位不确定度扩张（位置误差带）。 */
    public static Interval chainageExpanded(DefectObservation o) {
        return new Interval(o.chainageStartM() - o.positionUncertaintyM(),
                o.chainageEndM() + o.positionUncertaintyM());
    }

    /** 长度测量区间（米）。 */
    public static Interval lengthInterval(DefectObservation o) {
        return new Interval(o.lengthMinM(), o.lengthMaxM());
    }

    /** 宽度测量区间（毫米）；任一期缺测返回 null。 */
    public static Interval widthIntervalOrNull(DefectObservation a, DefectObservation b) {
        if (a.widthMinMm() == null || a.widthMaxMm() == null
                || b.widthMinMm() == null || b.widthMaxMm() == null) {
            return null;
        }
        // 返回 b 相对 a 的宽度差区间，用同一方法；此处仅用于可比较性
        return new Interval(b.widthMinMm() - a.widthMaxMm(), b.widthMaxMm() - a.widthMinMm());
    }

    public static double intersectionSpan(Interval a, Interval b) {
        double lo = Math.max(a.lo(), b.lo());
        double hi = Math.min(a.hi(), b.hi());
        return Math.max(0.0, hi - lo);
    }

    /**
     * 重叠比例 = 交集长度 / 较短区间长度。
     * 当一个区间被另一个包含时比例为 1；点区间（span≈0）落在对方内记 1，否则 0。
     */
    public static double overlapRatio(Interval a, Interval b) {
        double inter = intersectionSpan(a, b);
        double minSpan = Math.min(a.span(), b.span());
        if (minSpan <= EPS) {
            // 退化区间（近似点）
            boolean aPoint = a.span() <= EPS;
            boolean bPoint = b.span() <= EPS;
            if (aPoint && bPoint) {
                return Math.abs(a.lo() - b.lo()) <= EPS ? 1.0 : 0.0;
            }
            if (aPoint) {
                return b.contains(a.lo()) ? 1.0 : 0.0;
            }
            return a.contains(b.lo()) ? 1.0 : 0.0;
        }
        return Math.min(1.0, inter / minSpan);
    }

    /** 区间差 a→b：b 相对 a 的变化区间 [bLo - aHi, bHi - aLo]。 */
    public static Interval change(Interval a, Interval b) {
        return new Interval(b.lo() - a.hi(), b.hi() - a.lo());
    }

    /** 两期测量边界：检测分辨率（毫米→米）的较大者。 */
    public static double measurementBoundaryM(DefectObservation a, DefectObservation b) {
        return Math.max(a.detectionResolutionMm(), b.detectionResolutionMm()) / 1000.0;
    }

    /**
     * 变化判定类别（见 {@code Vocab.CHANGE_*}），以测量边界 boundary 为准：
     * - 变化区间整体位于 +boundary 之上 → 支持扩展；
     * - 变化区间整体位于 -boundary 之下 → 支持收缩；
     * - 变化区间整体落在 [-boundary, +boundary] 内 → 测量边界内，不足以宣称变化；
     * - 其余（横跨且幅度超出边界）→ 方向不确定。
     */
    public static String classifyChange(Interval chg, double boundaryM) {
        if (chg.lo() > boundaryM + EPS) {
            return com.citydrain.capacity.domain.Vocab.CHANGE_INCREASE;
        }
        if (chg.hi() < -boundaryM - EPS) {
            return com.citydrain.capacity.domain.Vocab.CHANGE_DECREASE;
        }
        if (chg.lo() >= -boundaryM - EPS && chg.hi() <= boundaryM + EPS) {
            return com.citydrain.capacity.domain.Vocab.CHANGE_WITHIN_BOUNDARY;
        }
        return com.citydrain.capacity.domain.Vocab.CHANGE_INDETERMINATE;
    }

    /** 区间之间的最小间隙（重叠时 0）。用于连接长度合理性判断。 */
    public static double gap(Interval a, Interval b) {
        if (a.hi() < b.lo()) {
            return b.lo() - a.hi();
        }
        if (b.hi() < a.lo()) {
            return a.lo() - b.hi();
        }
        return 0.0;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
