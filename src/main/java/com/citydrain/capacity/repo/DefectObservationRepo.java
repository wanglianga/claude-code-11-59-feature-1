package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.DefectObservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 缺陷观测只读存储：只提供 insert / 查询，不提供 update/delete（不重写原始判读）。 */
@Repository
public class DefectObservationRepo {

    public static final RowMapper<DefectObservation> MAPPER = (rs, i) -> new DefectObservation(
            rs.getLong("id"),
            rs.getLong("segment_id"),
            rs.getString("geometry_version"),
            rs.getString("defect_code"),
            rs.getString("defect_type"),
            rs.getObject("observed_at", OffsetDateTime.class),
            rs.getDouble("chainage_start_m"),
            rs.getDouble("chainage_end_m"),
            rs.getDouble("length_min_m"),
            rs.getDouble("length_max_m"),
            (Double) rs.getObject("width_min_mm"),
            (Double) rs.getObject("width_max_mm"),
            rs.getDouble("detection_resolution_mm"),
            rs.getDouble("position_uncertainty_m"),
            rs.getString("source_system"),
            rs.getString("source_version"),
            (Long) rs.getObject("external_ref_id"),
            rs.getObject("registered_at", OffsetDateTime.class)
    );

    private final JdbcClient db;

    public DefectObservationRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(DefectObservation o) {
        return db.sql("""
                INSERT INTO defect_observation(segment_id, geometry_version, defect_code, defect_type,
                    observed_at, chainage_start_m, chainage_end_m, length_min_m, length_max_m,
                    width_min_mm, width_max_mm, detection_resolution_mm, position_uncertainty_m,
                    source_system, source_version, external_ref_id, registered_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,COALESCE(?, now())) RETURNING id
                """)
                .params(o.segmentId(), o.geometryVersion(), o.defectCode(), o.defectType(),
                        o.observedAt(), o.chainageStartM(), o.chainageEndM(),
                        o.lengthMinM(), o.lengthMaxM(), o.widthMinMm(), o.widthMaxMm(),
                        o.detectionResolutionMm(), o.positionUncertaintyM(),
                        o.sourceSystem(), o.sourceVersion(), o.externalRefId(), o.registeredAt())
                .query(Long.class)
                .single();
    }

    public Optional<DefectObservation> findById(long id) {
        return db.sql("SELECT * FROM defect_observation WHERE id = ?").params(id).query(MAPPER).optional();
    }

    public List<DefectObservation> listBySegment(long segmentId) {
        return db.sql("SELECT * FROM defect_observation WHERE segment_id = ? ORDER BY observed_at, id")
                .params(segmentId).query(MAPPER).list();
    }

    /**
     * 演化用查询：该管段在 asOf 前已入库的观测（迟到补录对历史复算不可见）。
     * 按观察日期升序。
     */
    public List<DefectObservation> forEvolution(long segmentId, OffsetDateTime asOf) {
        return db.sql("""
                SELECT * FROM defect_observation
                WHERE segment_id = ? AND registered_at <= ?
                ORDER BY observed_at, id
                """)
                .params(segmentId, asOf).query(MAPPER).list();
    }
}
