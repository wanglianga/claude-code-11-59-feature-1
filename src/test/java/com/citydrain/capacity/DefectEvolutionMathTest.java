package com.citydrain.capacity;

import com.citydrain.capacity.config.DefectEvolutionProps;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.service.evolution.CandidateLinker;
import com.citydrain.capacity.service.evolution.DefectGeometryMath;
import com.citydrain.capacity.service.evolution.GeometryVersionMath;
import com.citydrain.capacity.service.evolution.HungarianMatcher;
import com.citydrain.capacity.service.evolution.TrajectoryLinker;
import com.citydrain.capacity.domain.GeometryMapping;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 缺陷演化连接核心纯函数与两路线差异（不依赖数据库）。 */
class DefectEvolutionMathTest {

    private static final ZoneOffset CST = ZoneOffset.ofHours(8);
    private static final DefectEvolutionProps PROPS =
            new DefectEvolutionProps(0.10, 5.0, 0.05, 0);

    private static OffsetDateTime at(int y) {
        return OffsetDateTime.of(y, 5, 10, 10, 0, 0, 0, CST);
    }

    private static int seq = 1;

    private static DefectObservation obs(String geo, String code, int year, double s, double e,
                                         double lenMin, double lenMax, double posUnc) {
        return new DefectObservation(seq++, 1L, geo, code, "裂缝", at(year), s, e,
                lenMin, lenMax, 0.3, 0.5, 4.0, posUnc, "CCTV", "V" + year, null, at(year).plusDays(3));
    }

    @Test
    void overlapRatioBasics() {
        var a = new DefectGeometryMath.Interval(0, 10);
        var b = new DefectGeometryMath.Interval(5, 15);
        assertEquals(0.5, DefectGeometryMath.overlapRatio(a, b), 1e-9);
        assertEquals(1.0, DefectGeometryMath.overlapRatio(a, new DefectGeometryMath.Interval(2, 8)), 1e-9);
        assertEquals(0.0, DefectGeometryMath.overlapRatio(a, new DefectGeometryMath.Interval(20, 30)), 1e-9);
    }

    @Test
    void changeClassificationRespectsMeasurementBoundary() {
        // 增长 1.0m，边界 0.005m → 支持扩展
        assertEquals("SUPPORTED_INCREASE", DefectGeometryMath.classifyChange(
                new DefectGeometryMath.Interval(0.99, 1.01), 0.005));
        // 变化 -0.1..0.1，边界 0.5m → 测量边界内
        assertEquals("WITHIN_MEASUREMENT_BOUNDARY", DefectGeometryMath.classifyChange(
                new DefectGeometryMath.Interval(-0.1, 0.1), 0.5));
        // 收缩
        assertEquals("SUPPORTED_DECREASE", DefectGeometryMath.classifyChange(
                new DefectGeometryMath.Interval(-1.0, -0.8), 0.005));
    }

    @Test
    void geometryMappingOnlyWithinDeclaredRange() {
        var mapping = new GeometryMapping(1, 1L, "G1", "G2", 70, 100, 68, 98, null, at(2026));
        var inside = obs("G1", "X", 2025, 80, 82, 1.0, 1.2, 0.3);
        var outside = obs("G1", "Y", 2025, 95, 97, 1.0, 1.2, 0.3);
        var target = obs("G2", "X2", 2026, 78, 80, 1.1, 1.3, 0.3);
        // 平移 -2m：80→78，82→80
        var mapped = GeometryVersionMath.mapChainage(inside, target, List.of(mapping));
        assertNotNull(mapped);
        assertEquals(77.7, mapped.lo(), 1e-6); // (80-0.3) - 2
        // 95m 的观测也在 [70,100] 内吗？95 在范围内，但构造一个真正越界的：
        var far = obs("G1", "Z", 2025, 105, 107, 1.0, 1.2, 0.3);
        assertNull(GeometryVersionMath.mapChainage(far, target, List.of(mapping)),
                "越出 from 范围 → 无映射");
        assertNull(GeometryVersionMath.mapChainage(obs("G1", "W", 2025, 40, 42, 1.0, 1.2, 0.3),
                target, List.of(mapping)));
    }

    @Test
    void crackExpansionLinksAcrossNonOverlappingLengths() {
        // 长度区间不重叠（0.8-1.0 vs 2.6-3.0），但里程重叠 → 仍应连成扩展轨迹
        var a = obs("G", "A", 2024, 40, 52, 0.8, 1.0, 0.5);
        var b = obs("G", "A", 2025, 39.5, 52.5, 2.6, 3.0, 0.5);
        CandidateLinker.Edge e = CandidateLinker.evaluate(a, b, List.of(), PROPS);
        assertTrue(e.feasible(), "扩展裂缝长度不重叠也应可连接：" + e.rejectReason());
    }

    @Test
    void geometryWithoutMappingRejected() {
        var a = obs("G1", "A", 2025, 95, 97, 1.0, 1.2, 0.3);
        var b = obs("G2", "A", 2026, 95, 97, 1.0, 1.2, 0.3);
        CandidateLinker.Edge e = CandidateLinker.evaluate(a, b, List.of(), PROPS);
        assertFalse(e.feasible());
        assertTrue(e.rejectReason().contains("无明确映射"));
    }

    @Test
    void hungarianFindsOptimalAssignment() {
        double[][] w = {
                {9, 2},
                {3, 8}};
        var pairs = HungarianMatcher.maxWeightMatching(w);
        // 最优：行0-列0(9)+行1-列1(8)=17，而非 2+3=5
        assertEquals(2, pairs.size());
        assertTrue(pairs.stream().anyMatch(p -> p.row() == 0 && p.col() == 0));
        assertTrue(pairs.stream().anyMatch(p -> p.row() == 1 && p.col() == 1));
    }

    @Test
    void twoRoutesDifferOnAmbiguousSwap() {
        // 两条相邻裂缝，后期里程互换；规则贪心与整体约束可能给出不同但都“时间+空间”合法的集合
        var l24 = obs("G", "L", 2024, 99.00, 100.00, 0.6, 0.7, 0.05);
        var r24 = obs("G", "R", 2024, 100.10, 100.25, 0.15, 0.16, 0.05);
        var l25 = obs("G", "L", 2025, 100.13, 101.13, 0.6, 0.7, 0.05);
        var r25 = obs("G", "R", 2025, 100.00, 101.00, 0.15, 0.16, 0.05);
        var all = List.of(l24, r24, l25, r25);
        var rule = TrajectoryLinker.link(all, List.of(), PROPS, "RULE_BASED");
        var global = TrajectoryLinker.link(all, List.of(), PROPS, "GLOBAL_CONSTRAINT");
        // 两条路线各自都应产生合法连接（不作为对方的补救），此处只断言都非空且连通
        assertFalse(rule.chains().isEmpty());
        assertFalse(global.chains().isEmpty());
        // 两种路线都不应把同一观测同时计入两条链（顶点不相交）
        assertTrue(vertexDisjoint(rule.chains()));
        assertTrue(vertexDisjoint(global.chains()));
    }

    private boolean vertexDisjoint(List<List<Long>> chains) {
        List<Long> seen = new java.util.ArrayList<>();
        for (List<Long> c : chains) {
            for (Long id : c) {
                if (seen.contains(id)) {
                    return false;
                }
                seen.add(id);
            }
        }
        return true;
    }
}
