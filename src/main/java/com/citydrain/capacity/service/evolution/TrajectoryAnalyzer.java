package com.citydrain.capacity.service.evolution;

import com.citydrain.capacity.config.DefectEvolutionProps;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.GeometryMapping;
import com.citydrain.capacity.domain.InspectionCoverage;
import com.citydrain.capacity.domain.Vocab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轨迹分析：把一条已连接的观测链解释为变化状态、连续性、独立变化范围与歧义。
 *
 * 关键纪律：
 * - 中间年份无覆盖 → 不宣称持续劣化（连续=CONTINUOUS 或 COVERAGE_GAP）；
 * - 管段改造后旧新几何仅在明确映射范围内可连接（映射由候选生成保证）；
 * - 同一观测被两条轨迹竞争 → 保留歧义、不双计（每条轨迹各自独立，竞争只记录不复制）；
 * - 变化判定以“测量边界”（两期检测分辨率较大者）为准，两期定位误差/分辨率不同也不强行排成折线。
 */
public final class TrajectoryAnalyzer {

    private TrajectoryAnalyzer() {}

    /** 相邻两期比较。 */
    public record Comparison(
            long fromId, long toId, int fromYear, int toYear,
            double overlapRatio, boolean geometryRemapped,
            double[] lengthChangeM, double[] widthChangeMm,
            double measurementBoundaryM, String verdict, String note) {
    }

    /** 独立变化范围分析结果（供外部整治排序引用；不改变原始判读）。 */
    public record ChangeRange(
            Long fromId, Long toId, int spanYears,
            double[] lengthChangeRangeM, double[] supportedChangeBeyondBoundaryM,
            String direction, boolean comparable, String note) {
    }

    /** 歧义/竞争信息。 */
    public record Ambiguity(boolean contested, List<Map<String, Object>> competitors, String note) {
    }

    /** 单条轨迹的分析结果（service 负责序列化并持久化）。 */
    public record TrajectoryResult(
            String trajectoryCode, String status, String continuity,
            List<Long> observationIds, List<Comparison> comparisons,
            ChangeRange changeRange, Ambiguity ambiguity, Map<String, Object> evidence) {
    }

    /** 输入上下文。 */
    public record Context(List<DefectObservation> observations, List<InspectionCoverage> coverage,
                          List<GeometryMapping> mappings, List<CandidateLinker.Edge> allCandidates,
                          Set<String> acceptedPairKeys, DefectEvolutionProps props) {
    }

