package com.citydrain.capacity.service.evolution;

import com.citydrain.capacity.domain.DefectObservation;
import com.citydrain.capacity.domain.GeometryMapping;
import com.citydrain.capacity.domain.Vocab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轨迹连接：两条独立路线，共用 {@link CandidateLinker} 的候选边与容差，但挑选轨迹集合的算法不同。
 *
 * 路线A RULE_BASED（规则）：把边按“前一期年份”分桶，各桶内按得分从高到低逐段贪心，每端至多一前驱一后继。
 *   规则透明、工程师可逐步复核；代价是高密度裂缝区先到先得，可能产生局部错连与多条候选。
 *
 * 路线B GLOBAL_CONSTRAINT（整体约束）：把多期全部观测与位置误差作为整体，候选图是有向无环图，
 *   求解“最大权顶点不相交路径覆盖”——等价于候选边上的最大权二分匹配（因为 DAG 无环）。
 *   它同时决定所有观察期的连接，必要时“跳年”以换取全局最优，而非逐期贪心。
 *   这是从头对全段整体求解，不是规则匹配失败后的补救；代价是求解与多种可行轨迹的解释成本。
 *
 * 两条路线互不作为对方的预处理或补丁。
 */
public final class TrajectoryLinker {

    private TrajectoryLinker() {}

    /** 连接结果：轨迹链（按观察日期升序的观测 id 列表）+ 全部候选边 + 本路线采用的边集合。 */
    public record LinkResult(List<List<Long>> chains,
                             List<CandidateLinker.Edge> allCandidates,
                             Set<String> acceptedPairKeys) {
    }

    public static LinkResult link(List<DefectObservation> observations,
                                  List<GeometryMapping> mappings,
                                  com.citydrain.capacity.config.DefectEvolutionProps props,
                                  String method) {
        List<CandidateLinker.Edge> all = CandidateLinker.candidates(observations, mappings, props);
        List<CandidateLinker.Edge> feasible = all.stream().filter(CandidateLinker.Edge::feasible).toList();

        List<CandidateLinker.Edge> accepted = Vocab.LINK_GLOBAL_CONSTRAINT.equals(method)
                ? globalPathCover(feasible, observations)
                : ruleBasedPerPeriod(feasible, observations);

        List<List<Long>> chains = buildChains(observations, accepted);
        Set<String> keys = new LinkedHashSet<>();
        accepted.forEach(e -> keys.add(key(e.fromId(), e.toId())));
        return new LinkResult(chains, all, keys);
    }

    // ------------------------------------------------------------------ 路线A

    /** 规则路线：按前一期年份分桶，各桶内按得分降序贪心，每端至多一条边。 */
    private static List<CandidateLinker.Edge> ruleBasedPerPeriod(List<CandidateLinker.Edge> feasible,
                                                                 List<DefectObservation> observations) {
        Map<Long, DefectObservation> byId = index(observations);
        Map<Integer, List<CandidateLinker.Edge>> byFromYear = new LinkedHashMap<>();
        for (CandidateLinker.Edge e : feasible) {
            int y = byId.get(e.fromId()).observedAt().getYear();
            byFromYear.computeIfAbsent(y, k -> new ArrayList<>()).add(e);
        }
        List<Integer> years = new ArrayList<>(byFromYear.keySet());
        years.sort(Integer::compareTo);

        Set<Long> usedFrom = new LinkedHashSet<>();
        Set<Long> usedTo = new LinkedHashSet<>();
        List<CandidateLinker.Edge> accepted = new ArrayList<>();
        for (Integer y : years) {
            List<CandidateLinker.Edge> bucket = new ArrayList<>(byFromYear.get(y));
            bucket.sort(Comparator.comparingDouble(CandidateLinker.Edge::score).reversed()
                    .thenComparingLong(CandidateLinker.Edge::fromId)
                    .thenComparingLong(CandidateLinker.Edge::toId));
            for (CandidateLinker.Edge e : bucket) {
                if (!usedFrom.contains(e.fromId()) && !usedTo.contains(e.toId())) {
                    usedFrom.add(e.fromId());
                    usedTo.add(e.toId());
                    accepted.add(e);
                }
            }
        }
        return accepted;
    }

    // ------------------------------------------------------------------ 路线B

    /**
     * 整体约束路线：最大权顶点不相交路径覆盖。
     * 构造二分图（左=出边端，右=入边端），边权=连接得分，求最大权匹配即为全局最优轨迹集合。
     * DAG 保证匹配不会成环，因此匹配结果直接是若干条路径。
     */
    private static List<CandidateLinker.Edge> globalPathCover(List<CandidateLinker.Edge> feasible,
                                                              List<DefectObservation> observations) {
        if (feasible.isEmpty()) {
            return List.of();
        }
        List<Long> nodes = new ArrayList<>();
        observations.stream()
                .sorted((x, y) -> {
                    int c = x.observedAt().compareTo(y.observedAt());
                    return c != 0 ? c : Long.compare(x.id(), y.id());
                })
                .forEach(o -> nodes.add(o.id()));
        Map<Long, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            idx.put(nodes.get(i), i);
        }
        int n = nodes.size();
        double[][] w = new double[n][n];
        Map<String, CandidateLinker.Edge> edgeByKey = new LinkedHashMap<>();
        for (CandidateLinker.Edge e : feasible) {
            int i = idx.get(e.fromId());
            int j = idx.get(e.toId());
            if (e.score() > w[i][j]) {
                w[i][j] = e.score();
            }
            edgeByKey.put(key(e.fromId(), e.toId()), e);
        }
        List<HungarianMatcher.Pair> pairs = HungarianMatcher.maxWeightMatching(w);
        List<CandidateLinker.Edge> accepted = new ArrayList<>();
        for (HungarianMatcher.Pair p : pairs) {
            long from = nodes.get(p.row());
            long to = nodes.get(p.col());
            CandidateLinker.Edge e = edgeByKey.get(key(from, to));
            if (e != null && w[p.row()][p.col()] > 0) {
                accepted.add(e);
            }
        }
        return accepted;
    }

    // ------------------------------------------------------------------ 通用

    private static Map<Long, DefectObservation> index(List<DefectObservation> observations) {
        Map<Long, DefectObservation> byId = new LinkedHashMap<>();
        observations.forEach(o -> byId.put(o.id(), o));
        return byId;
    }

    private static String key(long a, long b) {
        return a + "->" + b;
    }

    /** 由采用的边构造链：每个观测至多一前驱一后继，沿时间顺序拼接。 */
    private static List<List<Long>> buildChains(List<DefectObservation> observations,
                                                List<CandidateLinker.Edge> accepted) {
        Map<Long, Long> next = new LinkedHashMap<>();
        Set<Long> hasPrev = new LinkedHashSet<>();
        for (CandidateLinker.Edge e : accepted) {
            next.put(e.fromId(), e.toId());
            hasPrev.add(e.toId());
        }
        List<Long> ordered = new ArrayList<>();
        observations.stream()
                .sorted((x, y) -> {
                    int c = x.observedAt().compareTo(y.observedAt());
                    return c != 0 ? c : Long.compare(x.id(), y.id());
                })
                .forEach(o -> ordered.add(o.id()));

        List<List<Long>> chains = new ArrayList<>();
        for (Long start : ordered) {
            if (hasPrev.contains(start)) {
                continue; // 非链头
            }
            List<Long> chain = new ArrayList<>();
            Long cur = start;
            while (cur != null && !chain.contains(cur)) {
                chain.add(cur);
                cur = next.get(cur);
            }
            chains.add(chain);
        }
        return chains;
    }
}
