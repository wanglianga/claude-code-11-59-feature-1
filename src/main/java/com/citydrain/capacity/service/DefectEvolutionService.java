package com.citydrain.capacity.service;

import com.citydrain.capacity.config.DefectEvolutionProps;
import com.citydrain.capacity.domain.DefectEvolutionRun;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.GeometryMapping;
import com.citydrain.capacity.domain.InspectionCoverage;
import com.citydrain.capacity.domain.LinkMethod;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.TrajectoryCandidateLink;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.DefectContextRepo;
import com.citydrain.capacity.repo.DefectEvolutionRepo;
import com.citydrain.capacity.repo.DefectObservationRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.citydrain.capacity.service.evolution.CandidateLinker;
import com.citydrain.capacity.service.evolution.TrajectoryAnalyzer;
import com.citydrain.capacity.service.evolution.TrajectoryLinker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缺陷形态演化推导编排。
 * 输入：某管段在 as_of 前已入库的多期已确认缺陷观测 + 检查覆盖 + 几何映射。
 * 输出（追加，不重写原始判读）：轨迹集合、变化状态、独立变化范围、候选连接、排序影响。
 * 同 (segment, method, as_of) 幂等：重复提交返回既有运行（replayed=true），逐位一致。
 */
@Service
public class DefectEvolutionService {

    private final SegmentRepo segmentRepo;
    private final DefectObservationRepo obsRepo;
    private final DefectContextRepo contextRepo;
    private final DefectEvolutionRepo runRepo;
    private final DefectEvolutionProps props;
    private final ObjectMapper objectMapper;

    public DefectEvolutionService(SegmentRepo segmentRepo, DefectObservationRepo obsRepo,
                                  DefectContextRepo contextRepo, DefectEvolutionRepo runRepo,
                                  DefectEvolutionProps props, ObjectMapper objectMapper) {
        this.segmentRepo = segmentRepo;
        this.obsRepo = obsRepo;
        this.contextRepo = contextRepo;
        this.runRepo = runRepo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public record RunOutcome(DefectEvolutionRun run, boolean replayed) {}

    @Transactional
    public RunOutcome run(long segmentId, LinkMethod method, OffsetDateTime asOf) {
        Segment segment = segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + segmentId));
        OffsetDateTime effectiveAsOf = (asOf != null ? asOf : OffsetDateTime.now())
                .truncatedTo(ChronoUnit.SECONDS);

        var existing = runRepo.findRun(segmentId, method.name(), effectiveAsOf);
        if (existing.isPresent()) {
            return new RunOutcome(existing.get(), true);
        }

        List<DefectObservation> observations = obsRepo.forEvolution(segmentId, effectiveAsOf);
        if (observations.isEmpty()) {
            throw ApiException.unprocessable("该管段在 as_of 前无缺陷观测，无法进行演化推导");
        }
        List<InspectionCoverage> coverage = contextRepo.listCoverage(segmentId, effectiveAsOf);
        List<GeometryMapping> mappings = contextRepo.listMappings(segmentId, effectiveAsOf);

        // 1) 连接（两路线各自独立求解，互不预处理/补丁）
        TrajectoryLinker.LinkResult link = TrajectoryLinker.link(
                observations, mappings, props, method.name());

        // 2) 分析每条链
        TrajectoryAnalyzer.Context ctx = new TrajectoryAnalyzer.Context(
                observations, coverage, mappings, link.allCandidates(),
                link.acceptedPairKeys(), props);
        List<TrajectoryAnalyzer.TrajectoryResult> results = new ArrayList<>();
        int idx = 1;
        for (List<Long> chain : link.chains()) {
            results.add(TrajectoryAnalyzer.analyze(chain, idx++, ctx));
        }

        // 3) 独立变化范围 + 整治排序（不双计：每条轨迹独立计数，竞争只标歧义）
        Map<String, Object> summary = buildSummary(segment, method, observations, coverage,
                results, link);

