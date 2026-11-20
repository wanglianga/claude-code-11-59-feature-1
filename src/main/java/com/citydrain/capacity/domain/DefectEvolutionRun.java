package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/** 缺陷形态演化运行：管段 + 连接路线 + as_of。同 (segment, method, as_of) 幂等。 */
public record DefectEvolutionRun(
        long id,
        String runCode,
        long segmentId,
        String method,
        OffsetDateTime asOf,
        String status,
        String summaryJson,
        OffsetDateTime createdAt
) {}
