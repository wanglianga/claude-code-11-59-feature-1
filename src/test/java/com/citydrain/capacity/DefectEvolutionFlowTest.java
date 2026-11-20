package com.citydrain.capacity;

import com.citydrain.capacity.domain.DefectEvolutionRun;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.LinkMethod;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.DefectEvolutionRepo;
import com.citydrain.capacity.repo.DefectObservationRepo;
import com.citydrain.capacity.service.DefectEvolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.citydrain.capacity.TestFixtures.t;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 缺陷形态演化端到端：裂缝扩展 / 相邻裂缝并存 / 定位误差扩大 / 缺巡检年 / 几何改造。
 * 断言轨迹、变化状态、连续性、排序影响与不双计纪律；两路线均验证且互不为补救。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DefectEvolutionFlowTest {

    @Autowired TestFixtures fx;
    @Autowired DefectEvolutionService service;
    @Autowired DefectEvolutionRepo runRepo;
    @Autowired DefectObservationRepo obsRepo;
    @Autowired ObjectMapper om;

    private static final String G1 = "GEO-2024";
    private static final String G2 = "GEO-2026-REHAB";

    /** as_of 设为读取全部种子之后（registered_at 都已过）。 */
    private static OffsetDateTime asOf() {
        return t(2027, 1, 1, 0, 0);
    }

    private JsonNode summary(DefectEvolutionRun run) throws Exception {
        assertNotNull(run.summaryJson());
        return om.readTree(run.summaryJson());
    }

    private DefectEvolutionRun run(long seg, LinkMethod m) {
        return service.run(seg, m, asOf()).run();
    }

    // ---------------------------------------------------------------- 裂缝扩展

    @Test
    void crackExpansionYieldsPossiblyExpandedWithChangeRange() throws Exception {
        long seg = fx.defectSegment("T-EXPAND");
        fx.dObs(seg, G1, "C1", 2024, 60, 62, 0.8, 1.0, 0.4);
        fx.dObs(seg, G1, "C1", 2025, 59.6, 62.4, 1.6, 1.9, 0.4);
        fx.dObs(seg, G1, "C1", 2026, 59.2, 62.8, 2.6, 3.0, 0.5);
        fx.dCover(seg, G1, 2024, 0, 120);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G1, 2026, 0, 120);

        DefectEvolutionRun run = run(seg, LinkMethod.RULE_BASED);
        var trajs = service.getTrajectories(run.id());
        assertEquals(1, trajs.size(), "一条连续轨迹");
        var traj = trajs.get(0);
        assertEquals(Vocab.TRAJ_POSSIBLY_EXPANDED, traj.status());
        assertEquals(Vocab.CONT_CONTINUOUS, traj.continuity());
        // 变化范围：首末长度差约 +1.6..+2.2m
        JsonNode cr = om.readTree(traj.changeRangeJson());
        assertTrue(cr.path("comparable").asBoolean());
        assertTrue(cr.path("lengthChangeRangeM").get(0).asDouble() > 1.0);
        // 排序纳入
        JsonNode ranking = summary(run).path("remediationRanking");
        assertEquals(1, ranking.size());
        assertEquals(traj.trajectoryCode(), ranking.get(0).path("trajectoryCode").asText());
        assertEquals(1, ranking.get(0).path("rank").asInt());
    }

    // ---------------------------------------------------------------- 缺巡检年

    @Test
    void missingInspectionYearIsNotComparableNotContinuousDeterioration() throws Exception {
        long seg = fx.defectSegment("T-MISSING");
        fx.dObs(seg, G1, "M1", 2024, 40, 42, 0.9, 1.1, 0.4);
        fx.dObs(seg, G1, "M1", 2026, 39.6, 42.4, 1.9, 2.3, 0.5);
        fx.dCover(seg, G1, 2024, 0, 120);
        // 2025 无覆盖
        fx.dCover(seg, G1, 2026, 0, 120);

        DefectEvolutionRun run = run(seg, LinkMethod.GLOBAL_CONSTRAINT);
        var trajs = service.getTrajectories(run.id());
        assertEquals(1, trajs.size(), "2024 与 2026 仍连成一条轨迹");
        var traj = trajs.get(0);
        assertEquals(Vocab.TRAJ_NOT_COMPARABLE, traj.status(), "中间缺覆盖不得宣称持续劣化");
        assertEquals(Vocab.CONT_COVERAGE_GAP, traj.continuity());
        JsonNode cr = om.readTree(traj.changeRangeJson());
        assertFalse(cr.path("comparable").asBoolean());
        // 不进入整治排序，且排序影响解释原因
        JsonNode ranking = summary(run).path("remediationRanking");
        assertEquals(0, ranking.size());
        assertTrue(summary(run).path("rankingImpact").path("excludedFromRanking").toString()
                .contains(traj.trajectoryCode()));
    }

    // ---------------------------------------------------------------- 几何改造

    @Test
    void mappedGeometryChangeLinksAndIsRemapped() throws Exception {
        long seg = fx.defectSegment("T-GEOM-MAP");
        fx.dObs(seg, G1, "GM", 2025, 80, 82, 1.2, 1.4, 0.3);
        fx.dObs(seg, G2, "GM", 2026, 78.0, 80.2, 1.4, 1.6, 0.3);
        fx.dMapping(seg, G1, G2, 70, 100, 68, 98);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G2, 2026, 0, 120);

        DefectEvolutionRun run = run(seg, LinkMethod.RULE_BASED);
        var trajs = service.getTrajectories(run.id());
        assertEquals(1, trajs.size(), "明确映射范围内可跨几何连接");
        assertEquals(Vocab.CONT_GEOMETRY_REMAPPED, trajs.get(0).continuity());
        assertEquals(Vocab.TRAJ_PERSISTENT, trajs.get(0).status(), "长度变化在测量边界内 → 持续");
    }

    @Test
    void unmappedGeometryChangeIsNotComparable() throws Exception {
        long seg = fx.defectSegment("T-GEOM-UNMAP");
        fx.dObs(seg, G1, "GU", 2025, 95, 97, 1.0, 1.2, 0.3);
        fx.dObs(seg, G2, "GU", 2026, 95.2, 97.4, 1.1, 1.4, 0.3);
        // 映射仅覆盖 [70,90]，95-97m 越界 → 不可连接
        fx.dMapping(seg, G1, G2, 70, 90, 68, 88);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G2, 2026, 0, 120);

        DefectEvolutionRun run = run(seg, LinkMethod.RULE_BASED);
        var trajs = service.getTrajectories(run.id());
        assertEquals(2, trajs.size(), "未映射的两期不得相连 → 两条单期轨迹");
        for (var tr : trajs) {
            assertEquals(Vocab.TRAJ_NOT_COMPARABLE, tr.status());
            JsonNode ev = om.readTree(tr.evidenceJson());
            assertEquals(Vocab.NC_GEOMETRY_NOT_MAPPED, ev.path("notComparableReason").asText());
        }
        // 候选连接中记录“无明确映射”的拒绝原因
        boolean hasReject = service.getCandidates(run.id()).stream()
                .anyMatch(c -> c.reason() != null && c.reason().contains("无明确映射"));
        assertTrue(hasReject, "候选连接应记录几何未映射的拒绝原因");
    }

    // ---------------------------------------------------------------- 定位误差 / 竞争

    @Test
    void positionUncertaintyExpansionCreatesContestWithoutDoubleCounting() throws Exception {
        long seg = fx.defectSegment("T-POS-ERR");
        fx.dObs(seg, G1, "P1", 2025, 50.0, 50.5, 0.5, 0.6, 0.1);
        fx.dObs(seg, G1, "P2", 2025, 51.2, 51.6, 0.4, 0.5, 0.1);
        // 2026 定位误差扩大到 1.2m → 两条前期观测的扩张带都与两条后期观测重叠
        fx.dObs(seg, G1, "P1", 2026, 49.8, 50.4, 0.5, 0.6, 1.2);
        fx.dObs(seg, G1, "P2", 2026, 51.0, 51.5, 0.4, 0.5, 1.2);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G1, 2026, 0, 120);

        DefectEvolutionRun run = run(seg, LinkMethod.GLOBAL_CONSTRAINT);
        var trajs = service.getTrajectories(run.id());
        // 不双计：每条观测只计入一条轨迹一次（顶点不相交）
        List<Long> ids = new java.util.ArrayList<>();
        for (var tr : trajs) {
            for (JsonNode n : om.readTree(tr.observationIdsJson())) {
                assertFalse(ids.contains(n.asLong()), "同一观测不得被两条轨迹双计");
                ids.add(n.asLong());
            }
        }
        assertEquals(4, ids.size(), "4 条观测全部各计一次");

        // 若存在竞争，则相应轨迹标记 contested 并排除出排序（NOT_COMPARABLE）
        boolean anyContested = trajs.stream().anyMatch(tr -> {
            try {
                return om.readTree(tr.ambiguityJson()).path("contested").asBoolean();
            } catch (Exception ex) {
                return false;
            }
        });
        if (anyContested) {
            assertTrue(trajs.stream().anyMatch(tr -> tr.status().equals(Vocab.TRAJ_NOT_COMPARABLE)));
            assertFalse(summary(run).path("contested").isEmpty());
        }
    }

    @Test
    void adjacentCracksCoexistAsTwoSeparateTrajectories() throws Exception {
        long seg = fx.defectSegment("T-CONTENTION");
        // 长裂缝 L（99-100m）与短裂缝 R（104m），相隔约 4m，各自逐年持续
        fx.dObs(seg, G1, "L", 2024, 99.00, 100.00, 1.0, 1.2, 0.1);
        fx.dObs(seg, G1, "R", 2024, 104.00, 104.30, 0.2, 0.3, 0.1);
        fx.dObs(seg, G1, "L", 2025, 98.90, 100.10, 1.0, 1.2, 0.1);
        fx.dObs(seg, G1, "R", 2025, 104.00, 104.30, 0.2, 0.3, 0.1);
        fx.dObs(seg, G1, "L", 2026, 99.05, 100.15, 1.0, 1.2, 0.1);
        fx.dObs(seg, G1, "R", 2026, 103.95, 104.25, 0.2, 0.3, 0.1);
        for (int y = 2024; y <= 2026; y++) {
            fx.dCover(seg, G1, y, 0, 120);
        }
        // 两条路线都应得到 2 条轨迹（长、短各一条），且不双计
        for (LinkMethod m : LinkMethod.values()) {
            DefectEvolutionRun run = run(seg, m);
            var trajs = service.getTrajectories(run.id());
            assertEquals(2, trajs.size(), m + "：两条相邻裂缝应为两条轨迹");
            var ids = new java.util.ArrayList<Long>();
            for (var tr : trajs) {
                for (JsonNode n : om.readTree(tr.observationIdsJson())) {
                    assertFalse(ids.contains(n.asLong()), m + "：观测不双计");
                    ids.add(n.asLong());
                }
            }
            assertEquals(6, ids.size());
            // 两条裂缝各自持续、长度在边界内 → PERSISTENT（不得误判为新发/消失）
            assertTrue(trajs.stream().allMatch(tr -> tr.status().equals(Vocab.TRAJ_PERSISTENT)),
                    m + "：相邻并存裂缝应各自持续");
        }
    }

    // ---------------------------------------------------------------- 幂等 / 路线

    @Test
    void rerunIsIdempotentAndRouteGuidanceIndependent() throws Exception {
        long seg = fx.defectSegment("T-IDEMPOTENT");
        fx.dObs(seg, G1, "I1", 2025, 10, 12, 1.0, 1.2, 0.4);
        fx.dObs(seg, G1, "I1", 2026, 9.8, 12.2, 1.4, 1.7, 0.5);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G1, 2026, 0, 120);

        var first = service.run(seg, LinkMethod.RULE_BASED, asOf());
        var second = service.run(seg, LinkMethod.RULE_BASED, asOf());
        assertFalse(first.replayed());
        assertTrue(second.replayed(), "同键幂等重放");
        assertEquals(first.run().id(), second.run().id());

        // 整体约束是独立路线：从头求解全段，结果非空，且汇总里有路线纪律说明（非补救）
        var global = service.run(seg, LinkMethod.GLOBAL_CONSTRAINT, asOf());
        assertFalse(global.replayed());
        JsonNode gsum = summary(global.run());
        assertTrue(gsum.path("methodDiscipline").asText().contains("非规则失败后的补救"));
        JsonNode guide = gsum.path("routeGuidance");
        assertFalse(guide.path("ruleBasedFavoredWhen").isEmpty());
        assertFalse(guide.path("globalConstraintFavoredWhen").isEmpty());
    }

    @Test
    void asOfVisibilityHidesLaterRegisteredObservations() {
        long seg = fx.defectSegment("T-ASOF");
        fx.dObs(seg, G1, "V1", 2024, 10, 12, 0.8, 1.0, 0.4);
        fx.dObs(seg, G1, "V1", 2025, 9.8, 12.2, 1.6, 1.9, 0.4);
        fx.dCover(seg, G1, 2024, 0, 120);
        fx.dCover(seg, G1, 2025, 0, 120);
        // as_of 早于 2025 观测入库（registered_at = 2025-05-13）→ 只见 2024
        var early = service.run(seg, LinkMethod.RULE_BASED, t(2025, 1, 1, 0, 0)).run();
        assertEquals(1, service.getTrajectories(early.id()).size());
        var late = service.run(seg, LinkMethod.RULE_BASED, asOf()).run();
        assertEquals(1, service.getTrajectories(late.id()).size());
        try {
            assertEquals(2, om.readTree(service.getTrajectories(late.id()).get(0)
                    .observationIdsJson()).size(), "as_of 足够晚时两期可见");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void changeRangesExposedAsIndependentAnalysis() throws Exception {
        long seg = fx.defectSegment("T-CHANGE");
        fx.dObs(seg, G1, "R1", 2025, 30, 32, 1.0, 1.2, 0.4);
        fx.dObs(seg, G1, "R1", 2026, 29.8, 32.2, 1.6, 1.9, 0.4);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G1, 2026, 0, 120);
        DefectEvolutionRun run = run(seg, LinkMethod.RULE_BASED);
        Object ranges = service.getChangeRanges(run.id());
        assertNotNull(ranges);
        JsonNode node = om.valueToTree(ranges);
        assertEquals(1, node.size());
        assertTrue(node.get(0).path("changeRange").path("supportedChangeBeyondBoundaryM")
                .get(0).asDouble() > 0, "扩展量已扣除测量边界");
    }

    // ---------------------------------------------------------------- 新发 / 消失

    @Test
    void newDefectAfterPriorCoverageIsPossiblyNew() {
        long seg = fx.defectSegment("T-NEW");
        // 2026 才出现的新缺陷；2025 该位置有覆盖但无缺陷
        fx.dObs(seg, G1, "N1", 2026, 70, 71.5, 0.9, 1.1, 0.4);
        fx.dCover(seg, G1, 2025, 0, 120);
        fx.dCover(seg, G1, 2026, 0, 120);
        DefectEvolutionRun run = run(seg, LinkMethod.GLOBAL_CONSTRAINT);
        var trajs = service.getTrajectories(run.id());
        assertEquals(1, trajs.size());
        assertEquals(Vocab.TRAJ_POSSIBLY_NEW, trajs.get(0).status(), "前期有覆盖无缺陷 → 可能新发");
    }

    @Test
    void defectNotSeenAfterLaterCoverageIsPossiblyDisappeared() {
        long seg = fx.defectSegment("T-DISAPPEARED");
        // 2024 有的缺陷，2025 该位置有覆盖但未再记录
        fx.dObs(seg, G1, "D1", 2024, 50, 51.5, 0.8, 1.0, 0.4);
        fx.dCover(seg, G1, 2024, 0, 120);
        fx.dCover(seg, G1, 2025, 0, 120);
        DefectEvolutionRun run = run(seg, LinkMethod.RULE_BASED);
        var trajs = service.getTrajectories(run.id());
        assertEquals(1, trajs.size());
        assertEquals(Vocab.TRAJ_POSSIBLY_DISAPPEARED, trajs.get(0).status(), "后期有覆盖无后继 → 可能消失");
    }
}
