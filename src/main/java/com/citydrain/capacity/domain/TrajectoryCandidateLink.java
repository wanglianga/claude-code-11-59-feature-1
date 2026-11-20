package com.citydrain.capacity.domain;

/** 轨迹候选连接：每条可行连接（采用或未采用）均可回查，便于工程师复核规则与整体约束求解。 */
public record TrajectoryCandidateLink(
        long id,
        long runId,
        long fromObservationId,
        long toObservationId,
        double score,
        Double overlapRatio,
        boolean accepted,
        String reason
) {}
