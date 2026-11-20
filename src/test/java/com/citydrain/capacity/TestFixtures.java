package com.citydrain.capacity;

import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.GeometryMapping;
import com.citydrain.capacity.domain.InspectionCoverage;
import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.repo.DefectContextRepo;
import com.citydrain.capacity.repo.DefectObservationRepo;
import com.citydrain.capacity.repo.EventRepo;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.NetworkVersionRepo;
import com.citydrain.capacity.repo.ObservationRepo;
import com.citydrain.capacity.repo.ParamVersionRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 测试夹具：两管段小网络 N1→S-A→N2→S-B→B1。 */
@Component
public class TestFixtures {

    public static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired NetworkVersionRepo nvRepo;
    @Autowired SegmentRepo segmentRepo;
    @Autowired ParamVersionRepo paramRepo;
    @Autowired ObservationRepo obsRepo;
    @Autowired EventRepo eventRepo;
    @Autowired ExternalRefRepo extRepo;
    @Autowired DefectObservationRepo defectRepo;
    @Autowired DefectContextRepo contextRepo;

    public long nvId;
    public long segA;
    public long segB;
    public long eventId;

    public static OffsetDateTime t(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, CST);
    }

    // ------------------------------------------------------------- 缺陷演化夹具

    /** 新建一条独立缺陷演化管段，避免与种子/其它测试碰撞（每次独立网络版本，避免跨事务复用已回滚的 nvId）。 */
    public long defectSegment(String code) {
        long defNv = nvRepo.insert("NV-DEFECT-" + code + "-" + System.nanoTime(), "缺陷演化测试网络");
        return segmentRepo.insert(defNv, code, "DN-" + code, "DN-" + code + "-N", 120, 1000,
                11.0, 10.8, 600);
    }

    public long dObs(long segmentId, String geo, String code, int year, double s, double e,
                     double lenMin, double lenMax, double posUnc) {
        return defectRepo.insert(new DefectObservation(0, segmentId, geo, code, "裂缝",
                t(year, 5, 10, 10, 0), s, e, lenMin, lenMax, 0.3, 0.5, 4.0, posUnc,
                "CCTV", "V" + year, null, t(year, 5, 13, 9, 0)));
    }

    public long dCover(long segmentId, String geo, int year, double start, double end) {
        return contextRepo.insertCoverage(new InspectionCoverage(0, segmentId, geo, year, start, end,
                t(year, 5, 10, 10, 0), "INSPECTION", "Y" + year, t(year, 5, 10, 10, 0)));
    }

    public long dMapping(long segmentId, String from, String to,
                         double fromStart, double fromEnd, double toStart, double toEnd) {
        return contextRepo.insertMapping(new GeometryMapping(0, segmentId, from, to,
                fromStart, fromEnd, toStart, toEnd, "test mapping", t(2026, 3, 1, 9, 0)));
    }

    /** 基础网络 + 参数版本（validFrom 2026-01-01，createdAt 2026-01-05）+ 事件。
     *  节点用 TN1/TN2/TB1，避免与种子数据（N1..N4/B1）在共享库中碰撞。 */
    public void baseNetwork() {
        nvId = nvRepo.insert("NV-TEST-" + System.nanoTime(), "测试网络");
        segA = segmentRepo.insert(nvId, "S-A", "TN1", "TN2", 100, 1000, 5.20, 5.00, 800);
        segB = segmentRepo.insert(nvId, "S-B", "TN2", "TB1", 100, 1000, 5.00, 4.80, 800);
        eventId = eventRepo.insert("EV-TEST-" + System.nanoTime(), "测试事件",
                t(2026, 7, 14, 2, 0), t(2026, 7, 14, 8, 0));
    }

    public long param(long segmentId, int versionNo, Double dMin, Double dMax,
                      Double nMin, Double nMax, OffsetDateTime validFrom, OffsetDateTime createdAt) {
        return paramRepo.insert(new ParamVersion(0, segmentId, versionNo, "SURVEY",
                dMin, dMax, 0.0019, 0.0021, nMin, nMax, 0.0, 0.05,
                validFrom, null, createdAt, "test", null, null));
    }

    public long obs(String type, String targetType, String targetCode, Double valueNum,
                    String valueText, OffsetDateTime sampledAt, OffsetDateTime ingestedAt) {
        return obsRepo.insert(new Observation(0, type, targetType, targetCode, valueNum, valueText,
                null, sampledAt, ingestedAt, "test", "v1", "RAW"));
    }
}
