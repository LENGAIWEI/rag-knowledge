package org.example.ragknowledge.eval;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测执行器：把评测集在多个配置档位上各跑一遍，产出可比对的报告。
 *
 * <p>只走 {@link KnowledgeService#retrieve}，<b>不调用大模型生成答案</b>。原因有三：</p>
 * <ol>
 *   <li>评测要评的是检索，生成质量会污染信号 —— 检索对了但模型答歪，不该算检索的错；</li>
 *   <li>几十条用例 × 6 个档位 = 上百次大模型调用，又慢又花钱；</li>
 *   <li>生成结果没法自动判对错，还得再引入一个"裁判模型"，整个评测的可信度反而下降。</li>
 * </ol>
 *
 * <p>档位差异通过<b>参数覆盖</b>实现，不改全局配置 —— 否则并发提问会读到被评测改了一半的配置。</p>
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    /** 单次评测请求的最大档位数，防止有人传一堆档位把服务跑满 */
    private static final int MAX_PROFILES = 8;

    private final KnowledgeService knowledgeService;
    private final KnowledgeProperties properties;
    private final org.example.ragknowledge.embed.EmbeddingClient embeddingClient;

    public EvalRunner(KnowledgeService knowledgeService,
                      KnowledgeProperties properties,
                      org.example.ragknowledge.embed.EmbeddingClient embeddingClient) {
        this.knowledgeService = knowledgeService;
        this.properties = properties;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 跑一个档位。
     *
     * @param topK 每次检索取多少条。要大于等于你想看的最大 K
     *             （比如想算 Hit@10，这里就得传 10）
     * @param minScoreOverride        覆盖最终分阈值；null = 用全局默认。
     *                                 扫这个值能看出"门槛卡掉了多少本该命中的候选"
     * @param minVectorScoreOverride  覆盖向量召回阈值；null = 用全局默认
     */
    public EvalReport run(EvalSetStore.EvalSet set, EvalProfile profile, int topK,
                          Double minScoreOverride, Double minVectorScoreOverride) {
        KnowledgeProperties.Hybrid hybridOverride = hybridOverride(profile.hybrid(), minVectorScoreOverride);
        List<EvalReport.CaseResult> details = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (EvalCase evalCase : set.safeCases()) {
            details.add(runOne(evalCase, profile, topK, hybridOverride, minScoreOverride, latencies));
        }

        EvalReport report = EvalReport.summarize(
                profile.name(), profile.description(), details, latencies, topK);
        log.info("评测档位「{}」完成：可回答 {} 条，Hit@5={}, MRR={}, 平均 {}ms",
                profile.name(), report.answerableCount(),
                String.format("%.3f", report.hit5()),
                String.format("%.3f", report.mrr()),
                String.format("%.0f", report.avgLatencyMs()));
        return report;
    }

    /** 按标准档位依次跑完，返回对比表 */
    public List<EvalReport> runAll(EvalSetStore.EvalSet set, List<EvalProfile> profiles, int topK,
                                   Double minScoreOverride, Double minVectorScoreOverride) {
        List<EvalProfile> limited = profiles.size() > MAX_PROFILES
                ? profiles.subList(0, MAX_PROFILES)
                : profiles;

        // 先把所有问题向量化一遍，把缓存捂热。
        // 不做这一步的话，第一个档位要承担全部 Embedding 网络开销，
        // 后面档位全命中缓存 —— 延迟对比会得出"加的功能越多越快"这种荒谬结论。
        warmUpQueryCache(set);

        List<EvalReport> reports = new ArrayList<>(limited.size());
        for (EvalProfile profile : limited) {
            reports.add(run(set, profile, topK, minScoreOverride, minVectorScoreOverride));
        }
        return reports;
    }

    /** 预热查询向量缓存；失败不影响评测，只是延迟数据会偏大 */
    private void warmUpQueryCache(EvalSetStore.EvalSet set) {
        List<String> questions = new ArrayList<>();
        for (EvalCase evalCase : set.safeCases()) {
            questions.add(evalCase.question());
        }
        if (questions.isEmpty()) {
            return;
        }
        try {
            embeddingClient.embedQueries(questions);
            log.info("评测开始前已预热 {} 条问题的查询向量", questions.size());
        } catch (Exception e) {
            log.warn("查询向量预热失败，各档位延迟数据会受 Embedding 网络开销影响：{}", e.getMessage());
        }
    }

    private EvalReport.CaseResult runOne(EvalCase evalCase,
                                         EvalProfile profile,
                                         int topK,
                                         KnowledgeProperties.Hybrid hybridOverride,
                                         Double minScoreOverride,
                                         List<Long> latencies) {
        long started = System.currentTimeMillis();
        try {
            KnowledgeService.Retrieval retrieval = knowledgeService.retrieve(
                    evalCase.question(),
                    null,                       // 评测集里的问题都是自包含的，不需要多轮历史
                    topK,
                    minScoreOverride,           // null 时用全局默认，保证各档位可比
                    null,
                    profile.rewrite(),
                    profile.hyde(),
                    hybridOverride,
                    profile.rerankMode());
            latencies.add(System.currentTimeMillis() - started);

            List<KnowledgeService.Hit> hits = retrieval.hits();
            boolean refused = !retrieval.found();

            int firstRank = 0;
            for (int i = 0; i < hits.size(); i++) {
                if (evalCase.matches(hits.get(i))) {
                    firstRank = i + 1;
                    break;
                }
            }

            // 可回答用例：命中即通过；应拒答用例：正确拒答才算通过
            boolean passed = evalCase.answerable() ? firstRank > 0 : refused;

            return new EvalReport.CaseResult(
                    evalCase.id(),
                    evalCase.question(),
                    evalCase.answerable(),
                    firstRank,
                    refused,
                    passed,
                    topSources(hits),
                    null);

        } catch (Exception e) {
            latencies.add(System.currentTimeMillis() - started);
            log.warn("评测用例 {} 执行失败：{}", evalCase.id(), e.getMessage());
            return new EvalReport.CaseResult(
                    evalCase.id(), evalCase.question(), evalCase.answerable(),
                    0, false, false, List.of(), e.getMessage());
        }
    }

    /** 取前 3 条的「来源｜章节」用于报告里定位问题 */
    private List<String> topSources(List<KnowledgeService.Hit> hits) {
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 0; i < hits.size() && labels.size() < 3; i++) {
            KnowledgeService.Hit hit = hits.get(i);
            String section = hit.section() == null || hit.section().isBlank()
                    ? "" : "｜" + hit.section();
            labels.add(hit.source() + section);
        }
        return new ArrayList<>(labels);
    }

    /**
     * 构造档位专用的召回配置：以全局配置为底，只覆盖"是否启用混合检索"。
     *
     * <p>其余参数（召回条数、RRF 的 k、各路权重、向量阈值）全部继承全局值，
     * 这样各档位之间唯一的变量就是"有没有 BM25 这一路"，归因才干净。</p>
     */
    private KnowledgeProperties.Hybrid hybridOverride(boolean enabled, Double minVectorScoreOverride) {
        KnowledgeProperties.Hybrid base = properties.getHybrid();
        KnowledgeProperties.Hybrid copy = new KnowledgeProperties.Hybrid();
        copy.setEnabled(enabled);
        copy.setRecallTopK(base.getRecallTopK());
        copy.setMinVectorScore(minVectorScoreOverride != null
                ? minVectorScoreOverride : base.getMinVectorScore());
        copy.setRrfK(base.getRrfK());
        copy.setVectorWeight(base.getVectorWeight());
        copy.setBm25Weight(base.getBm25Weight());
        copy.setBm25TopK(base.getBm25TopK());
        copy.setBm25K1(base.getBm25K1());
        copy.setBm25B(base.getBm25B());
        return copy;
    }
}
