package com.citydrain.capacity.domain;

/** 缺陷轨迹连接路线。两路线相互独立，选择依据巡检密度、定位误差与是否需要全段一致的演化结果。 */
public enum LinkMethod {
    /** 相邻年份观测区间重叠比例 + 属性容差，逐段连接（规则透明、易复核，但在高密度区产生多候选）。 */
    RULE_BASED,
    /** 多期观测与位置误差作为整体约束，寻找满足时间顺序+空间连续性的全局轨迹集合（减少局部错连，但有求解/解释成本）。 */
    GLOBAL_CONSTRAINT
}
