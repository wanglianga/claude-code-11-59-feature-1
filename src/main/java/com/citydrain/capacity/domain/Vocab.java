package com.citydrain.capacity.domain;

/**
 * 开放词表常量：观测类型、目标类型、外部引用类型、覆盖账本条目、限制来源标记。
 * 使用 String 常量而非枚举，便于外部系统扩展取值。
 */
public final class Vocab {

    private Vocab() {}

    // 观测类型
    public static final String OBS_FLOW = "FLOW";                       // 流量
    public static final String OBS_WATER_LEVEL = "WATER_LEVEL";         // 水位（节点/管段）
    public static final String OBS_RAINFALL = "RAINFALL";               // 降雨
    public static final String OBS_DOWNSTREAM_LEVEL = "DOWNSTREAM_LEVEL"; // 下游边界水位
    public static final String OBS_GATE_STATE = "GATE_STATE";           // 闸门状态

    // 观测目标类型
    public static final String TARGET_SEGMENT = "SEGMENT";
    public static final String TARGET_NODE = "NODE";
    public static final String TARGET_GATE = "GATE";
    public static final String TARGET_BOUNDARY = "BOUNDARY";

    // 外部引用类型（只读接入）
    public static final String REF_DEFECT = "DEFECT_OBSERVATION";       // 缺陷观测
    public static final String REF_INSPECTION = "INSPECTION_COVERAGE";  // 检查覆盖
    public static final String REF_REPAIR = "REPAIR_ACTION";            // 维修作用范围/验收结果

    // 覆盖账本条目
    public static final String ITEM_PARAM_VERSION = "PARAM_VERSION";
    public static final String ITEM_PARAM_GEOMETRY = "PARAM_GEOMETRY";
    public static final String ITEM_PARAM_SLOPE = "PARAM_SLOPE";
    public static final String ITEM_PARAM_ROUGHNESS = "PARAM_ROUGHNESS";
    public static final String ITEM_PARAM_SILTATION = "PARAM_SILTATION";
    public static final String ITEM_OBS_DOWNSTREAM_LEVEL = "OBS_DOWNSTREAM_LEVEL";
    public static final String ITEM_OBS_GATE_STATE = "OBS_GATE_STATE";
    public static final String ITEM_OBS_FLOW = "OBS_FLOW";
    public static final String ITEM_CALIBRATION_SAMPLES = "CALIBRATION_SAMPLES";
    public static final String ITEM_EXT_DEFECT_REF = "EXT_DEFECT_REF";
    public static final String ITEM_EXT_INSPECTION_REF = "EXT_INSPECTION_COVERAGE_REF";

    // 覆盖账本状态
    public static final String ST_AVAILABLE = "AVAILABLE";
    public static final String ST_ASSUMED = "ASSUMED";
    public static final String ST_MISSING = "MISSING";
    public static final String ST_CONFLICTED = "CONFLICTED";

    // 限制来源标记（写入 capacity_result.limitations）
    public static final String LIM_ROUGHNESS_MISSING = "ROUGHNESS_MISSING";
    public static final String LIM_DOWNSTREAM_LEVEL_MISSING = "DOWNSTREAM_LEVEL_MISSING";
    public static final String LIM_SILTATION_MISSING = "SILTATION_MISSING";
    public static final String LIM_BACKWATER_DOMINATED = "BACKWATER_DOMINATED";
    public static final String LIM_GATE_CLOSED = "GATE_CLOSED";
    public static final String LIM_GATE_PARTIAL = "GATE_PARTIAL";
    public static final String LIM_CALIBRATION_INSUFFICIENT = "CALIBRATION_INSUFFICIENT";

    // 不可判定原因
    public static final String REASON_NO_PARAM_VERSION = "NO_PARAMETER_VERSION_AT_AS_OF";
    public static final String REASON_GEOMETRY_MISSING = "GEOMETRY_MISSING";
    public static final String REASON_SLOPE_MISSING = "SLOPE_MISSING";

    // ======================================================================
    // 缺陷形态演化（V2）
    // ======================================================================

    // 缺陷类型（开放词表：裂缝 / 变形 / 渗漏 ...）
    public static final String DEFECT_CRACK = "裂缝";
    public static final String DEFECT_DEFORMATION = "变形";
    public static final String DEFECT_LEAK = "渗漏";

    // 轨迹变化状态
    public static final String TRAJ_PERSISTENT = "PERSISTENT";               // 持续存在（无可支持的变化）
    public static final String TRAJ_POSSIBLY_EXPANDED = "POSSIBLY_EXPANDED"; // 可能扩展（变化超出测量边界）
    public static final String TRAJ_POSSIBLY_NEW = "POSSIBLY_NEW";           // 可能新发（前期有覆盖但无对应前身）
    public static final String TRAJ_POSSIBLY_DISAPPEARED = "POSSIBLY_DISAPPEARED"; // 可能消失（后续有覆盖但无后继）
    public static final String TRAJ_NOT_COMPARABLE = "NOT_COMPARABLE";       // 无法比较（缺覆盖/几何未映射/定位歧义）

    // 轨迹连续性
    public static final String CONT_CONTINUOUS = "CONTINUOUS";               // 相邻年份连续覆盖
    public static final String CONT_COVERAGE_GAP = "COVERAGE_GAP";           // 中间年份无覆盖，不可宣称持续劣化
    public static final String CONT_GEOMETRY_REMAPPED = "GEOMETRY_REMAPPED"; // 跨几何改造，经明确映射范围连接
    public static final String CONT_SINGLE = "SINGLE_OBSERVATION";           // 仅一期观测，无相邻连接

    // 不可比较原因
    public static final String NC_COVERAGE_GAP = "COVERAGE_GAP_BETWEEN_OBSERVATIONS";
    public static final String NC_NO_PRIOR_COVERAGE = "NO_PRIOR_COVERAGE_SCOPE";
    public static final String NC_GEOMETRY_NOT_MAPPED = "GEOMETRY_NOT_MAPPED";
    public static final String NC_POSITION_AMBIGUITY = "POSITION_UNCERTAINTY_AMBIGUOUS";
    public static final String NC_CONTESTED = "OBSERVATION_CONTESTED";

    // 变化判定（对原始判读的独立分析结论）
    public static final String CHANGE_INCREASE = "SUPPORTED_INCREASE";       // 变化超出测量边界，支持扩展
    public static final String CHANGE_DECREASE = "SUPPORTED_DECREASE";
    public static final String CHANGE_WITHIN_BOUNDARY = "WITHIN_MEASUREMENT_BOUNDARY"; // 测量边界内，不足以宣称变化
    public static final String CHANGE_INDETERMINATE = "INDETERMINATE";       // 不重叠/不确定

    // 轨迹连接路线
    public static final String LINK_RULE_BASED = "RULE_BASED";               // 相邻年份重叠+属性容差，逐段贪心
    public static final String LINK_GLOBAL_CONSTRAINT = "GLOBAL_CONSTRAINT"; // 多期整体约束，全局最优轨迹集合
}