    /**
     * 分析一条链。
     * @param chain 该轨迹的观测 id（按时间升序）
     * @param index 全局序号（用于生成 trajectoryCode）
     */
    public static TrajectoryResult analyze(List<Long> chain, int index, Context ctx) {
        Map<Long, DefectObservation> byId = new LinkedHashMap<>();
        ctx.observations().forEach(o -> byId.put(o.id(), o));
        List<DefectObservation> obs = chain.stream().map(byId::get).toList();
        String code = "T-" + String.format("%03d", index);

        // 1) 相邻比较
        List<Comparison> comparisons = new ArrayList<>();
        boolean anySupportedIncrease = false;
        boolean anyCoverageGap = false;
        boolean anyRemapped = false;
        for (int i = 0; i + 1 < obs.size(); i++) {
            DefectObservation a = obs.get(i);
            DefectObservation b = obs.get(i + 1);
            boolean remapped = !a.geometryVersion().equals(b.geometryVersion());
            anyRemapped |= remapped;
            boolean gap = hasCoverageGap(a, b, ctx);
            anyCoverageGap |= gap;

            double overlap = overlapFor(a, b, ctx);
            double[] lengthDelta = {
                    DefectGeometryMath.round4(b.lengthMinM() - a.lengthMaxM()),
                    DefectGeometryMath.round4(b.lengthMaxM() - a.lengthMinM())};
            double[] widthDelta = widthDeltaOrNull(a, b);
            double boundary = DefectGeometryMath.measurementBoundaryM(a, b);
            DefectGeometryMath.Interval chg = new DefectGeometryMath.Interval(lengthDelta[0], lengthDelta[1]);
            String verdict;
            String note;
            if (gap) {
                verdict = Vocab.CHANGE_INDETERMINATE;
                note = "中间年份无覆盖（" + missingYears(a, b, ctx) + "），不能宣称持续变化";
            } else {
                verdict = DefectGeometryMath.classifyChange(chg, boundary);
                note = switch (verdict) {
                    case Vocab.CHANGE_INCREASE -> "长度变化区间下界 " + lengthDelta[0]
                            + "m 超出测量边界 " + boundary + "m，支持扩展";
                    case Vocab.CHANGE_DECREASE -> "长度变化区间上界 " + lengthDelta[1]
                            + "m 低于 -测量边界 " + boundary + "m，支持收缩";
                    default -> "长度变化区间 [" + lengthDelta[0] + ", " + lengthDelta[1]
                            + "]m 落在测量边界 ±" + boundary + "m 内，不足以宣称变化";
                };
            }
            if (Vocab.CHANGE_INCREASE.equals(verdict)) {
                anySupportedIncrease = true;
            }
            comparisons.add(new Comparison(a.id(), b.id(), a.observedAt().getYear(),
                    b.observedAt().getYear(), DefectGeometryMath.round4(overlap), remapped,
                    lengthDelta, widthDelta, DefectGeometryMath.round4(boundary), verdict, note));
        }

        // 2) 连续性
        String continuity = obs.size() < 2 ? Vocab.CONT_SINGLE
                : anyRemapped ? Vocab.CONT_GEOMETRY_REMAPPED
                : anyCoverageGap ? Vocab.CONT_COVERAGE_GAP : Vocab.CONT_CONTINUOUS;

        // 3) 歧义：本链任一观测是否有分差在容差内的其它可行候选
        Ambiguity ambiguity = detectAmbiguity(chain, ctx);

        // 4) 状态
        String status;
        String notComparableReason = null;
        if (ambiguity.contested()) {
            status = Vocab.TRAJ_NOT_COMPARABLE;
            notComparableReason = Vocab.NC_CONTESTED;
        } else if (obs.size() == 1) {
            DefectObservation only = obs.get(0);
            boolean priorSameGeo = !coverageYearsBefore(only, ctx).isEmpty();
            boolean laterSameGeo = !coverageYearsAfter(only, ctx).isEmpty();
            boolean priorAnyGeo = hasCoverageBeforeAnyGeometry(only, ctx);
            boolean laterAnyGeo = hasCoverageAfterAnyGeometry(only, ctx);
            if (priorSameGeo) {
                status = Vocab.TRAJ_POSSIBLY_NEW;
            } else if (laterSameGeo) {
                status = Vocab.TRAJ_POSSIBLY_DISAPPEARED;
            } else if (priorAnyGeo || laterAnyGeo) {
                // 其它几何版本有覆盖但无明确映射 → 不可比较（不能据此宣称新发/消失）
                status = Vocab.TRAJ_NOT_COMPARABLE;
                notComparableReason = Vocab.NC_GEOMETRY_NOT_MAPPED;
            } else {
                status = Vocab.TRAJ_NOT_COMPARABLE;
                notComparableReason = priorAnyGeo || laterAnyGeo
                        ? Vocab.NC_COVERAGE_GAP : Vocab.NC_NO_PRIOR_COVERAGE;
            }
        } else if (anyCoverageGap) {
            status = Vocab.TRAJ_NOT_COMPARABLE;
            notComparableReason = Vocab.NC_COVERAGE_GAP;
        } else if (anySupportedIncrease) {
            status = Vocab.TRAJ_POSSIBLY_EXPANDED;
        } else {
            status = Vocab.TRAJ_PERSISTENT;
        }

        // 5) 独立变化范围（首末可比观测；不可比时仍给出端点比较但标 comparable=false）
        ChangeRange changeRange = buildChangeRange(obs, comparisons, continuity,
                status.equals(Vocab.TRAJ_NOT_COMPARABLE));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("method", "trajectory-analysis");
        evidence.put("observationCount", obs.size());
        evidence.put("acceptedLinks", acceptedLinksOf(chain, ctx));
        evidence.put("overlapMinRatio", ctx.props().overlapMinRatio());
        evidence.put("lengthSpanRatioMax", ctx.props().lengthSpanRatioMax());
        evidence.put("measurementBoundaryNote",
                "变化判定以两期检测分辨率较大者为测量边界；定位不确定度用于连接而非变化幅度");
        if (notComparableReason != null) {
            evidence.put("notComparableReason", notComparableReason);
        }
        if (obs.size() == 1) {
            evidence.put("priorCoverage", coverageYearsBefore(obs.get(0), ctx));
            evidence.put("laterCoverage", coverageYearsAfter(obs.get(0), ctx));
        }
        return new TrajectoryResult(code, status, continuity, chain, comparisons,
                changeRange, ambiguity, evidence);
    }

    // ------------------------------------------------------------------ 歧义

