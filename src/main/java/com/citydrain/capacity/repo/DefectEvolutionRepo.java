package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.DefectEvolutionRun;
import com.citydrain.capacity.domain.DefectTrajectory;
import com.citydrain.capacity.domain.TrajectoryCandidateLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 缺陷演化运行 / 轨迹 / 候选连接存储。 */
@Repository
public class DefectEvolutionRepo {

    public static final RowMapper<DefectEvolutionRun> RUN_MAPPER = (rs, i) -> new DefectEvolutionRun(
            rs.getLong("id"),
            rs.getString("run_code"),
            rs.getLong("segment_id"),
            rs.getString("method"),
            rs.getObject("as_of", OffsetDateTime.class),
            rs.getString("status"),
            rs.getString("summary"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    public static final RowMapper<DefectTrajectory> TRAJ_MAPPER = (rs, i) -> new DefectTrajectory(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getString("trajectory_code"),
            rs.getString("status"),
            rs.getString("continuity"),
            rs.getString("observation_ids"),
            rs.getString("comparisons"),
            rs.getString("change_range"),
            rs.getString("ambiguity"),
            rs.getString("evidence")
    );

    public static final RowMapper<TrajectoryCandidateLink> LINK_MAPPER = (rs, i) -> new TrajectoryCandidateLink(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getLong("from_observation_id"),
            rs.getLong("to_observation_id"),
            rs.getDouble("score"),
            (Double) rs.getObject("overlap_ratio"),
            rs.getBoolean("accepted"),
            rs.getString("reason")
    );

    private final JdbcClient db;

    public DefectEvolutionRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insertRun(String runCode, long segmentId, String method, OffsetDateTime asOf) {
        return db.sql("INSERT INTO defect_evolution_run(run_code, segment_id, method, as_of) VALUES (?,?,?,?) RETURNING id")
                .params(runCode, segmentId, method, asOf)
                .query(Long.class).single();
    }

    public void finishRun(long runId, String summaryJson) {
        db.sql("UPDATE defect_evolution_run SET status = 'COMPLETED', summary = ? WHERE id = ?")
                .params(Jsonb.of(summaryJson), runId).update();
    }

    public Optional<DefectEvolutionRun> findRun(long segmentId, String method, OffsetDateTime asOf) {
        return db.sql("SELECT * FROM defect_evolution_run WHERE segment_id = ? AND method = ? AND as_of = ?")
                .params(segmentId, method, asOf).query(RUN_MAPPER).optional();
    }

    public Optional<DefectEvolutionRun> findRunById(long runId) {
        return db.sql("SELECT * FROM defect_evolution_run WHERE id = ?").params(runId).query(RUN_MAPPER).optional();
    }

    public List<DefectEvolutionRun> listRunsBySegment(long segmentId) {
        return db.sql("SELECT * FROM defect_evolution_run WHERE segment_id = ? ORDER BY id")
                .params(segmentId).query(RUN_MAPPER).list();
    }

    public long insertTrajectory(long runId, String code, String status, String continuity,
                                 String observationIdsJson, String comparisonsJson,
                                 String changeRangeJson, String ambiguityJson, String evidenceJson) {
        return db.sql("""
                INSERT INTO defect_trajectory(run_id, trajectory_code, status, continuity,
                    observation_ids, comparisons, change_range, ambiguity, evidence)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """)
                .params(runId, code, status, continuity,
                        Jsonb.of(observationIdsJson), Jsonb.of(comparisonsJson),
                        Jsonb.of(changeRangeJson), Jsonb.of(ambiguityJson), Jsonb.of(evidenceJson))
                .query(Long.class).single();
    }

    public List<DefectTrajectory> listTrajectories(long runId) {
        return db.sql("SELECT * FROM defect_trajectory WHERE run_id = ? ORDER BY trajectory_code")
                .params(runId).query(TRAJ_MAPPER).list();
    }

    public void insertCandidateLink(long runId, long fromObs, long toObs, double score,
                                    Double overlapRatio, boolean accepted, String reason) {
        db.sql("""
                INSERT INTO trajectory_candidate_link(run_id, from_observation_id, to_observation_id,
                    score, overlap_ratio, accepted, reason)
                VALUES (?,?,?,?,?,?,?)
                """)
                .params(runId, fromObs, toObs, score, overlapRatio, accepted, reason).update();
    }

    public List<TrajectoryCandidateLink> listCandidateLinks(long runId) {
        return db.sql("SELECT * FROM trajectory_candidate_link WHERE run_id = ? ORDER BY id")
                .params(runId).query(LINK_MAPPER).list();
    }
}
