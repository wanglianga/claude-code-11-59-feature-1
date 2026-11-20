package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 缺陷观测：一条已确认缺陷在某次巡检中的一次观察。
 * 只读原始判读：保存裂缝起止里程、长度/宽度区间、检测分辨率、定位不确定度、观察日期与几何版本。
 * 演化运行在其上追加分析，不重写本条记录。
 */
public record DefectObservation(
        long id,
        long segmentId,
        String geometryVersion,
        String defectCode,
        String defectType,
        OffsetDateTime observedAt,
        double chainageStartM,
        double chainageEndM,
        double lengthMinM,
        double lengthMaxM,
        Double widthMinMm,
        Double widthMaxMm,
        double detectionResolutionMm,
        double positionUncertaintyM,
        String sourceSystem,
        String sourceVersion,
        Long externalRefId,
        OffsetDateTime registeredAt
) {
    /** 观测里程区间长度（起止里程跨度）。 */
    public double chainageSpanM() {
        return chainageEndM - chainageStartM;
    }

    /** 覆盖里程范围在给定定位不确定度下的不确定扩张半宽：取定位不确定度本身（米）。 */
    public double positionHalfWidthM() {
        return positionUncertaintyM;
    }
}
