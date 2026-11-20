package com.citydrain.capacity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缺陷形态演化推导参数。全部参与连接与变化判定，写入轨迹证据供工程师复核。
 * 路线A（规则）与路线B（整体约束）共用同一套候选边与容差，仅在“如何挑选轨迹集合”上不同。
 */
@ConfigurationProperties(prefix = "app.defect-evolution")
public record DefectEvolutionProps(
        /** 候选连接的最小里程区间重叠比例（按定位不确定度扩张后的区间计算）。 */
        double overlapMinRatio,
        /**
         * 长度量级合理性上限：两期长度区间跨度的比值（大/小）超过此值视为“量级差异过大”，
         * 不可能是同一裂缝（拒绝连接）。注意这只是量级护栏——裂缝扩展会使长度区间不重叠，
         * 因此长度“接近程度”只用于打分排序，不作硬门槛，避免把真实扩展误切断。
         */
        double lengthSpanRatioMax,
        /** 次优候选与最优候选的分差低于此值 → 保留歧义（不双计）。 */
        double ambiguityScoreMargin,
        /** 轨迹级：扩张/收缩判定额外要求的连续覆盖年数（0=只需相邻观测）。 */
        int minContinuousYears
) {}
