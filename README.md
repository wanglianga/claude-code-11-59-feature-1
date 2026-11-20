# 城市地下管网输送能力区间评估与运行分析平台

面向市政排水运行团队：在不同降雨、下游水位和闸门状态下，评估现有管段**还可承载多少流量**。
平台不把名义管径直接代入公式，而是以**经确认的测量区间**（有效断面、坡度、糙率、淤积/变形）
逐段传播不确定性，输出**可通过流量区间**、相对设计需求的余量、主导不确定参数与不可判定原因；
不同工况的峰值不会被拼成并不存在的瓶颈。

另含**缺陷形态演化推导**（§4.1）：在外部已确认的多期缺陷观测之上连接裂纹轨迹，区分"缺陷真的扩展 /
两次观察到不同裂缝 / 测量边界变宽 / 缺覆盖 / 几何改造"，输出持续/扩展/新发/消失/不可比较状态、
独立变化范围与整治排序影响——**不把年份标签排成折线就宣布劣化**。

- 技术栈：Java 25 · Spring Boot 3.5 · PostgreSQL 18（Flyway 迁移）· springdoc-openapi（Swagger UI）
- 交付形态：后端平台（REST API + Swagger UI），含 Dockerfile 与 docker-compose 一键启动

---

## 1. 快速启动

### 1.1 Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui/index.html
- OpenAPI 文档：http://localhost:8080/v3/api-docs
- PostgreSQL：localhost:5432（库/用户/密码均为 `ccdev`）

首次启动自动执行 Flyway 建表并写入种子数据（覆盖全部验收场景）。

### 1.2 本地开发

```bash
# 需要：JDK 25、Maven 3.9+、PostgreSQL（127.0.0.1:5432，库/用户/密码 ccdev）
mvn spring-boot:run          # 启动应用（自动建表+种子数据）
mvn test                     # 运行 40 个测试（含集成测试，需 PostgreSQL 在线）
```

配置项（环境变量）：`DB_URL`（默认 `jdbc:postgresql://127.0.0.1:5432/ccdev`）、
`DB_USER`/`DB_PASSWORD`（默认 `ccdev`）、`SERVER_PORT`（默认 8080）、`SEED_ENABLED`（默认 true）。

### 1.3 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `operator` | `operator123` | OPERATOR | 全部读写接口 |
| `viewer` | `viewer123` | VIEWER | 仅 GET 查询 |

Swagger UI 与 `/v3/api-docs` 无需凭据；`/api/**` 需要 Basic Auth。

---

## 2. 领域模型

| 库 | 表 | 内容 |
| --- | --- | --- |
| 资产参数库 | `network_version` / `pipe_segment` / `segment_parameter_version` | 管网版本、管段名义属性与设计需求；**经确认的**有效直径/坡度/糙率/淤积测量区间，按版本与有效时间窗管理 |
| 运行观测库 | `observation` | 流量/水位/降雨/下游边界/闸门状态，含采样时间 `sampled_at`、入库时间 `ingested_at`、校准信息 |
| 外部只读引用 | `external_ref` | 缺陷观测（位置范围/类别等级/形态属性）、检查覆盖、维修作用范围与验收结果；**只存引用及来源版本** |
| 水文事件 | `hydro_event` | 事件窗口；同一事件的观测组合边界 |
| 情景 | `scenario` | 事件 + 管网版本 + 路径 + 边界取值规则；闸门冲突拆分为条件情景 |
| 计算 | `computation_run` / `capacity_result` | 方法 + as_of 的运行；每段流量区间、余量、主导不确定参数、不可判定原因、证据 |
| 覆盖账本 | `coverage_ledger` | 本次计算中每个参数/观测的可用性（AVAILABLE/ASSUMED/MISSING/CONFLICTED） |
| 缺陷演化（V2） | `defect_observation` / `inspection_coverage` / `geometry_mapping` | 多期缺陷观察（起止里程/长度宽度区间/分辨率/定位误差/几何版本，只读）；巡检覆盖年份里程段；几何版本映射范围 |
| 缺陷演化（V2） | `defect_evolution_run` / `defect_trajectory` / `trajectory_candidate_link` | 演化运行（段+路线+as_of，幂等）；轨迹（状态/连续性/比较/变化范围/歧义）；候选连接（采用/未采用均可回查） |

