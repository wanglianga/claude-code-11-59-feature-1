package com.citydrain.capacity.service;

import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.repo.EventRepo;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.DefectContextRepo;
import com.citydrain.capacity.repo.DefectObservationRepo;
import com.citydrain.capacity.repo.NetworkVersionRepo;
import com.citydrain.capacity.repo.ObservationRepo;
import com.citydrain.capacity.repo.ParamVersionRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 种子数据：覆盖全部验收场景（断面局部缩减、淤积测量区间、糙率缺失、暴雨边界变化、
 * 闸门状态冲突、维修记录、检查覆盖/缺陷只读引用）。仅在数据库为空时执行。
 * 时间线：参数版本 2026-01 登记；事件 E1=2026-07-14 暴雨；E2=2026-08-02 暴雨（无水位观测）；
 * 维修验收 2026-09-01。迟到补录在验收验证时实时入库。
 */
@Component
@Profile("!test")
public class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);
    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    private final NetworkVersionRepo nvRepo;
    private final SegmentRepo segmentRepo;
    private final ParamVersionRepo paramRepo;
    private final ObservationRepo obsRepo;
    private final EventRepo eventRepo;
    private final ExternalRefRepo extRepo;
    private final DefectObservationRepo defectObsRepo;
    private final DefectContextRepo contextRepo;
    private final boolean seedEnabled;

    public SeedDataRunner(NetworkVersionRepo nvRepo, SegmentRepo segmentRepo,
                          ParamVersionRepo paramRepo, ObservationRepo obsRepo,
                          EventRepo eventRepo, ExternalRefRepo extRepo,
                          DefectObservationRepo defectObsRepo, DefectContextRepo contextRepo,
                          @Value("${app.seed.enabled:true}") boolean seedEnabled) {
        this.nvRepo = nvRepo;
        this.segmentRepo = segmentRepo;
        this.paramRepo = paramRepo;
        this.obsRepo = obsRepo;
        this.eventRepo = eventRepo;
        this.extRepo = extRepo;
        this.defectObsRepo = defectObsRepo;
        this.contextRepo = contextRepo;
        this.seedEnabled = seedEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled || !nvRepo.listCodes().isEmpty()) {
            return;
        }
        log.info("写入种子数据...");

        long nvId = nvRepo.insert("NV-2024A", "2024 版竣工管网（主干线 N1→B1）");

        // 管段：主干线 N1→S1→N2→S2→N3→S3→N4→S4→B1(出口)
        long s1 = segmentRepo.insert(nvId, "S1", "N1", "N2", 180, 1000, 12.36, 12.00, 900);
        long s2 = segmentRepo.insert(nvId, "S2", "N2", "N3", 150, 1000, 12.00, 11.70, 900);
        long s3 = segmentRepo.insert(nvId, "S3", "N3", "N4", 200, 1200, 11.70, 11.30, 1400);
        long s4 = segmentRepo.insert(nvId, "S4", "N4", "B1", 160, 1200, 11.30, 10.98, 1400);

        // 参数版本 v1（2026-01-01 起有效，2026-01-05 登记入库）
        OffsetDateTime validFrom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, CST);
        OffsetDateTime registered = OffsetDateTime.of(2026, 1, 5, 10, 0, 0, 0, CST);
        seedParam(s1, 1, "SURVEY", 990.0, 1000.0, 0.0019, 0.0021, 0.012, 0.014, 0.0, 0.05,
                validFrom, registered, "2026-01 全线测量确认");
        // S2：断面局部缩减（CCTV+声呐确认有效直径 [900,960]mm，名义 1000mm）
        seedParam(s2, 1, "SURVEY", 900.0, 960.0, 0.0019, 0.0021, 0.012, 0.014, 0.05, 0.10,
                validFrom, registered, "断面局部缩减确认（缺陷引用 D-1001 佐证）");
        // S3：淤积测量区间 [0.10,0.20]
        seedParam(s3, 1, "SURVEY", 1180.0, 1200.0, 0.0019, 0.0021, 0.013, 0.015, 0.10, 0.20,
                validFrom, registered, "淤积测量区间 [0.10,0.20]");
        // S4：糙率缺失（内衬资料不全）
        seedParam(s4, 1, "AS_BUILT", 1190.0, 1200.0, 0.0019, 0.0021, null, null, 0.0, 0.05,
                validFrom, registered, "竣工断面；糙率缺失（内衬资料不全）");

        // 水文事件
        long e1 = eventRepo.insert("E-2026-0714-STORM", "2026-07-14 暴雨（有完整水位/流量监测）",
                OffsetDateTime.of(2026, 7, 14, 2, 0, 0, 0, CST),
                OffsetDateTime.of(2026, 7, 14, 8, 0, 0, 0, CST));
        long e2 = eventRepo.insert("E-2026-0802-STORM", "2026-08-02 暴雨（仅降雨，无水位观测）",
                OffsetDateTime.of(2026, 8, 2, 14, 0, 0, 0, CST),
                OffsetDateTime.of(2026, 8, 2, 18, 0, 0, 0, CST));

        // E1 观测（2026-07-14 09:30 入库）
        OffsetDateTime ing1 = OffsetDateTime.of(2026, 7, 14, 9, 30, 0, 0, CST);
        // 降雨 mm/h
        obs("RAINFALL", "BOUNDARY", "B1", 12.0, null, "mm/h", t(2026, 7, 14, 3, 0), ing1);
        obs("RAINFALL", "BOUNDARY", "B1", 28.0, null, "mm/h", t(2026, 7, 14, 4, 0), ing1);
        obs("RAINFALL", "BOUNDARY", "B1", 15.0, null, "mm/h", t(2026, 7, 14, 5, 0), ing1);
        // 下游边界水位（04:00 壅水超过管顶 12.18m）
        obs("DOWNSTREAM_LEVEL", "BOUNDARY", "B1", 11.30, null, "m", t(2026, 7, 14, 3, 0), ing1);
        obs("DOWNSTREAM_LEVEL", "BOUNDARY", "B1", 12.40, null, "m", t(2026, 7, 14, 4, 0), ing1);
        obs("DOWNSTREAM_LEVEL", "BOUNDARY", "B1", 11.90, null, "m", t(2026, 7, 14, 5, 0), ing1);
        // 节点水位（与流量同刻对齐，用于校准）
        level("N1", 12.68, 13.56, 13.21, ing1);
        level("N2", 12.32, 13.20, 12.85, ing1);
        level("N3", 12.02, 12.90, 12.55, ing1);
        level("N4", 11.62, 12.55, 12.20, ing1);
        // 流量（与水位同刻；S2 另有一条 03:25 未对齐流量，校准时应被拒绝）
        flow("S1", 1008, 1023, 993, ing1);
        flow("S2", 785, 809, 791, ing1);
        obs("FLOW", "SEGMENT", "S2", 960.0, null, "L/s", t(2026, 7, 14, 3, 25), ing1);
        flow("S3", 1275, 1228, 1176, ing1);
        flow("S4", 1606, 1060, 1521, ing1);
        // 闸门 G-S3 状态冲突：02:30 OPEN 与 03:10 CLOSED
        obs("GATE_STATE", "GATE", "G-S3", null, "OPEN", null, t(2026, 7, 14, 2, 30), ing1);
        obs("GATE_STATE", "GATE", "G-S3", null, "CLOSED", null, t(2026, 7, 14, 3, 10), ing1);

        // E2 观测（仅降雨，2026-08-02 19:00 入库）→ 演示下游水位缺失扩大区间
        OffsetDateTime ing2 = OffsetDateTime.of(2026, 8, 2, 19, 0, 0, 0, CST);
        obs("RAINFALL", "BOUNDARY", "B1", 35.0, null, "mm/h", t(2026, 8, 2, 15, 0), ing2);

        // 外部只读引用
        extRepo.insert("DEFECT_OBSERVATION", "D-1001", "CCTV检测系统", "CCTV-2026.05", s2,
                OffsetDateTime.of(2026, 5, 20, 10, 0, 0, 0, CST),
                "{\"locationRange\":{\"startM\":45,\"endM\":62},\"category\":\"结构缺陷\",\"grade\":2,"
                        + "\"morphology\":{\"type\":\"变形/局部缩减\",\"reductionPct\":[8,12]},"
                        + "\"observedBy\":\"检测单位A\"}");
        extRepo.insert("INSPECTION_COVERAGE", "C-2001", "检测管理系统", "INS-2026.05", s2,
                OffsetDateTime.of(2026, 5, 18, 9, 0, 0, 0, CST),
                "{\"scope\":\"S2 全管段 CCTV\",\"coveredLengthM\":150,\"inspector\":\"检测单位A\"}");
        extRepo.insert("INSPECTION_COVERAGE", "C-2002", "检测管理系统", "INS-2026.05", s3,
                OffsetDateTime.of(2026, 5, 18, 9, 0, 0, 0, CST),
                "{\"scope\":\"S3 全管段 CCTV\",\"coveredLengthM\":200,\"inspector\":\"检测单位A\"}");
        // 维修：R-3001 有验收且实测可追溯（验收验证时派生新版本）；R-3002 无验收；R-3003 缺 measurementRef
        extRepo.insert("REPAIR_ACTION", "R-3001", "维修管理系统", "REP-2026.09", s2,
                OffsetDateTime.of(2026, 9, 1, 9, 0, 0, 0, CST),
                "{\"scope\":\"S2 45-62m 不锈钢内衬修复\","
                        + "\"acceptance\":{\"result\":\"ACCEPTED\",\"acceptedAt\":\"2026-09-01T09:00:00+08:00\"},"
                        + "\"measuredChanges\":{\"diameterMm\":[968,985],\"siltRatio\":[0.0,0.02],"
                        + "\"roughness\":[0.011,0.013]},\"measurementRef\":\"POST-REHAB-SURVEY-2026-09\"}");
        extRepo.insert("REPAIR_ACTION", "R-3002", "维修管理系统", "REP-2026.09", s3,
                OffsetDateTime.of(2026, 9, 3, 9, 0, 0, 0, CST),
                "{\"scope\":\"S3 清淤\",\"acceptance\":{\"result\":\"PENDING\"},"
                        + "\"measuredChanges\":{\"siltRatio\":[0.02,0.05]},\"measurementRef\":\"X-1\"}");
        extRepo.insert("REPAIR_ACTION", "R-3003", "维修管理系统", "REP-2026.09", s1,
                OffsetDateTime.of(2026, 9, 5, 9, 0, 0, 0, CST),
                "{\"scope\":\"S1 检查井修复\","
                        + "\"acceptance\":{\"result\":\"ACCEPTED\",\"acceptedAt\":\"2026-09-05T09:00:00+08:00\"},"
                        + "\"measuredChanges\":{\"diameterMm\":[995,1000]}}");

        seedDefectEvolution(nvId);

        log.info("种子数据完成: networkVersionId={}, segments=[{},{},{},{}], events=[{},{}]",
                nvId, s1, s2, s3, s4, e1, e2);
    }

    /**
     * 缺陷形态演化验收场景（独立管段 S5，与能力评估主线解耦）。
     * 覆盖：裂缝扩展 / 相邻裂缝并存 / 定位误差扩大 / 缺巡检年 / 几何改造。
     * 多期 CCTV 巡检各期分辨率与定位误差不同；原始判读只读，演化运行在其上追加分析。
     */
    private void seedDefectEvolution(long nvId) {
        // 每个验收场景一条独立管段，互不干扰，便于逐条核对轨迹/状态/排序。
        String g1 = "GEO-2024";
        String g2 = "GEO-2026-REHAB";

        // E1 裂缝扩展：60-62m 裂缝逐年变长，长度区间不重叠且超出测量边界 → POSSIBLY_EXPANDED
        seedDefectSegment(nvId, "S5-CRACK-EXPAND", "裂缝扩展");

        // E2 相邻裂缝并存：约 99m 与约 104m 两条裂缝，各成一条轨迹、不并存一条、不双计
        seedContentionSegment(nvId);

        // E3 定位误差扩大：同一位置两条裂缝，2026 定位误差扩大后位置带互相覆盖 → 竞争不双计
        seedPositionErrorSegment(nvId);

        // E4 缺巡检年：2024 与 2026 有观测、2025 无覆盖 → NOT_COMPARABLE / COVERAGE_GAP
        seedMissingYearSegment(nvId);

        // E5 几何改造（可映射）：旧几何经明确映射范围与新几何相连 → PERSISTENT / GEOMETRY_REMAPPED
        seedMappedRehabSegment(nvId);

        // E6 几何改造（不可映射）：坐标相近但映射范围不覆盖 → NOT_COMPARABLE / GEOMETRY_NOT_MAPPED
        seedUnmappedRehabSegment(nvId);

        // E7 可能新发：2025 该位置有覆盖但无缺陷，2026 出现新缺陷 → POSSIBLY_NEW
        seedNewDefectSegment(nvId);

        // E8 可能消失：2024 有缺陷且 2025 该位置有覆盖（未再记录）→ POSSIBLY_DISAPPEARED
        seedDisappearedDefectSegment(nvId);

        log.info("缺陷演化种子完成：CRACK-EXPAND / ADJACENT-CRACKS / POSITION-ERROR / MISSING-YEAR"
                + " / GEOM-MAPPED / GEOM-UNMAPPED / NEW / DISAPPEARED");
    }

    /** E1：单裂缝逐年扩展（三段观测，长度区间不重叠）。 */
    private void seedDefectSegment(long nvId, String code, String desc) {
        String g1 = "GEO-2024";
        long seg = segmentRepo.insert(nvId, code, "D-" + code, "D-" + code + "-N", 120, 1000,
                11.00, 10.80, 600);
        // 2024：长度 0.8-1.0m
        defectObs(seg, g1, "CRK-1", "裂缝", time(2024, 5, 10), 60.0, 62.0,
                0.8, 1.0, 0.3, 0.5, 5.0, 0.4, "CCTV-2024");
        // 2025：长度 1.6-1.9m（显著增长）
        defectObs(seg, g1, "CRK-1", "裂缝", time(2025, 5, 12), 59.6, 62.4,
                1.6, 1.9, 0.4, 0.7, 4.0, 0.4, "CCTV-2025");
        // 2026：长度 2.6-3.0m（继续扩展）
        defectObs(seg, g1, "CRK-1", "裂缝", time(2026, 5, 14), 59.2, 62.8,
                2.6, 3.0, 0.6, 1.0, 3.0, 0.5, "CCTV-2026");
        cover(seg, g1, 2024, 0, 120, time(2024, 5, 10));
        cover(seg, g1, 2025, 0, 120, time(2025, 5, 12));
        cover(seg, g1, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E2：相邻两条裂缝并存（里程相近但可分离），各自逐年持续存在。 */
    private void seedContentionSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-ADJACENT-CRACKS", "D-ADJ", "D-ADJ-N", 120, 1000,
                11.00, 10.80, 600);
        String g = "GEO-2024";
        // 长裂缝 L：约 99m，长度 ~1.1m，逐年轻微漂移
        defectObs(seg, g, "ADJ-L", "裂缝", time(2024, 5, 10), 99.00, 100.00,
                1.0, 1.2, 0.3, 0.4, 5.0, 0.1, "CCTV-2024");
        defectObs(seg, g, "ADJ-L", "裂缝", time(2025, 5, 12), 98.90, 100.10,
                1.0, 1.2, 0.3, 0.4, 5.0, 0.1, "CCTV-2025");
        defectObs(seg, g, "ADJ-L", "裂缝", time(2026, 5, 14), 99.05, 100.15,
                1.0, 1.2, 0.3, 0.4, 5.0, 0.1, "CCTV-2026");
        // 短裂缝 R：约 104m，长度 ~0.25m，与 L 相隔约 4m（远超定位误差），应与 L 各自成链
        defectObs(seg, g, "ADJ-R", "裂缝", time(2024, 5, 10), 104.00, 104.30,
                0.2, 0.3, 0.2, 0.25, 5.0, 0.1, "CCTV-2024");
        defectObs(seg, g, "ADJ-R", "裂缝", time(2025, 5, 12), 104.00, 104.30,
                0.2, 0.3, 0.2, 0.25, 5.0, 0.1, "CCTV-2025");
        defectObs(seg, g, "ADJ-R", "裂缝", time(2026, 5, 14), 103.95, 104.25,
                0.2, 0.3, 0.2, 0.25, 5.0, 0.1, "CCTV-2026");
        cover(seg, g, 2024, 0, 120, time(2024, 5, 10));
        cover(seg, g, 2025, 0, 120, time(2025, 5, 12));
        cover(seg, g, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E3：定位误差扩大导致竞争（同位置两条裂缝，后期定位误差覆盖两者）。 */
    private void seedPositionErrorSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-POSITION-ERROR", "D-POS", "D-POS-N", 120, 1000,
                11.00, 10.80, 600);
        String g = "GEO-2024";
        // 2025：两条裂缝 P1(50.0-50.5) 与 P2(51.2-51.6)，定位误差 0.1m
        defectObs(seg, g, "POS-1", "裂缝", time(2025, 5, 12), 50.0, 50.5,
                0.5, 0.6, 0.3, 0.4, 4.0, 0.1, "CCTV-2025");
        defectObs(seg, g, "POS-2", "裂缝", time(2025, 5, 12), 51.2, 51.6,
                0.4, 0.5, 0.3, 0.4, 4.0, 0.1, "CCTV-2025");
        // 2026：两条仍在，但定位误差扩大到 1.2m；P1 位置 49.8-50.4，P2 位置 51.0-51.5
        defectObs(seg, g, "POS-1", "裂缝", time(2026, 5, 14), 49.8, 50.4,
                0.5, 0.6, 0.3, 0.5, 3.0, 1.2, "CCTV-2026");
        defectObs(seg, g, "POS-2", "裂缝", time(2026, 5, 14), 51.0, 51.5,
                0.4, 0.5, 0.3, 0.5, 3.0, 1.2, "CCTV-2026");
        cover(seg, g, 2025, 0, 120, time(2025, 5, 12));
        cover(seg, g, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E4：缺巡检年（中间年份无覆盖）。 */
    private void seedMissingYearSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-MISSING-YEAR", "D-MISS", "D-MISS-N", 120, 1000,
                11.00, 10.80, 600);
        String g = "GEO-2024";
        defectObs(seg, g, "MISS-1", "裂缝", time(2024, 5, 10), 40.0, 42.0,
                0.9, 1.1, 0.3, 0.5, 5.0, 0.4, "CCTV-2024");
        defectObs(seg, g, "MISS-1", "裂缝", time(2026, 5, 14), 39.6, 42.4,
                1.9, 2.3, 0.5, 0.8, 3.0, 0.5, "CCTV-2026");
        cover(seg, g, 2024, 0, 120, time(2024, 5, 10));
        // 2025 无覆盖（缺巡检年）
        cover(seg, g, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E5：几何改造（明确映射范围内可连接）。 */
    private void seedMappedRehabSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-GEOM-MAPPED", "D-MAP", "D-MAP-N", 120, 1000,
                11.00, 10.80, 600);
        String g1 = "GEO-2024";
        String g2 = "GEO-2026-REHAB";
        defectObs(seg, g1, "MAP-1", "裂缝", time(2025, 5, 12), 80.0, 82.0,
                1.2, 1.4, 0.4, 0.6, 4.0, 0.3, "CCTV-2025");
        // 改造后新里程 78.1-80.2（旧 [80,82] 经映射 [70,100]→[68,98] 平移 -2m 得 [-?])
        defectObs(seg, g2, "MAP-1", "裂缝", time(2026, 5, 14), 78.0, 80.2,
                1.4, 1.6, 0.4, 0.7, 3.0, 0.3, "CCTV-2026");
        // 映射：旧 [70,100] → 新 [68,98]（平移 -2m），观测(含误差)落在范围内
        contextRepo.insertMapping(new com.citydrain.capacity.domain.GeometryMapping(
                0, seg, g1, g2, 70.0, 100.0, 68.0, 98.0,
                "2026-03 改造：70-100m 段重修，里程平移 -2m", time(2026, 3, 1)));
        cover(seg, g1, 2025, 0, 120, time(2025, 5, 12));
        cover(seg, g2, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E6：几何改造（映射范围不覆盖，坐标相近也不可连接）。 */
    private void seedUnmappedRehabSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-GEOM-UNMAPPED", "D-UNM", "D-UNM-N", 120, 1000,
                11.00, 10.80, 600);
        String g1 = "GEO-2024";
        String g2 = "GEO-2026-REHAB";
        defectObs(seg, g1, "UNM-1", "裂缝", time(2025, 5, 12), 95.0, 97.0,
                1.0, 1.2, 0.3, 0.5, 4.0, 0.3, "CCTV-2025");
        defectObs(seg, g2, "UNM-1", "裂缝", time(2026, 5, 14), 95.2, 97.4,
                1.1, 1.4, 0.4, 0.7, 3.0, 0.3, "CCTV-2026");
        // 映射仅覆盖 [70,90]→[68,88]，95-97m 不在范围内 → 不可跨几何连接
        contextRepo.insertMapping(new com.citydrain.capacity.domain.GeometryMapping(
                0, seg, g1, g2, 70.0, 90.0, 68.0, 88.0,
                "2026-03 改造仅 70-90m，95m 段几何未声明", time(2026, 3, 1)));
        cover(seg, g1, 2025, 0, 120, time(2025, 5, 12));
        cover(seg, g2, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E7：可能新发——前期该位置有覆盖但无缺陷，后期出现新缺陷。 */
    private void seedNewDefectSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-POSSIBLY-NEW", "D-NEW", "D-NEW-N", 120, 1000,
                11.00, 10.80, 600);
        String g = "GEO-2024";
        // 2026 才出现的新缺陷（约 70m）
        defectObs(seg, g, "NEW-1", "裂缝", time(2026, 5, 14), 70.0, 71.5,
                0.9, 1.1, 0.3, 0.5, 3.0, 0.4, "CCTV-2026");
        // 2025 该管段（含 70m 位置）有覆盖，未记录该缺陷 → 前期有覆盖
        cover(seg, g, 2025, 0, 120, time(2025, 5, 12));
        cover(seg, g, 2026, 0, 120, time(2026, 5, 14));
    }

    /** E8：可能消失——前期有缺陷，后期该位置有覆盖但未再记录。 */
    private void seedDisappearedDefectSegment(long nvId) {
        long seg = segmentRepo.insert(nvId, "S5-POSSIBLY-DISAPPEARED", "D-DIS", "D-DIS-N", 120, 1000,
                11.00, 10.80, 600);
        String g = "GEO-2024";
        // 2024 有的缺陷（约 50m），之后未再记录
        defectObs(seg, g, "DIS-1", "裂缝", time(2024, 5, 10), 50.0, 51.5,
                0.8, 1.0, 0.3, 0.5, 5.0, 0.4, "CCTV-2024");
        cover(seg, g, 2024, 0, 120, time(2024, 5, 10));
        // 2025 该管段（含 50m 位置）有覆盖，但未记录该缺陷 → 后期有覆盖
        cover(seg, g, 2025, 0, 120, time(2025, 5, 12));
    }

    private void cover(long segmentId, String geoVersion, int year, double start, double end,
                       OffsetDateTime at) {
        contextRepo.insertCoverage(new com.citydrain.capacity.domain.InspectionCoverage(
                0, segmentId, geoVersion, year, start, end, at, "INSPECTION", "Y" + year, at));
    }
    private OffsetDateTime time(int y, int mo, int d) {
        return OffsetDateTime.of(y, mo, d, 10, 0, 0, 0, CST);
    }

    private void defectObs(long segmentId, String geoVersion, String code, String type,
                           OffsetDateTime observedAt, double chStart, double chEnd,
                           double lenMin, double lenMax, double wMin, double wMax,
                           double resolutionMm, double posUncM, String sourceVersion) {
        defectObsRepo.insert(new com.citydrain.capacity.domain.DefectObservation(
                0, segmentId, geoVersion, code, type, observedAt, chStart, chEnd,
                lenMin, lenMax, wMin, wMax, resolutionMm, posUncM,
                "CCTV检测系统", sourceVersion, null, observedAt.plusDays(3)));
    }

    private void seedParam(long segmentId, int versionNo, String source,
                           Double dMin, Double dMax, Double sMin, Double sMax,
                           Double nMin, Double nMax, Double siltMin, Double siltMax,
                           OffsetDateTime validFrom, OffsetDateTime createdAt, String note) {
        paramRepo.insert(new ParamVersion(0, segmentId, versionNo, source, dMin, dMax,
                sMin, sMax, nMin, nMax, siltMin, siltMax, validFrom, null, createdAt,
                "seed", null, note));
    }

    private OffsetDateTime t(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, CST);
    }

    private void level(String node, double v1, double v2, double v3, OffsetDateTime ing) {
        obs("WATER_LEVEL", "NODE", node, v1, null, "m", t(2026, 7, 14, 3, 0), ing);
        obs("WATER_LEVEL", "NODE", node, v2, null, "m", t(2026, 7, 14, 4, 0), ing);
        obs("WATER_LEVEL", "NODE", node, v3, null, "m", t(2026, 7, 14, 5, 0), ing);
    }

    private void flow(String seg, double v1, double v2, double v3, OffsetDateTime ing) {
        obs("FLOW", "SEGMENT", seg, v1, null, "L/s", t(2026, 7, 14, 3, 0), ing);
        obs("FLOW", "SEGMENT", seg, v2, null, "L/s", t(2026, 7, 14, 4, 0), ing);
        obs("FLOW", "SEGMENT", seg, v3, null, "L/s", t(2026, 7, 14, 5, 0), ing);
    }

    private void obs(String type, String targetType, String targetCode, Double valueNum,
                     String valueText, String unit, OffsetDateTime sampledAt, OffsetDateTime ingestedAt) {
        obsRepo.insert(new Observation(0, type, targetType, targetCode, valueNum, valueText,
                unit, sampledAt, ingestedAt, "监测中心", "MON-2026.07", "CALIBRATED"));
    }
}