        String runCode = "EVO-" + segmentId + "-" + method.name() + "-" + effectiveAsOf.toEpochSecond();
        long runId = runRepo.insertRun(runCode, segmentId, method.name(), effectiveAsOf);
        try {
            Set<String> acceptedPairs = new LinkedHashSet<>();
            for (TrajectoryAnalyzer.TrajectoryResult r : results) {
                for (int i = 0; i + 1 < r.observationIds().size(); i++) {
                    acceptedPairs.add(r.observationIds().get(i) + "->" + r.observationIds().get(i + 1));
                }
            }
            for (TrajectoryAnalyzer.TrajectoryResult r : results) {
                runRepo.insertTrajectory(runId, r.trajectoryCode(), r.status(), r.continuity(),
                        write(r.observationIds()), write(r.comparisons()),
                        write(r.changeRange()), write(r.ambiguity()), write(r.evidence()));
            }
            for (CandidateLinker.Edge e : link.allCandidates()) {
                boolean accepted = acceptedPairs.contains(e.fromId() + "->" + e.toId());
                String reason = e.feasible()
                        ? (accepted ? "ACCEPTED" : "FEASIBLE_NOT_CHOSEN")
                        : e.rejectReason();
                runRepo.insertCandidateLink(runId, e.fromId(), e.toId(), e.score(),
                        e.overlapRatio(), accepted, reason);
            }
            runRepo.finishRun(runId, write(summary));
        } catch (Exception e) {
            throw ApiException.badRequest("演化结果序列化失败: " + e.getMessage());
        }
        return new RunOutcome(runRepo.findRunById(runId).orElseThrow(), false);
    }

    // ---------------------------------------------------------------- summary

    private Map<String, Object> buildSummary(Segment segment, LinkMethod method,
                                             List<DefectObservation> observations,
                                             List<InspectionCoverage> coverage,
                                             List<TrajectoryAnalyzer.TrajectoryResult> results,
                                             TrajectoryLinker.LinkResult link) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("segmentCode", segment.segmentCode());
        summary.put("method", method.name());
        summary.put("observationCount", observations.size());
        summary.put("periods", observations.stream().map(o -> o.observedAt().getYear()).distinct().sorted().toList());
        summary.put("coverageYears", coverage.stream().map(InspectionCoverage::inspectionYear)
                .distinct().sorted().toList());
        summary.put("trajectoryCount", results.size());

        // 状态分布
        Map<String, Integer> statusCount = new LinkedHashMap<>();
        for (TrajectoryAnalyzer.TrajectoryResult r : results) {
            statusCount.merge(r.status(), 1, Integer::sum);
        }
        summary.put("statusDistribution", statusCount);

        // 独立变化范围（每条轨迹一条；供外部整治排序引用）
        List<Map<String, Object>> changeRanges = new ArrayList<>();
        for (TrajectoryAnalyzer.TrajectoryResult r : results) {
            Map<String, Object> cr = new LinkedHashMap<>();
            cr.put("trajectoryCode", r.trajectoryCode());
            cr.put("status", r.status());
            cr.put("continuity", r.continuity());
            cr.put("observationIds", r.observationIds());
            cr.put("changeRange", r.changeRange());
            changeRanges.add(cr);
        }
        summary.put("changeRanges", changeRanges);

        // 整治排序：只对可比且支持扩张的轨迹给出排序；排序影响按“检测分辨率/测量边界”折算
        summary.put("remediationRanking", buildRanking(results, segment));
        summary.put("rankingImpact", buildRankingImpact(results));

        // 歧义/不双计
        List<Map<String, Object>> contested = new ArrayList<>();
        for (TrajectoryAnalyzer.TrajectoryResult r : results) {
            if (r.ambiguity().contested()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("trajectoryCode", r.trajectoryCode());
                m.put("competitors", r.ambiguity().competitors());
                m.put("note", r.ambiguity().note());
                contested.add(m);
            }
        }
        summary.put("contested", contested);
        summary.put("noDoubleCountNote", "每条观测只计入一条轨迹一次；竞争连接仅记录歧义，不复制观测、不重复计数");

        // 候选连接统计
        long feasible = link.allCandidates().stream().filter(CandidateLinker.Edge::feasible).count();
        long accepted = link.allCandidates().stream().filter(e -> isAcceptedEdge(e, results)).count();
        summary.put("candidateLinks", Map.of(
                "feasible", feasible,
                "rejected", link.allCandidates().size() - feasible,
                "accepted", accepted));

        summary.put("routeGuidance", RouteGuidance.describe(observations, coverage, props));
        summary.put("methodDiscipline", method == LinkMethod.GLOBAL_CONSTRAINT
                ? "整体约束：每个相邻观测期边界对全部观测求解一对一指派（全局最优），组成轨迹集合；非规则失败后的补救"
                : "规则匹配：相邻年份重叠+属性容差逐段贪心；规则透明、可逐步复核，但高密度区可能多候选");
        return summary;
    }

    private boolean isAcceptedEdge(CandidateLinker.Edge e,
                                   List<TrajectoryAnalyzer.TrajectoryResult> results) {
        for (TrajectoryAnalyzer.TrajectoryResult r : results) {
            for (int i = 0; i + 1 < r.observationIds().size(); i++) {
                if (r.observationIds().get(i) == e.fromId()
                        && r.observationIds().get(i + 1) == e.toId()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildRanking(List<TrajectoryAnalyzer.TrajectoryResult> results,
                                                   Segment segment) {
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (TrajectoryAnalyzer.TrajectoryResult r : results) {
            if (!Vocab.TRAJ_POSSIBLY_EXPANDED.equals(r.status())) {
                continue;
            }
            TrajectoryAnalyzer.ChangeRange cr = r.changeRange();
            if (cr == null || !cr.comparable()) {
                continue;
            }
            double supportedMin = cr.supportedChangeBeyondBoundaryM()[0];
            double supportedMax = cr.supportedChangeBeyondBoundaryM()[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trajectoryCode", r.trajectoryCode());
            m.put("segmentCode", segment.segmentCode());
            m.put("fromObservationId", cr.fromId());
            m.put("toObservationId", cr.toId());
            m.put("spanYears", cr.spanYears());
            m.put("supportedIncreaseMinM", supportedMin);
            m.put("supportedIncreaseMaxM", supportedMax);
            m.put("orderMetric", supportedMin); // 保守：以可支持的最小扩展量排序
            m.put("basis", "POSSIBLY_EXPANDED 且连续可比；以扣除测量边界后的最小扩展量排序（保守排序）");
            ranking.add(m);
        }
        ranking.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> (double) m.get("orderMetric")).reversed());
        int rank = 1;
        for (Map<String, Object> m : ranking) {
            m.put("rank", rank++);
        }
        return ranking;
    }

    private Map<String, Object> buildRankingImpact(List<TrajectoryAnalyzer.TrajectoryResult> results) {
        Map<String, Object> impact = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        for (TrajectoryAnalyzer.TrajectoryResult r : results) {
            if (Vocab.TRAJ_POSSIBLY_EXPANDED.equals(r.status())) {
                continue;
            }
            String reason = switch (r.status()) {
                case Vocab.TRAJ_NOT_COMPARABLE -> "NOT_COMPARABLE：缺覆盖/几何未映射/定位歧义，不进入整治排序";
                case Vocab.TRAJ_PERSISTENT -> "PERSISTENT：测量边界内无支持扩展，暂不排序";
                case Vocab.TRAJ_POSSIBLY_NEW -> "POSSIBLY_NEW：仅一期且前期有覆盖，需下一期确认后再排序";
                case Vocab.TRAJ_POSSIBLY_DISAPPEARED -> "POSSIBLY_DISAPPEARED：后续有覆盖但无后继，不按扩展排序";
                default -> "未支持扩展";
            };
            excluded.add(r.trajectoryCode() + " — " + reason);
        }
        impact.put("excludedFromRanking", excluded);
        impact.put("note",
                "排序只纳入 POSSIBLY_EXPANDED 且连续的轨迹；缺覆盖/几何未映射/歧义轨迹被排除以避免错误排序。"
                        + "变化幅度已扣除测量边界，定位不确定度只影响连接、不放大扩展量。");
        return impact;
    }

    // ---------------------------------------------------------------- 查询

    public DefectEvolutionRun getRun(long runId) {
        return runRepo.findRunById(runId)
                .orElseThrow(() -> ApiException.notFound("演化运行不存在: " + runId));
    }

    public List<com.citydrain.capacity.domain.DefectTrajectory> getTrajectories(long runId) {
        return runRepo.listTrajectories(runId);
    }

    public List<TrajectoryCandidateLink> getCandidates(long runId) {
        return runRepo.listCandidateLinks(runId);
    }

    public List<DefectEvolutionRun> listRuns(long segmentId) {
        return runRepo.listRunsBySegment(segmentId);
    }

    /** 读取 summary 中的独立变化范围（供外部整治排序系统引用）。 */
    public Object getChangeRanges(long runId) {
        DefectEvolutionRun run = getRun(runId);
        return readSummaryField(run, "changeRanges");
    }

    public Object getRanking(long runId) {
        DefectEvolutionRun run = getRun(runId);
        return readSummaryField(run, "remediationRanking");
    }

    public Object getRankingImpact(long runId) {
        DefectEvolutionRun run = getRun(runId);
        return readSummaryField(run, "rankingImpact");
    }

    public Object getRouteGuidance(long runId) {
        DefectEvolutionRun run = getRun(runId);
        return readSummaryField(run, "routeGuidance");
    }

    private Object readSummaryField(DefectEvolutionRun run, String field) {
        try {
            if (run.summaryJson() == null) {
                return null;
            }
            return objectMapper.readTree(run.summaryJson()).path(field);
        } catch (Exception e) {
            return null;
        }
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw ApiException.badRequest("序列化失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 登记

    @Transactional
    public DefectObservation registerObservation(DefectObservation o) {
        segmentRepo.findById(o.segmentId())
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + o.segmentId()));
        if (o.chainageEndM() < o.chainageStartM()) {
            throw ApiException.unprocessable("止里程不得小于起里程");
        }
        if (o.lengthMaxM() < o.lengthMinM()) {
            throw ApiException.unprocessable("长度区间上界不得小于下界");
        }
        try {
            long id = obsRepo.insert(o);
            return obsRepo.findById(id).orElseThrow();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw ApiException.conflict("相同来源/版本/缺陷编号/观察日期的观测已登记");
            }
            throw ApiException.badRequest("缺陷观测入库失败: " + e.getMessage());
        }
    }

    @Transactional
    public InspectionCoverage registerCoverage(InspectionCoverage c) {
        segmentRepo.findById(c.segmentId())
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + c.segmentId()));
        if (c.coveredEndM() < c.coveredStartM()) {
            throw ApiException.unprocessable("覆盖止里程不得小于起里程");
        }
        long id = contextRepo.insertCoverage(c);
        return contextRepo.listCoverage(c.segmentId(), OffsetDateTime.now().plusYears(100)).stream()
                .filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public GeometryMapping registerMapping(GeometryMapping m) {
        segmentRepo.findById(m.segmentId())
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + m.segmentId()));
        if (m.fromGeometryVersion().equals(m.toGeometryVersion())) {
            throw ApiException.unprocessable("几何映射的旧/新版本不得相同");
        }
        if (m.fromEndM() < m.fromStartM() || m.toEndM() < m.toStartM()) {
            throw ApiException.unprocessable("几何映射里程范围非法");
        }
        long id = contextRepo.insertMapping(m);
        return contextRepo.listMappings(m.segmentId(), OffsetDateTime.now().plusYears(100)).stream()
                .filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    public List<DefectObservation> listObservations(long segmentId) {
        segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + segmentId));
        return obsRepo.listBySegment(segmentId);
    }
}