    private static Ambiguity detectAmbiguity(List<Long> chain, Context ctx) {
        List<Map<String, Object>> competitors = new ArrayList<>();
        boolean contested = false;
        // 仅检查“本链实际采用的相邻连接”，寻找同一端点、分差在容差内的竞争可行边
        for (int i = 0; i + 1 < chain.size(); i++) {
            long from = chain.get(i);
            long to = chain.get(i + 1);
            CandidateLinker.Edge accepted = findEdge(from, to, ctx);
            if (accepted == null) {
                continue;
            }
            for (CandidateLinker.Edge other : ctx.allCandidates()) {
                if (!other.feasible() || other == accepted) {
                    continue;
                }
                if (!(other.fromId() == from && other.toId() != to)
                        && !(other.toId() == to && other.fromId() != from)) {
                    continue;
                }
                if (Math.abs(other.score() - accepted.score())
                        <= ctx.props().ambiguityScoreMargin() + DefectGeometryMath.EPS) {
                    contested = true;
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("observationId", other.fromId() == from ? from : to);
                    c.put("role", other.fromId() == from ? "FROM" : "TO");
                    c.put("chosenLink", from + "->" + to);
                    c.put("competingLink", other.fromId() + "->" + other.toId());
                    c.put("chosenScore", accepted.score());
                    c.put("competingScore", other.score());
                    competitors.add(c);
                }
            }
        }
        String note = contested
                ? "存在分差在容差内的竞争连接：保留不确定性，观测只计入本轨迹一次（不双计）"
                : "无竞争连接";
        return new Ambiguity(contested, competitors, note);
    }

