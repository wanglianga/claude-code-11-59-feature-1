package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.DefectEvolutionRun;
import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.DefectTrajectory;
import com.citydrain.capacity.domain.GeometryMapping;
import com.citydrain.capacity.domain.InspectionCoverage;
import com.citydrain.capacity.domain.LinkMethod;
import com.citydrain.capacity.domain.TrajectoryCandidateLink;
import com.citydrain.capacity.service.DefectEvolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺陷形态演化推导接口。
 * 登记多期缺陷观测 / 检查覆盖 / 几何映射；按管段+路线+as_of 运行演化推导，
 * 输出轨迹、变化状态、独立变化范围与整治排序影响。原始判读只读、不重写。
 */
@RestController
@RequestMapping("/api/defect-evolution")
@Tag(name = "缺陷形态演化推导")
public class DefectEvolutionController {

    private final DefectEvolutionService service;
    private final ObjectMapper objectMapper;

    public DefectEvolutionController(DefectEvolutionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public record ObservationReq(@NotNull Long segmentId,
                                 @NotBlank String geometryVersion,
                                 @NotBlank String defectCode,
                                 @NotBlank String defectType,
                                 @NotNull OffsetDateTime observedAt,
                                 @NotNull Double chainageStartM,
                                 @NotNull Double chainageEndM,
                                 @NotNull Double lengthMinM,
                                 @NotNull Double lengthMaxM,
                                 Double widthMinMm,
                                 Double widthMaxMm,
                                 @PositiveOrZero double detectionResolutionMm,
                                 @PositiveOrZero double positionUncertaintyM,
                                 String sourceSystem,
                                 String sourceVersion,
                                 OffsetDateTime registeredAt) {}

    public record CoverageReq(@NotNull Long segmentId,
                              @NotBlank String geometryVersion,
                              @NotNull Integer inspectionYear,
                              @NotNull Double coveredStartM,
                              @NotNull Double coveredEndM,
                              @NotNull OffsetDateTime inspectedAt,
                              String sourceSystem,
                              String sourceVersion,
                              OffsetDateTime registeredAt) {}

    public record MappingReq(@NotNull Long segmentId,
                             @NotBlank String fromGeometryVersion,
                             @NotBlank String toGeometryVersion,
                             @NotNull Double fromStartM,
                             @NotNull Double fromEndM,
                             @NotNull Double toStartM,
                             @NotNull Double toEndM,
                             String note) {}

    public record RunReq(@NotNull Long segmentId,
                         @NotNull LinkMethod method,
                         OffsetDateTime asOf) {}

    // ------------------------------------------------------------- 登记

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记一次缺陷观察（起止里程/长度宽度区间/检测分辨率/定位不确定度/观察日期/几何版本），只读不重写")
    public DefectObservation registerObservation(@Valid @RequestBody ObservationReq r) {
        return service.registerObservation(new DefectObservation(
                0, r.segmentId(), r.geometryVersion(), r.defectCode(), r.defectType(),
                r.observedAt(), r.chainageStartM(), r.chainageEndM(),
                r.lengthMinM(), r.lengthMaxM(), r.widthMinMm(), r.widthMaxMm(),
                r.detectionResolutionMm(), r.positionUncertaintyM(),
                orDefault(r.sourceSystem(), "CCTV"), orDefault(r.sourceVersion(), "MANUAL"),
                null, r.registeredAt()));
    }

    @GetMapping("/segments/{segmentId}/observations")
    @Operation(summary = "列出某管段的全部缺陷观察（原始判读）")
    public List<DefectObservation> listObservations(@PathVariable long segmentId) {
        return service.listObservations(segmentId);
    }

    @PostMapping("/coverage")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记某巡检年份的检查覆盖里程范围（用于判断中间年份是否有覆盖）")
    public InspectionCoverage registerCoverage(@Valid @RequestBody CoverageReq r) {
        return service.registerCoverage(new InspectionCoverage(
                0, r.segmentId(), r.geometryVersion(), r.inspectionYear(),
                r.coveredStartM(), r.coveredEndM(), r.inspectedAt(),
                orDefault(r.sourceSystem(), "INSPECTION"), orDefault(r.sourceVersion(), "MANUAL"),
                r.registeredAt()));
    }

    @PostMapping("/geometry-mappings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "声明几何版本映射（旧→新里程换算范围）；未声明映射的改造不可跨几何连接")
    public GeometryMapping registerMapping(@Valid @RequestBody MappingReq r) {
        return service.registerMapping(new GeometryMapping(
                0, r.segmentId(), r.fromGeometryVersion(), r.toGeometryVersion(),
                r.fromStartM(), r.fromEndM(), r.toStartM(), r.toEndM(), r.note(), null));
    }

    // ------------------------------------------------------------- 运行

    @PostMapping("/runs")
    @Operation(summary = "执行缺陷形态演化推导（同 segment+method+asOf 幂等；asOf 缺省为当前时刻）")
    public Map<String, Object> run(@Valid @RequestBody RunReq r) {
        DefectEvolutionService.RunOutcome outcome = service.run(r.segmentId(), r.method(), r.asOf());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", outcome.run().id());
        body.put("runCode", outcome.run().runCode());
        body.put("replayed", outcome.replayed());
        body.put("method", outcome.run().method());
        body.put("asOf", outcome.run().asOf());
        body.put("summary", json(outcome.run().summaryJson()));
        body.put("trajectories", service.getTrajectories(outcome.run().id()));
        return body;
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "演化运行详情（轨迹 + 汇总 + 排序 + 路线建议）")
    public Map<String, Object> getRun(@PathVariable long runId) {
        DefectEvolutionRun run = service.getRun(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", run);
        body.put("summary", json(run.summaryJson()));
        body.put("trajectories", service.getTrajectories(runId));
        return body;
    }

    @GetMapping("/segments/{segmentId}/runs")
    @Operation(summary = "列出某管段的历史演化运行")
    public List<DefectEvolutionRun> listRuns(@PathVariable long segmentId) {
        return service.listRuns(segmentId);
    }

    @GetMapping("/runs/{runId}/trajectories")
    @Operation(summary = "轨迹集合（多期观测连接 + 变化状态 + 连续性 + 歧义）")
    public List<DefectTrajectory> trajectories(@PathVariable long runId) {
        service.getRun(runId);
        return service.getTrajectories(runId);
    }

    @GetMapping("/runs/{runId}/candidate-links")
    @Operation(summary = "候选连接（采用/未采用均可回查，供工程师复核规则与整体约束）")
    public List<TrajectoryCandidateLink> candidateLinks(@PathVariable long runId) {
        service.getRun(runId);
        return service.getCandidates(runId);
    }

    @GetMapping("/runs/{runId}/change-ranges")
    @Operation(summary = "独立变化范围分析结果（供外部整治排序系统引用；不重写原始判读）")
    public Object changeRanges(@PathVariable long runId) {
        service.getRun(runId);
        return service.getChangeRanges(runId);
    }

    @GetMapping("/runs/{runId}/ranking")
    @Operation(summary = "整治排序（仅纳入可比且支持扩展的轨迹；排序影响透明列出）")
    public Object ranking(@PathVariable long runId) {
        service.getRun(runId);
        return service.getRanking(runId);
    }

    @GetMapping("/runs/{runId}/ranking-impact")
    @Operation(summary = "排序影响：哪些轨迹因缺覆盖/几何未映射/歧义被排除出排序")
    public Object rankingImpact(@PathVariable long runId) {
        service.getRun(runId);
        return service.getRankingImpact(runId);
    }

    @GetMapping("/runs/{runId}/route-guidance")
    @Operation(summary = "路线选择建议（按巡检密度、定位误差、是否需全段一致结果）")
    public Object routeGuidance(@PathVariable long runId) {
        service.getRun(runId);
        return service.getRouteGuidance(runId);
    }

    private String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private Object json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }
}