## 3. 核心规则实现

| 规则 | 实现 |
| --- | --- |
| 参数版本无法对应计算时**不得用最新值回填历史** | 版本解析要求 `valid_from ≤ as_of < valid_to` **且** `created_at ≤ as_of`（知识截止）；无对应版本 → 该段不可判定 `NO_PARAMETER_VERSION_AT_AS_OF` |
| 缺糙率/下游水位 → **扩大区间并标记限制来源** | 糙率缺失用缺省区间 [0.009, 0.035] 并标 `ROUGHNESS_MISSING`；下游水位缺失假设尾水至管顶+2m 壅水并标 `DOWNSTREAM_LEVEL_MISSING`；假设写入覆盖账本与证据 |
| **时间未对齐的峰值不得合成**同一事件 | 校准样本要求流量与上/下游水位在 10 分钟容差内同刻出现，否则拒绝（计入账本）；情景仅按事件窗口组合边界，峰值时刻仅在 `peakNote` 中透明列出 |
| 维修记录 → 新参数版本 | 仅当 `acceptance.result=ACCEPTED` 且 `measuredChanges` 含实测区间且 `measurementRef` 非空（可追溯）才派生；旧版本关闭但**保留**（不自动抹去旧测量）；未实测项继承上一版本 |
| 闸门状态冲突 | 拆分条件情景（如 `G-S3=OPEN` / `G-S3=CLOSED`）分别计算，**不取平均**；CLOSED → 该段能力为 0 |
| 外部缺陷/覆盖/维修 | 只读引用接入，作证据上下文，**不重新定位缺陷、不裁决观测、不推导覆盖与维修状态**，不生成维修优先级 |
| 历史复算 | 同一 `(scenario, method, as_of)` 幂等：重复提交返回既有运行（`replayed=true`），结果逐位一致 |
| 迟到监测补录 | 观测可见性按 `ingested_at ≤ as_of` 判定：旧 as_of 复算看不到迟到数据，新 as_of 才纳入 |

## 4. 两条评估路线（相互独立，均产出可复算区间）

| | 路线A `INTERVAL_PROPAGATION` | 路线B `CALIBRATED_ENSEMBLE` |
| --- | --- | --- |
| 方法 | 工程水力公式逐段传播管径/糙率/坡度/有效断面上下界（圆管满流曼宁公式 Q=C·D_eff^(8/3)·√S_eff/n） | 经已知工况校准的水力情景集合：由同刻流量+水位反算糙率区间，多段联合传播流量与水位可行范围 |
| 耦合 | 逐段独立（本段下游节点水位） | 下游边界→上游逐段回水传播（回水及边界联动） |
| 优点 | 稳定、易逐项复核 | 能表达回水与边界联动、区间更贴合实测 |
| 代价 | 保守参数会放大区间，可能无法区分相近瓶颈 | 需要足量校准事件（<3 样本标 `CALIBRATION_INSUFFICIENT`），参数校准变化影响历史结果 |
| 选择依据 | 现场参数不确定度低、网络耦合弱、需逐项复核 | 校准事件充足、网络耦合强、需边界联动精度 |

两路线**互不作为对方的预处理或核验**，仅共享纯数学核心（曼宁公式与区间传播）。
同管段同边界条件下两路线结果可对照（见 §6 验证）。

## 4.1 缺陷形态演化推导（裂缝轨迹 / 变化状态 / 排序影响）

同一管段连续多年出现"轻度裂缝"时，不能把年份标签排成折线就宣布劣化。本能力在**外部已确认、
带位置范围的多期管段观测**之上，推导缺陷形态演化轨迹。**原始判读只读、不重写**：每次观察保存
裂缝起止里程、长度/宽度区间、检测分辨率、定位不确定度、观察日期与几何版本；演化运行只追加
轨迹、比较、变化范围与排序影响。

