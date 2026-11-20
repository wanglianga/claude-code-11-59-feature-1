package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 几何版本映射：明确声明旧几何里程 → 新几何里程的换算范围。
 * 管段改造后，只有落在 from/to 范围内的观测才允许跨几何版本连接；
 * 未声明映射的改造视作不可比较，而不是用坐标直接相连。
 */
public record GeometryMapping(
        long id,
        long segmentId,
        String fromGeometryVersion,
        String toGeometryVersion,
        double fromStartM,
        double fromEndM,
        double toStartM,
        double toEndM,
        String note,
        OffsetDateTime registeredAt
) {}
