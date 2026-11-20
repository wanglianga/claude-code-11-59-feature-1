package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.GeometryMapping;
import com.citydrain.capacity.domain.InspectionCoverage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** 检查覆盖与几何版本映射只读存储。 */
@Repository
public class DefectContextRepo {

    public static final RowMapper<InspectionCoverage> COVERAGE_MAPPER = (rs, i) -> new InspectionCoverage(
            rs.getLong("id"),
            rs.getLong("segment_id"),
            rs.getString("geometry_version"),
            rs.getInt("inspection_year"),
            rs.getDouble("covered_start_m"),
            rs.getDouble("covered_end_m"),
            rs.getObject("inspected_at", OffsetDateTime.class),
            rs.getString("source_system"),
            rs.getString("source_version"),
            rs.getObject("registered_at", OffsetDateTime.class)
    );

    public static final RowMapper<GeometryMapping> MAPPING_MAPPER = (rs, i) -> new GeometryMapping(
            rs.getLong("id"),
            rs.getLong("segment_id"),
            rs.getString("from_geometry_version"),
            rs.getString("to_geometry_version"),
            rs.getDouble("from_start_m"),
            rs.getDouble("from_end_m"),
            rs.getDouble("to_start_m"),
            rs.getDouble("to_end_m"),
            rs.getString("note"),
            rs.getObject("registered_at", OffsetDateTime.class)
    );

    private final JdbcClient db;

    public DefectContextRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insertCoverage(InspectionCoverage c) {
        return db.sql("""
                INSERT INTO inspection_coverage(segment_id, geometry_version, inspection_year,
                    covered_start_m, covered_end_m, inspected_at, source_system, source_version, registered_at)
                VALUES (?,?,?,?,?,?,?,?,COALESCE(?, now())) RETURNING id
                """)
                .params(c.segmentId(), c.geometryVersion(), c.inspectionYear(),
                        c.coveredStartM(), c.coveredEndM(), c.inspectedAt(),
                        c.sourceSystem(), c.sourceVersion(), c.registeredAt())
                .query(Long.class).single();
    }

    public long insertMapping(GeometryMapping m) {
        return db.sql("""
                INSERT INTO geometry_mapping(segment_id, from_geometry_version, to_geometry_version,
                    from_start_m, from_end_m, to_start_m, to_end_m, note, registered_at)
                VALUES (?,?,?,?,?,?,?,?,COALESCE(?, now())) RETURNING id
                """)
                .params(m.segmentId(), m.fromGeometryVersion(), m.toGeometryVersion(),
                        m.fromStartM(), m.fromEndM(), m.toStartM(), m.toEndM(), m.note(),
                        m.registeredAt())
                .query(Long.class).single();
    }

    public List<InspectionCoverage> listCoverage(long segmentId, OffsetDateTime asOf) {
        return db.sql("""
                SELECT * FROM inspection_coverage
                WHERE segment_id = ? AND registered_at <= ?
                ORDER BY inspection_year, covered_start_m
                """)
                .params(segmentId, asOf).query(COVERAGE_MAPPER).list();
    }

    public List<GeometryMapping> listMappings(long segmentId, OffsetDateTime asOf) {
        return db.sql("""
                SELECT * FROM geometry_mapping
                WHERE segment_id = ? AND registered_at <= ?
                ORDER BY id
                """)
                .params(segmentId, asOf).query(MAPPING_MAPPER).list();
    }
}
