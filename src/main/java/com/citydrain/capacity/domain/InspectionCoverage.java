package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/** 检查覆盖：某巡检年份中，沿管段实际完成检测的里程范围。用于判断中间年份是否有覆盖。 */
public record InspectionCoverage(
        long id,
        long segmentId,
        String geometryVersion,
        int inspectionYear,
        double coveredStartM,
        double coveredEndM,
        OffsetDateTime inspectedAt,
        String sourceSystem,
        String sourceVersion,
        OffsetDateTime registeredAt
) {}