### 两条独立的连接路线（选择依据：巡检密度 / 定位误差 / 是否需全段一致）

| | 路线A `RULE_BASED` | 路线B `GLOBAL_CONSTRAINT` |
| --- | --- | --- |
| 方法 | 相邻年份观测区间重叠比例 + 长度量级/类型容差，逐段贪心连接 | 多期观测与位置误差作为整体，候选图为 DAG，求解**最大权顶点不相交路径覆盖**（等价最大权二分匹配，匈牙利算法），一次决定整段轨迹集合 |
| 优点 | 规则透明、工程师可逐步复核 | 减少局部贪心错连、可跳年换全局最优 |
| 代价 | 高密度裂缝区先到先得、可能多候选 | 求解规模与多种可行轨迹的解释成本 |
| 适用 | 巡检密度适中、定位误差相对里程间距小、缺陷有限 | 高密度裂缝区、定位误差大、需全段一致演化结果 |

两路线**各自从头独立求解全段**（均满足时间顺序与空间连续性），**互不作为对方的预处理或补丁**；
整体约束不是"规则匹配失败后的补救"。运行汇总的 `methodDiscipline` 与 `routeGuidance` 说明选择依据。

### 变化状态与纪律

- 状态：`PERSISTENT`（持续）/ `POSSIBLY_EXPANDED`（可能扩展）/ `POSSIBLY_NEW`（可能新发）/
  `POSSIBLY_DISAPPEARED`（可能消失）/ `NOT_COMPARABLE`（无法比较）。
- **测量边界**：扩张/收缩判定以两期**检测分辨率的较大者**为边界；变化区间整体超出边界才记 `SUPPORTED_INCREASE`，
  落在 `±边界` 内记 `WITHIN_MEASUREMENT_BOUNDARY`（不足以宣称变化）。定位不确定度只用于**连接**，
  不放大扩展量——两次巡检分辨率/定位误差不同也不会被强行排成折线。
- **缺覆盖不劣化**：相邻观测跨年且中间年份在该位置无覆盖 → `NOT_COMPARABLE` / `COVERAGE_GAP`，
  端点比较仅供参考，不构成持续劣化结论。
- **几何改造**：旧→新几何仅在**明确映射范围**内可连接（`GEOMETRY_REMAPPED`）；未声明映射/越界则不连接、不据此宣称消失或新发。
- **不双计**：同一观测被两条轨迹竞争时，每条轨迹保留 `contested` 歧义并整体标 `NOT_COMPARABLE`，
  但观测只计入一条轨迹一次（顶点不相交），不复制、不重复计数。
- **独立变化范围**：`GET .../change-ranges` 输出端到端变化区间与扣除测量边界后的可支持扩展量，供外部整治排序引用。
- **排序影响**：`GET .../ranking` 只纳入 `POSSIBLY_EXPANDED` 且连续的轨迹（以可支持最小扩展量保守排序）；
  `GET .../ranking-impact` 透明列出因缺覆盖/几何未映射/歧义被排除的轨迹及原因。

## 5. API 概览