    private static CandidateLinker.Edge findEdge(long from, long to, Context ctx) {
        for (CandidateLinker.Edge e : ctx.allCandidates()) {
            if (e.feasible() && e.fromId() == from && e.toId() == to) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 单观测状态

    /** 在某观测之前（其它几何版本、同一里程位置）是否有覆盖（用于几何未映射提示）。 */
    private static boolean hasCoverageBeforeAnyGeometry(DefectObservation o, Context ctx) {
        for (InspectionCoverage c : ctx.coverage()) {
            if (c.inspectionYear() < o.observedAt().getYear()
                    && !c.geometryVersion().equals(o.geometryVersion())
                    && overlapsStation(c, o)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCoverageAfterAnyGeometry(DefectObservation o, Context ctx) {
        for (InspectionCoverage c : ctx.coverage()) {
            if (c.inspectionYear() > o.observedAt().getYear()
                    && !c.geometryVersion().equals(o.geometryVersion())
                    && overlapsStation(c, o)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 覆盖判定

    /** 相邻观测之间若跨年且中间年份在该位置无覆盖 → 覆盖缺口（跨几何同样要求中间年份有覆盖证据）。 */
    private static boolean hasCoverageGap(DefectObservation a, DefectObservation b, Context ctx) {
        int y1 = a.observedAt().getYear();
        int y2 = b.observedAt().getYear();
        String laterGeo = b.geometryVersion();
        for (int y = y1 + 1; y < y2; y++) {
            if (!coveredInYearEitherGeometry(a, b, y, laterGeo, ctx)) {
                return true;
            }
        }
        return false;
    }

    private static String missingYears(DefectObservation a, DefectObservation b, Context ctx) {
        List<Integer> missing = new ArrayList<>();
        String laterGeo = b.geometryVersion();
        for (int y = a.observedAt().getYear() + 1; y < b.observedAt().getYear(); y++) {
            if (!coveredInYearEitherGeometry(a, b, y, laterGeo, ctx)) {
                missing.add(y);
            }
        }
        return missing.toString();
    }

    /**
     * 该年在观测里程（含定位误差，取两观测并集）范围内是否在“任一相关几何版本”下有覆盖。
     * 同几何：只看该几何版本；跨几何：旧/新几何版本任一有覆盖即可（改造后覆盖通常登记在新几何）。
     */
    private static boolean coveredInYearEitherGeometry(DefectObservation a, DefectObservation b,
                                                       int year, String laterGeo, Context ctx) {
        double lo = Math.min(a.chainageStartM(), b.chainageStartM()) - a.positionUncertaintyM();
        double hi = Math.max(a.chainageEndM(), b.chainageEndM()) + a.positionUncertaintyM();
        for (InspectionCoverage c : ctx.coverage()) {
            if (c.inspectionYear() != year) {
                continue;
            }
            boolean geoOk = c.geometryVersion().equals(laterGeo)
                    || c.geometryVersion().equals(a.geometryVersion());
            if (!geoOk) {
                continue;
            }
            if (c.coveredEndM() >= lo - DefectGeometryMath.EPS
                    && c.coveredStartM() <= hi + DefectGeometryMath.EPS) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> coverageYearsBefore(DefectObservation o, Context ctx) {
        List<Integer> years = new ArrayList<>();
        for (InspectionCoverage c : ctx.coverage()) {
            if (c.inspectionYear() < o.observedAt().getYear()
                    && c.geometryVersion().equals(o.geometryVersion())
                    && overlapsStation(c, o)) {
                if (!years.contains(c.inspectionYear())) {
                    years.add(c.inspectionYear());
                }
            }
        }
        years.sort(Integer::compareTo);
        return years;
    }

    private static List<Integer> coverageYearsAfter(DefectObservation o, Context ctx) {
        List<Integer> years = new ArrayList<>();
        for (InspectionCoverage c : ctx.coverage()) {
            if (c.inspectionYear() > o.observedAt().getYear()
                    && c.geometryVersion().equals(o.geometryVersion())
                    && overlapsStation(c, o)) {
                if (!years.contains(c.inspectionYear())) {
                    years.add(c.inspectionYear());
                }
            }
        }
        years.sort(Integer::compareTo);
        return years;
    }

    private static boolean overlapsStation(InspectionCoverage c, DefectObservation o) {
        double lo = o.chainageStartM() - o.positionUncertaintyM();
        double hi = o.chainageEndM() + o.positionUncertaintyM();
        return c.coveredEndM() >= lo - DefectGeometryMath.EPS
                && c.coveredStartM() <= hi + DefectGeometryMath.EPS;
    }

    // ------------------------------------------------------------ 变化范围

    private static ChangeRange buildChangeRange(List<DefectObservation> obs,
                                                List<Comparison> comparisons,
                                                String continuity, boolean notComparable) {
        if (obs.size() < 2) {
            return new ChangeRange(null, null, 0, new double[]{0, 0}, new double[]{0, 0},
                    Vocab.CHANGE_INDETERMINATE, false,
                    "单期观测，无相邻可比观测；不生成变化结论");
        }
        DefectObservation first = obs.get(0);
        DefectObservation last = obs.get(obs.size() - 1);
        double lo = last.lengthMinM() - first.lengthMaxM();
        double hi = last.lengthMaxM() - first.lengthMinM();
        // 端点测量边界：取首末两期检测分辨率较大者
        double boundary = DefectGeometryMath.measurementBoundaryM(first, last);
        double[] raw = {DefectGeometryMath.round4(lo), DefectGeometryMath.round4(hi)};
        double[] beyond = {DefectGeometryMath.round4(lo - boundary),
                DefectGeometryMath.round4(hi - boundary)};
        boolean comparable = !notComparable && !continuity.equals(Vocab.CONT_COVERAGE_GAP);
        String direction;
        if (!comparable) {
            direction = Vocab.CHANGE_INDETERMINATE;
        } else {
            direction = DefectGeometryMath.classifyChange(
                    new DefectGeometryMath.Interval(lo, hi), boundary);
        }
        String note;
        if (continuity.equals(Vocab.CONT_COVERAGE_GAP)) {
            note = "中间年份无覆盖：端点变化仅供参考，不构成持续劣化结论";
        } else if (notComparable) {
            note = "存在定位歧义/竞争，变化方向保留为不确定";
        } else {
            note = "首末观测端到端变化；supportedChangeBeyondBoundary 已扣除测量边界 " + boundary + "m";
        }
        return new ChangeRange(first.id(), last.id(),
                last.observedAt().getYear() - first.observedAt().getYear(),
                raw, beyond, direction, comparable, note);
    }

    // ------------------------------------------------------------ 辅助

    private static double overlapFor(DefectObservation a, DefectObservation b, Context ctx) {
        DefectGeometryMath.Interval aChain = a.geometryVersion().equals(b.geometryVersion())
                ? DefectGeometryMath.chainageExpanded(a)
                : GeometryVersionMath.mapChainage(a, b, ctx.mappings());
        if (aChain == null) {
            return 0.0;
        }
        return DefectGeometryMath.overlapRatio(aChain, DefectGeometryMath.chainageExpanded(b));
    }

    private static double[] widthDeltaOrNull(DefectObservation a, DefectObservation b) {
        if (a.widthMinMm() == null || a.widthMaxMm() == null
                || b.widthMinMm() == null || b.widthMaxMm() == null) {
            return null;
        }
        return new double[]{
                DefectGeometryMath.round3(b.widthMinMm() - a.widthMaxMm()),
                DefectGeometryMath.round3(b.widthMaxMm() - a.widthMinMm())};
    }

    private static List<Map<String, Object>> acceptedLinksOf(List<Long> chain, Context ctx) {
        List<Map<String, Object>> links = new ArrayList<>();
        for (int i = 0; i + 1 < chain.size(); i++) {
            long from = chain.get(i);
            long to = chain.get(i + 1);
            for (CandidateLinker.Edge e : ctx.allCandidates()) {
                if (e.feasible() && e.fromId() == from && e.toId() == to) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", from);
                    m.put("to", to);
                    m.put("score", e.score());
                    m.put("overlapRatio", e.overlapRatio());
                    m.put("lengthSpanRatio", e.lengthSpanRatio());
                    m.put("geometryRemapped", e.geometryRemapped());
                    links.add(m);
                }
            }
        }
        return links;
    }
}
