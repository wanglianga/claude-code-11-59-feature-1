package com.citydrain.capacity.service;

import com.citydrain.capacity.config.DefectEvolutionProps;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.InspectionCoverage;
import com.citydrain.capacity.domain.Vocab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线选择建议：根据巡检密度、定位误差与是否需要全段一致的演化结果，说明两条路线各自的适用条件。
 * 这是选择依据说明，不是“规则失败后用整体约束补救”——两条路线都可独立适用于全段。
 */
public final class RouteGuidance {

    private RouteGuidance() {}

    public static Map<String, Object> describe(List<DefectObservation> observations,
                                               List<InspectionCoverage> coverage,
                                               DefectEvolutionProps props) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("observationCount", observations.size());
        g.put("periodCount", observations.stream().map(o -> o.observedAt().getYear()).distinct().count());
        g.put("coveragePeriodCount", coverage.stream().map(InspectionCoverage::inspectionYear)
                .distinct().count());

        double maxPosUnc = observations.stream().mapToDouble(DefectObservation::positionUncertaintyM)
                .max().orElse(0.0);
        double avgPosUnc = observations.stream().mapToDouble(DefectObservation::positionUncertaintyM)
                .average().orElse(0.0);
        g.put("maxPositionUncertaintyM", round3(maxPosUnc));
        g.put("avgPositionUncertaintyM", round3(avgPosUnc));

        // 估计高密度：同一观察期内相邻观测里程间距小于定位误差的 2 倍 → 存在拥挤隐患
        long crowdedPeriods = observations.stream().map(o -> o.observedAt().getYear()).distinct()
                .filter(y -> isCrowded(observations, y)).count();
        g.put("crowdedPeriods", crowdedPeriods);

        List<String> ruleFavors = new ArrayList<>();
        List<String> globalFavors = new ArrayList<>();
        ruleFavors.add("巡检密度适中（同一年内缺陷里程间距大于定位误差）时，重叠+容差规则稳定、可逐步复核");
        ruleFavors.add("定位误差相对里程间距较小、缺陷数量有限时优先规则匹配");
        globalFavors.add("高密度裂缝区（同一年内相邻缺陷里程间距小于定位误差量级）时，整体求解减少局部贪心错连");
        globalFavors.add("需要全段一致的演化结果、或有明显定位误差时，用整体约束一次求解整段轨迹集合");
        if (crowdedPeriods > 0) {
            globalFavors.add("检测到 " + crowdedPeriods + " 个拥挤观察期，整体约束更能减少错连");
            ruleFavors.add("拥挤观察期存在多条候选，规则结果应结合候选连接人工复核");
        }
        if (maxPosUnc > 2.0) {
            globalFavors.add("定位不确定度最大 " + round3(maxPosUnc) + "m 偏大，建议整体约束并保留歧义");
        }
        g.put("ruleBasedFavoredWhen", ruleFavors);
        g.put("globalConstraintFavoredWhen", globalFavors);
        g.put("choiceBasis",
                "按巡检密度、定位误差、是否需要全段一致的演化结果选择；不能把整体约束只当作规则匹配失败后的补救——"
                        + "两条路线各自独立适用全段，均满足时间顺序与空间连续性。");
        g.put("recommendedMethod",
                crowdedPeriods > 0 || maxPosUnc > 2.0
                        ? Vocab.LINK_GLOBAL_CONSTRAINT : Vocab.LINK_RULE_BASED);
        return g;
    }

    /** 某观察期内相邻观测里程间距是否小于等于 2×定位误差（拥挤/可能错连）。 */
    private static boolean isCrowded(List<DefectObservation> observations, int year) {
        List<DefectObservation> inYear = observations.stream()
                .filter(o -> o.observedAt().getYear() == year)
                .sorted((a, b) -> Double.compare(a.chainageStartM(), b.chainageStartM()))
                .toList();
        for (int i = 0; i + 1 < inYear.size(); i++) {
            DefectObservation a = inYear.get(i);
            DefectObservation b = inYear.get(i + 1);
            double gap = b.chainageStartM() - a.chainageEndM();
            double unc = Math.max(a.positionUncertaintyM(), b.positionUncertaintyM());
            if (gap <= 2.0 * unc + 1e-9) {
                return true;
            }
        }
        return false;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
