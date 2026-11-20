-- V2 缺陷形态演化推导 Schema：多期缺陷观测 / 检查覆盖 / 几何版本映射 / 演化运行 / 缺陷轨迹
--
-- 设计要点：
-- 1) 每次缺陷观察保存起止里程、长度/宽度区间、检测分辨率、定位不确定度、观察日期与几何版本，
--    作为原始判读只读存储（无 update/delete）——演化运行只追加分析，不重写原始判读。
-- 2) 检查覆盖按“巡检年份 + 覆盖里程段”存储，用于判断中间年份是否有覆盖（缺覆盖不得宣称持续劣化）。
-- 3) 几何版本映射仅声明明确的旧→新里程换算范围；管段改造后只有落在映射范围内的观测才可跨几何连接。
-- 4) 演化运行按 (segment, method, as_of) 幂等；轨迹、候选连接、变化范围与排序均为独立追加结果。

-- 缺陷观测：一条“已确认”的缺陷在某次巡检中的一次观察
CREATE TABLE defect_observation (
    id                       BIGSERIAL PRIMARY KEY,
    segment_id               BIGINT NOT NULL REFERENCES pipe_segment(id),
    geometry_version         TEXT NOT NULL,             -- 几何版本（改造前后不同）
    defect_code              TEXT NOT NULL,             -- 外部缺陷编号（同一缺陷跨期可复用，但不作为连接依据）
    defect_type              TEXT NOT NULL,             -- 裂缝 / 变形 / 渗漏 等
    observed_at              TIMESTAMPTZ NOT NULL,      -- 观察日期
    chainage_start_m         DOUBLE PRECISION NOT NULL, -- 起里程（沿管轴，米）
    chainage_end_m           DOUBLE PRECISION NOT NULL, -- 止里程（沿管轴，米）
    length_min_m             DOUBLE PRECISION NOT NULL, -- 裂缝长度区间下界（米）
    length_max_m             DOUBLE PRECISION NOT NULL, -- 裂缝长度区间上界（米）
    width_min_mm             DOUBLE PRECISION,          -- 宽度区间下界（毫米，可空=未测）
    width_max_mm             DOUBLE PRECISION,          -- 宽度区间上界（毫米，可空=未测）
    detection_resolution_mm  DOUBLE PRECISION NOT NULL DEFAULT 0, -- 检测分辨率（毫米/可分辨最小尺寸）
    position_uncertainty_m   DOUBLE PRECISION NOT NULL DEFAULT 0, -- 摄像车定位不确定度（米）
    source_system            TEXT NOT NULL,
    source_version           TEXT NOT NULL,
    external_ref_id          BIGINT REFERENCES external_ref(id),
    registered_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (chainage_end_m >= chainage_start_m),
    CHECK (length_min_m >= 0),
    CHECK (length_max_m >= length_min_m),
    CHECK (width_min_mm IS NULL OR width_min_mm >= 0),
    CHECK (width_min_mm IS NULL OR width_max_mm IS NULL OR width_max_mm >= width_min_mm),
    CHECK (detection_resolution_mm >= 0),
    CHECK (position_uncertainty_m >= 0),
    -- 同一来源同一期同一缺陷编号只登记一次（防止重复入库造成双计）
    UNIQUE (source_system, source_version, defect_code, observed_at)
);
CREATE INDEX idx_defect_obs_segment ON defect_observation (segment_id, observed_at);
CREATE INDEX idx_defect_obs_registered ON defect_observation (registered_at);

-- 检查覆盖：某巡检年份中，沿管段实际完成检测的里程范围
CREATE TABLE inspection_coverage (
    id                BIGSERIAL PRIMARY KEY,
    segment_id        BIGINT NOT NULL REFERENCES pipe_segment(id),
    geometry_version  TEXT NOT NULL,
    inspection_year   INT NOT NULL,
    covered_start_m   DOUBLE PRECISION NOT NULL,
    covered_end_m     DOUBLE PRECISION NOT NULL,
    inspected_at      TIMESTAMPTZ NOT NULL,
    source_system     TEXT NOT NULL,
    source_version    TEXT NOT NULL,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (covered_end_m >= covered_start_m)
);
CREATE INDEX idx_cov_segment_year ON inspection_coverage (segment_id, inspection_year);

-- 几何版本映射：明确声明旧几何里程→新几何里程的换算范围（改造后仅此范围内可连接）
CREATE TABLE geometry_mapping (
    id                   BIGSERIAL PRIMARY KEY,
    segment_id           BIGINT NOT NULL REFERENCES pipe_segment(id),
    from_geometry_version TEXT NOT NULL,
    to_geometry_version   TEXT NOT NULL,
    from_start_m         DOUBLE PRECISION NOT NULL,
    from_end_m           DOUBLE PRECISION NOT NULL,
    to_start_m           DOUBLE PRECISION NOT NULL,
    to_end_m             DOUBLE PRECISION NOT NULL,
    note                 TEXT,
    registered_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_end_m >= from_start_m),
    CHECK (to_end_m >= to_start_m)
);
CREATE INDEX idx_geom_map_segment ON geometry_mapping (segment_id, from_geometry_version, to_geometry_version);

-- 演化运行：管段 + 连接路线 + as_of；同键幂等
CREATE TABLE defect_evolution_run (
    id          BIGSERIAL PRIMARY KEY,
    run_code    TEXT NOT NULL UNIQUE,
    segment_id  BIGINT NOT NULL REFERENCES pipe_segment(id),
    method      TEXT NOT NULL,          -- RULE_BASED / GLOBAL_CONSTRAINT
    as_of       TIMESTAMPTZ NOT NULL,
    status      TEXT NOT NULL DEFAULT 'COMPLETED',
    summary     JSONB,                  -- 网络汇总、排序、排序影响、路线建议、歧义统计
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (segment_id, method, as_of)
);

-- 缺陷轨迹：由多期观测连接而成的一条演化链（不重写原始观测）
CREATE TABLE defect_trajectory (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES defect_evolution_run(id) ON DELETE CASCADE,
    trajectory_code TEXT NOT NULL,
    status          TEXT NOT NULL,      -- PERSISTENT / POSSIBLY_EXPANDED / POSSIBLY_NEW / POSSIBLY_DISAPPEARED / NOT_COMPARABLE
    continuity      TEXT NOT NULL,      -- CONTINUOUS / COVERAGE_GAP / GEOMETRY_REMAPPED
    observation_ids JSONB NOT NULL,
    comparisons     JSONB,              -- 相邻两期比较明细（重叠比例、属性容差、变化区间）
    change_range    JSONB,              -- 独立变化范围分析结果（供外部整治排序引用）
    ambiguity       JSONB,              -- 竞争/歧义信息（保留不确定性而不双计）
    evidence        JSONB,
    UNIQUE (run_id, trajectory_code)
);
CREATE INDEX idx_traj_run ON defect_trajectory (run_id);

-- 候选连接：每条可行连接（采用/未采用）均可回查，供工程师复核规则与整体约束
CREATE TABLE trajectory_candidate_link (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES defect_evolution_run(id) ON DELETE CASCADE,
    from_observation_id BIGINT NOT NULL,
    to_observation_id   BIGINT NOT NULL,
    score               DOUBLE PRECISION NOT NULL,
    overlap_ratio       DOUBLE PRECISION,
    accepted            BOOLEAN NOT NULL,
    reason              TEXT
);
CREATE INDEX idx_cand_run ON trajectory_candidate_link (run_id);
