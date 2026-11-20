package com.citydrain.capacity.service.evolution;

import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.GeometryMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 几何版本映射工具：把旧几何里程线性换算到新几何里程。
 * 仅当观测里程完整落在映射声明的 from 范围内，且目标映射到 to 范围内时，才允许跨几何连接；
 * 否则视为“几何未映射”，不得直接按坐标相连（也不得据此宣称消失/新发）。
 */
public final class GeometryVersionMath {

    private GeometryVersionMath() {}

    /**
     * 将观测 a 的里程区间从其几何版本换算到观测 b 的几何版本。
     * @return 换算后的里程区间；若几何版本相同返回原区间；若无覆盖映射返回 null。
     */
    public static DefectGeometryMath.Interval mapChainage(DefectObservation a, DefectObservation b,
                                                          List<GeometryMapping> mappings) {
        if (a.geometryVersion().equals(b.geometryVersion())) {
            return DefectGeometryMath.chainageExpanded(a);
        }
        for (GeometryMapping m : mappings) {
            if (!m.fromGeometryVersion().equals(a.geometryVersion())
                    || !m.toGeometryVersion().equals(b.geometryVersion())) {
                continue;
            }
            // 观测（含定位误差带）必须完整落在 from 范围内
            double aLo = a.chainageStartM() - a.positionUncertaintyM();
            double aHi = a.chainageEndM() + a.positionUncertaintyM();
            if (aLo < m.fromStartM() - DefectGeometryMath.EPS
                    || aHi > m.fromEndM() + DefectGeometryMath.EPS) {
                continue;
            }
            double fromSpan = m.fromEndM() - m.fromStartM();
            double toSpan = m.toEndM() - m.toStartM();
            if (fromSpan <= DefectGeometryMath.EPS) {
                continue;
            }
            double scale = toSpan / fromSpan;
            double lo = m.toStartM() + (aLo - m.fromStartM()) * scale;
            double hi = m.toStartM() + (aHi - m.fromStartM()) * scale;
            return new DefectGeometryMath.Interval(lo, hi);
        }
        return null;
    }

    /** 两观测之间是否存在“明确映射”，使旧几何可连接到新几何。 */
    public static boolean mapped(DefectObservation a, DefectObservation b, List<GeometryMapping> mappings) {
        return mapChainage(a, b, mappings) != null;
    }

    public static List<DefectObservation> distinctGeometries(List<DefectObservation> obs) {
        List<DefectObservation> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (DefectObservation o : obs) {
            if (!seen.contains(o.geometryVersion())) {
                seen.add(o.geometryVersion());
                out.add(o);
            }
        }
        return out;
    }
}
