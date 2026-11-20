package com.citydrain.capacity.service.evolution;

import com.citydrain.capacity.config.DefectEvolutionProps;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.GeometryMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选连接生成：两路线共用同一套“何为可行连接”的透明判据，仅在如何挑选集合上不同。
 *
 * 候选图是有向无环图（边恒由早期指向后期）。对每个可能的 (a→b) 逐项判定：
 * 1) 时间顺序：a 观察日期严格早于 b，且不在同年（一年一次巡检，同年不同缺陷不互连）。
 * 2) 几何：同几何版本直接用定位误差扩张的里程区间；跨版本需有明确映射且 a 完整落在映射范围内；
 *    无映射时若原始坐标可疑重叠，仍生成候选并标注“几何未映射”以便复核（结论为不可比较）。
 * 3) 空间：里程区间（含定位不确定度）重叠比例 ≥ overlapMinRatio。
 * 4) 属性：缺陷类型一致；长度区间跨度量级比 ≤ lengthSpanRatioMax（仅量级护栏，裂缝扩展允许长度区间不重叠）。
 * 5) 最近后继：仅保留 a 在时间上“下一个且空间重叠”的观测作为后继，避免跨期长跳跃与重复成环。
 *
 * 得分用于整体约束求解：空间重叠为主、长度量级接近与定位确定度为辅；跨几何略降权以示不确定。
 */
public final class CandidateLinker {

    private CandidateLinker() {}

    /** 一条候选连接（from 早于 to）。含诊断信息，未采用/被拒也保留供复核。 */
    public record Edge(long fromId, long toId, double score, double overlapRatio,
                       double lengthSpanRatio, boolean geometryRemapped, String rejectReason) {
        public boolean feasible() {
            return rejectReason == null;
        }
    }