| 接口 | 说明 |
| --- | --- |
| `POST /api/network-versions` · `POST /api/network-versions/{code}/segments` | 登记管网版本与管段 |
| `POST /api/segments/{id}/parameter-versions` · `GET .../parameter-versions?at=` | 登记/查询参数版本（`at` 解析历史时点，不回填） |
| `POST /api/observations/batch` · `GET /api/observations` | 观测入库（迟到补录用旧 `sampledAt` 即可）与查询 |
| `POST /api/events` | 登记水文事件窗口 |
| `POST /api/external-refs` · `GET /api/external-refs` | 登记/查询外部只读引用（无修改/删除接口） |
| `POST /api/external-refs/{id}/derive-parameter-version` | 维修验收派生新参数版本（无验收/不可追溯 → 422） |
| `POST /api/scenarios/assemble` · `GET /api/scenarios` | 组装情景（闸门冲突自动拆分条件情景） |
| `POST /api/computations` | 执行评估：`{scenarioId, method, asOf?}`，同键幂等 |
| `GET /api/computations/{runId}` · `/coverage` · `/evidence` | 运行结果、覆盖账本、证据回查 |
| `GET /api/scenarios/{id}/capacity?method=` | **网络情景查询**：各次运行的网络区间与瓶颈排序（下游系统接口） |
| `POST /api/defect-evolution/observations` · `GET .../segments/{id}/observations` | 登记/查询缺陷观察（起止里程、长度/宽度区间、分辨率、定位误差、几何版本；只读） |
| `POST /api/defect-evolution/coverage` · `POST /api/defect-evolution/geometry-mappings` | 登记检查覆盖年份里程段 / 几何版本映射范围 |
| `POST /api/defect-evolution/runs` | 执行演化推导：`{segmentId, method, asOf?}`，同键幂等 |
| `GET /api/defect-evolution/runs/{runId}` · `/trajectories` · `/candidate-links` | 轨迹、候选连接（采用/未采用均可回查） |
| `GET .../runs/{runId}/change-ranges` · `/ranking` · `/ranking-impact` · `/route-guidance` | 独立变化范围、整治排序、排序影响、路线选择建议 |

## 6. 验证方式（以下均为实际执行过的验证）

### 6.1 自动化测试

```bash
mvn test
# Tests run: 40, Failures: 0, Errors: 0 — BUILD SUCCESS
```

覆盖：曼宁公式数值、有效坡度/回水、区间变宽单调性、贡献度归一；版本窗口解析与知识截止；
糙率/下游水位缺失扩大与标记；闸门关闭置零与冲突拆分；未对齐样本拒绝；幂等重放；
迟到补录可见性；历史复算版本选择；覆盖账本记录；
**缺陷演化：重叠/测量边界分类、几何映射范围、扩展裂缝跨非重叠长度连接、匈牙利最优指派、
两路线顶点不相交；裂缝扩展→POSSIBLY_EXPANDED、缺巡检年→不可比较、可映射/不可映射改造、
定位误差竞争不双计、相邻裂缝并存、幂等与 as_of 可见性（18 个新测试）**。

### 6.2 种子数据验收场景（启动后自动写入）

主干线 `N1→S1→N2→S2→N3→S3→N4→S4→B1`（管网版本 `NV-2024A`）：

- **S2 断面局部缩减**：有效直径区间 [900, 960]mm（名义 1000mm），缺陷引用 D-1001 佐证
- **S3 淤积测量区间**：淤积率 [0.10, 0.20]
- **S4 糙率缺失**：内衬资料不全，糙率区间为空
- **E-2026-0714-STORM**：完整降雨/水位/流量监测；闸门 G-S3 在窗口内有 OPEN 与 CLOSED 冲突观测
- **E-2026-0802-STORM**：仅降雨，无水位观测（演示下游水位缺失）
- **维修记录**：R-3001（有验收+可追溯实测）、R-3002（无验收）、R-3003（缺 measurementRef）

缺陷形态演化验收场景（独立管段，均属 `NV-2024A`；每段一场景，多期 CCTV 各期分辨率/定位误差不同）：

- **S5-CRACK-EXPAND 裂缝扩展**：60-62m 裂缝 2024→2025→2026 长度 0.8-1.0→1.6-1.9→2.6-3.0m
  （长度区间不重叠且超出测量边界）→ `POSSIBLY_EXPANDED` / `CONTINUOUS`
- **S5-ADJACENT-CRACKS 相邻裂缝并存**：约 99m 长裂缝与约 104m 短裂缝各自逐年持续 → 两条各自
  `PERSISTENT`，不并为一条、不双计
- **S5-POSITION-ERROR 定位误差扩大**：2026 定位误差增至 1.2m，扩张后位置带覆盖两条裂缝 →
  保留竞争、`NOT_COMPARABLE`、不双计
- **S5-MISSING-YEAR 缺巡检年**：2024/2026 有观测、2025 无覆盖 → `NOT_COMPARABLE` / `COVERAGE_GAP`，
  **不宣称持续劣化**
