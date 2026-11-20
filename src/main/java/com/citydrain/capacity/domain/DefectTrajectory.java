package com.citydrain.capacity.domain;

/**
 * 缺陷轨迹：由多期观测连接而成的一条演化链。
 * 只引用原始观测 id；状态、比较明细、独立变化范围与歧义信息均为追加分析，不重写原始判读。
 */
public record DefectTrajectory(
        long id,
        long runId,
        String trajectoryCode,
        String status,
        String continuity,
        String observationIdsJson,
        String comparisonsJson,
        String changeRangeJson,
        String ambiguityJson,
        String evidenceJson
) {}