    public static List<Edge> candidates(List<DefectObservation> observations,
                                        List<GeometryMapping> mappings,
                                        DefectEvolutionProps props) {
        List<DefectObservation> sorted = observations.stream()
                .sorted((x, y) -> {
                    int c = x.observedAt().compareTo(y.observedAt());
                    return c != 0 ? c : Long.compare(x.id(), y.id());
                }).toList();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            DefectObservation a = sorted.get(i);
            for (int j = 0; j < sorted.size(); j++) {
                if (i == j) {
                    continue;
                }
                DefectObservation b = sorted.get(j);
                if (!a.observedAt().isBefore(b.observedAt())
                        || a.observedAt().getYear() == b.observedAt().getYear()) {
                    continue;
                }
                // 仅对“空间上可疑重叠”的序对建模（无映射的跨几何用原始坐标判断可疑性）
                if (!spatiallyPlausible(a, b, mappings, props)) {
                    continue;
                }
                // 最近后继：中间若存在更早的空间重叠同类观测，则 a→b 不是最近后继
                if (hasNearerSuccessor(a, b, sorted, mappings, props)) {
                    continue;
                }
                edges.add(evaluate(a, b, mappings, props));
            }
        }
        return edges;
    }

    private static boolean spatiallyPlausible(DefectObservation a, DefectObservation b,
                                              List<GeometryMapping> mappings,
                                              DefectEvolutionProps props) {
        DefectGeometryMath.Interval aChain = coordinateFor(a, b, mappings);
        DefectGeometryMath.Interval bChain = DefectGeometryMath.chainageExpanded(b);
        return DefectGeometryMath.overlapRatio(aChain, bChain) + DefectGeometryMath.EPS
                >= props.overlapMinRatio();
    }

    private static boolean hasNearerSuccessor(DefectObservation a, DefectObservation b,
                                              List<DefectObservation> sorted,
                                              List<GeometryMapping> mappings,
                                              DefectEvolutionProps props) {
        for (DefectObservation c : sorted) {
            if (c.id() == a.id() || c.id() == b.id()) {
                continue;
            }
            if (!c.observedAt().isAfter(a.observedAt())
                    || !c.observedAt().isBefore(b.observedAt())) {
                continue;
            }
            if (!c.defectType().equals(a.defectType())) {
                continue;
            }
            DefectGeometryMath.Interval aChain = coordinateFor(a, c, mappings);
            if (DefectGeometryMath.overlapRatio(aChain, DefectGeometryMath.chainageExpanded(c))
                    + DefectGeometryMath.EPS >= props.overlapMinRatio()) {
                return true;
            }
        }
        return false;
    }

    /**
     * a 在 b 视角下的里程区间：同几何用扩张区间；跨几何优先用映射；
     * 无映射时退回原始扩张区间（仅用于“可疑重叠”判断，evaluate 会因无映射而拒绝）。
     */
    private static DefectGeometryMath.Interval coordinateFor(DefectObservation a, DefectObservation b,
                                                            List<GeometryMapping> mappings) {
        if (a.geometryVersion().equals(b.geometryVersion())) {
            return DefectGeometryMath.chainageExpanded(a);
        }
        DefectGeometryMath.Interval mapped = GeometryVersionMath.mapChainage(a, b, mappings);
        return mapped != null ? mapped : DefectGeometryMath.chainageExpanded(a);
    }

    public static Edge evaluate(DefectObservation a, DefectObservation b,
                                List<GeometryMapping> mappings, DefectEvolutionProps props) {
        if (!a.observedAt().isBefore(b.observedAt())) {
            return reject(a, b, "时间顺序不成立（要求前期观察日期严格早于后期）");
        }
        boolean remapped = !a.geometryVersion().equals(b.geometryVersion());
        DefectGeometryMath.Interval aChain;
        if (remapped) {
            aChain = GeometryVersionMath.mapChainage(a, b, mappings);
            if (aChain == null) {
                return new Edge(a.id(), b.id(), 0.0, 0.0, 1.0, true,
                        "几何版本 " + a.geometryVersion() + "→" + b.geometryVersion()
                                + " 无明确映射覆盖该观测，不可跨几何连接（不得据此宣称消失/新发）");
            }
        } else {
            aChain = DefectGeometryMath.chainageExpanded(a);
        }
        DefectGeometryMath.Interval bChain = DefectGeometryMath.chainageExpanded(b);

        double overlap = DefectGeometryMath.overlapRatio(aChain, bChain);
        if (overlap + DefectGeometryMath.EPS < props.overlapMinRatio()) {
            return new Edge(a.id(), b.id(), 0.0, DefectGeometryMath.round4(overlap), 1.0, remapped,
                    "空间重叠比例 " + DefectGeometryMath.round4(overlap)
                            + " < 阈值 " + props.overlapMinRatio());
        }
        if (!a.defectType().equals(b.defectType())) {
            return new Edge(a.id(), b.id(), 0.0, DefectGeometryMath.round4(overlap), 1.0, remapped,
                    "缺陷类型不同（" + a.defectType() + " vs " + b.defectType() + "）");
        }
        // 长度跨度量级比（大/小）；仅作量级护栏，不要求长度区间重叠（裂缝扩展会使其不重叠）
        double spanA = Math.max(DefectGeometryMath.EPS, DefectGeometryMath.lengthInterval(a).span());
        double spanB = Math.max(DefectGeometryMath.EPS, DefectGeometryMath.lengthInterval(b).span());
        double spanRatio = Math.max(spanA, spanB) / Math.min(spanA, spanB);
        if (spanRatio > props.lengthSpanRatioMax() + DefectGeometryMath.EPS) {
            return new Edge(a.id(), b.id(), 0.0, DefectGeometryMath.round4(overlap),
                    DefectGeometryMath.round4(spanRatio), remapped,
                    "长度量级差 " + DefectGeometryMath.round4(spanRatio)
                            + " 超出上限 " + props.lengthSpanRatioMax() + "（视为不同缺陷）");
        }
        double spanSim = Math.min(spanA, spanB) / Math.max(spanA, spanB);
        double posTerm = 1.0 / (1.0 + (a.positionUncertaintyM() + b.positionUncertaintyM()));
        double score = 0.6 * overlap + 0.25 * spanSim + 0.15 * posTerm;
        if (remapped) {
            score *= 0.9;
        }
        return new Edge(a.id(), b.id(), DefectGeometryMath.round4(score),
                DefectGeometryMath.round4(overlap), DefectGeometryMath.round4(spanRatio),
                remapped, null);
    }

    private static Edge reject(DefectObservation a, DefectObservation b, String reason) {
        return new Edge(a.id(), b.id(), 0.0, 0.0, 1.0, false, reason);
    }
}