- **S5-GEOM-MAPPED 几何改造（可映射）**：旧 `GEO-2024` → 新 `GEO-2026-REHAB`（映射 70-100m→68-98m，
  平移 -2m）→ `PERSISTENT` / `GEOMETRY_REMAPPED`
- **S5-GEOM-UNMAPPED 几何改造（不可映射）**：坐标相近但映射范围不覆盖 → 两期各自单期
  `NOT_COMPARABLE`（`notComparableReason=GEOMETRY_NOT_MAPPED`），不得据此宣称消失/新发
- **S5-POSSIBLY-NEW 可能新发**：2025 该位置有覆盖但无缺陷、2026 出现新缺陷 → `POSSIBLY_NEW`
- **S5-POSSIBLY-DISAPPEARED 可能消失**：2024 有缺陷、2025 该位置有覆盖但未再记录 → `POSSIBLY_DISAPPEARED`

### 6.3 验收执行记录（2026-11-20 在隔离容器内实际执行）

```bash
# 1) 闸门冲突 → 拆分为 2 个条件情景（不取平均）
POST /api/scenarios/assemble  →  E-2026-0714-STORM|S1-S2-S3-S4|G-S3=OPEN
                                 E-2026-0714-STORM|S1-S2-S3-S4|G-S3=CLOSED

# 2) 路线A（as_of=2026-07-20，OPEN）：能力区间/余量/限制/瓶颈
S1 q=[533.3, 1190.3] 余量=[-0.407, 0.323]
S2 q=[0.0, 996.9]   余量=[-1.000, 0.108]  限制=[BACKWATER_DOMINATED]   ← 瓶颈
S3 q=[769.0, 1552.5] 余量=[-0.451, 0.109]
S4 q=[255.8, 2580.7] 余量=[-0.817, 0.843] 限制=[ROUGHNESS_MISSING]     ← 糙率缺失放大区间
网络区间=[0.0, 996.9] L/s；重复提交同键 → replayed=true（幂等）

# 3) 路线B（同情景同边界）：校准+联合传播
S1 q=[0.0, 1098.8] 校准样本=3 拒绝=0  水位可行范围=[12.61, 13.85]
S2 q=[0.0, 934.9]  校准样本=3 拒绝=1（03:25 未对齐流量被拒绝）
S3 q=[249.8, 1484.5]；S4 q=[639.7, 1721.2]（校准糙率替代缺省区间，区间收窄）

# 4) 闸门 CLOSED 条件情景：S3 q=[0.0, 0.0] 限制=[GATE_CLOSED]，瓶颈=S3

# 5) E2 暴雨（无水位观测）：四段均标 [DOWNSTREAM_LEVEL_MISSING]，区间扩大

# 6) 维修派生：R-3002 → 422（无验收）；R-3003 → 422（缺 measurementRef）；
#    R-3001 → 201 生成 S2 v2（直径 [968,985]，糙率 [0.011,0.013]，validFrom=2026-09-01）；
#    v1 关闭但保留（valid=[2026-01-01 → 2026-09-01]）

# 7) 历史复算：
#    as_of=2026-07-20 → 重放 run 1，S2 仍为 v1 值 [0.0, 996.9]
#    as_of=2026-10-01 → v1 已关闭、v2 尚未登记（知识空窗）→ S2 不可判定 NO_PARAMETER_VERSION_AT_AS_OF（不回填）
#    as_of=现在       → S2 用 v2：q=[421.8, 1247.2]（维修后能力提升）

# 8) 迟到补录：补录 B1 水位 12.90m（采样 07-14 05:30）→
#    旧 as_of 复算：replayed=true，S4 不变 [255.8, 2580.7]（补录不可见）
#    新 as_of：S4 尾水=[11.3, 12.9]（含补录 obs#35），q=[0.0, 2580.7]（回水主导）

# 9) 覆盖账本/证据回查：S4 主导不确定参数 ROUGHNESS 73.9%，
#    假设“糙率缺失采用缺省区间 [0.009,0.035]”写入证据；传播尾水与观测尾水分别标注

# 10) 网络情景查询 GET /api/scenarios/1/capacity：
#    随 as_of 演变——as_of=07-20 瓶颈 S2；as_of=10-01 S2 不可判定；维修后瓶颈 S1
```

### 6.4 缺陷演化验收执行记录（2026-11-20 在隔离容器内实际执行）

```bash
# 启动后种子自动写入 S5-* 六个验收管段；下列 asOf=2027-01-01 覆盖全部历史观测入库。
# 1) 裂缝扩展 S5-CRACK-EXPAND（RULE_BASED）：
T-001 POSSIBLY_EXPANDED / CONTINUOUS / obs=[78,79,80]
  changeRange.lengthChangeRangeM=[1.6, 2.2]，supportedChangeBeyondBoundaryM=[1.595, 2.195]（已扣 5mm 边界）
  remediationRanking: rank1 T-001，orderMetric=1.595（保守：可支持最小扩展量）

# 2) 相邻裂缝并存 S5-ADJACENT-CRACKS：
T-001 PERSISTENT（长裂缝）、T-002 PERSISTENT（短裂缝），各自 CONTINUOUS，互不双计（6 条观测各计一次）

# 3) 定位误差扩大 S5-POSITION-ERROR：
候选边 87->89 / 87->90 / 88->89 / 88->90（后期定位误差 1.2m 令位置带覆盖两条裂缝）
两路线均判 contested → T-001/T-002 NOT_COMPARABLE，保留歧义且不双计

# 4) 缺巡检年 S5-MISSING-YEAR：
T-001 NOT_COMPARABLE / COVERAGE_GAP（2024 与 2026 直连，2025 无覆盖）→ 不进入排序，rankingImpact 说明原因

# 5) 几何改造（可映射）S5-GEOM-MAPPED：
T-001 PERSISTENT / GEOMETRY_REMAPPED（旧 GEO-2024 → 新 GEO-2026-REHAB，映射 70-100→68-98 平移 -2m）

# 6) 几何改造（不可映射）S5-GEOM-UNMAPPED：
两期各自单期 NOT_COMPARABLE，notComparableReason=GEOMETRY_NOT_MAPPED；候选连接记录“无明确映射”拒绝原因

# 6b) 可能新发 S5-POSSIBLY-NEW：2025 有覆盖无缺陷、2026 出现 → POSSIBLY_NEW
# 6c) 可能消失 S5-POSSIBLY-DISAPPEARED：2024 有缺陷、2025 有覆盖未再记录 → POSSIBLY_DISAPPEARED

# 7) 幂等：重复 POST 同 (segment, method, asOf) → replayed=true，runId 不变
# 8) 权限：未认证 POST=401；viewer GET=200 / POST=403；不存在管段=404
# 9) 文档：/v3/api-docs 含 13 条 defect-evolution 路径；Swagger UI=200
```

## 7. 设计说明

- **as_of 语义**：复算使用“该时点的知识状态”——参数版本需有效窗覆盖且已登记，观测需已入库。
  已完成的运行不可变；知识库更新（如维修版本登记）后，选择新的 as_of 计算即可对照。
- **区间语义**：上界取有利端（大断面、小淤积、陡坡、小糙率、低尾水），下界取相反端；
  尾水超过管顶按出口淹没取水头差/管长为有效坡度，下界可为 0（标 `BACKWATER_DOMINATED`）。
- **缺省假设**（`application.yml` 可调，均写入账本与证据）：糙率 [0.009, 0.035]、淤积上限 0.30、
  出口壅水 2.0m、校准对齐容差 600s、校准样本下限 3 条。
- **不做的事**：不重新定位缺陷、不裁决观测、不推导覆盖与维修状态、不生成维修优先级；
  两路线互不预处理/核验；不用最新参数回填历史；不平均冲突闸门状态；不合成未对齐峰值。
- **缺陷演化的只读纪律**：只追加轨迹/比较/变化范围/排序影响，**不重写原始判读**；缺覆盖不宣称持续劣化；
  几何改造仅在明确映射范围内可连接；定位误差只影响连接、不放大扩展量；竞争保留歧义而不双计。
  演化排序是"供外部整治系统引用的独立分析结果"，不是本平台对缺陷的最终裁决。
