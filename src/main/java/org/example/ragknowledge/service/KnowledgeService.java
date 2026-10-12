package org.example.ragknowledge.service;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.embed.EmbeddingClient;
import org.example.ragknowledge.index.Chunk;
import org.example.ragknowledge.index.KnowledgeStore;
import org.example.ragknowledge.index.TextSplitter;
import org.example.ragknowledge.llm.AiService;
import org.example.ragknowledge.query.QueryRewriter;
import org.example.ragknowledge.retrieval.Candidate;
import org.example.ragknowledge.retrieval.HybridRetriever;
import org.example.ragknowledge.rerank.RerankerRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 编排服务：把"切段入库"和"检索问答"两条链路串起来。
 *
 * <h3>入库链路</h3>
 * <pre>
 *   splitText（按标题切章节，章节内按字数切块 + 重叠）
 *     → embedAll（批量向量化，失败降级逐条重试）
 *     → store.addAll（内存 + 落盘；BM25 索引按版本号惰性重建）
 * </pre>
 *
 * <h3>问答链路（v2 的三层优化都在这里）</h3>
 * <pre>
 *   ① Query 改写 / HyDE      ← 第 3 层：把问题变成多个"知识库听得懂"的检索式
 *   ② 批量向量化所有检索式    ← 一次 HTTP 调用，不是每个查询一次
 *   ③ 向量 + BM25 双路召回    ← 第 1 层
 *   ④ RRF 融合（多路 × 多查询）
 *   ⑤ 精排 Rerank（local/http）← 第 2 层
 *   ⑥ 截断 topK → 拼 Prompt → 大模型作答
 * </pre>
 *
 * <p>每一步的中间结果都会随响应返回，前端可以直接看到"哪一路召回的、融合分多少、精排分多少"，
 * 而不是只给一个黑盒答案。</p>
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 检索命中片段：把每一层的分数都带出去，便于界面展示与线上排错 */
    public record Hit(String text,
                      String source,
                      String section,
                      int ordinal,
                      double score,
                      Double vectorScore,
                      Double bm25Score,
                      double rrfScore,
                      Double rerankScore,
                      List<String> routes,
                      List<String> matchedQueries) {
    }

    /** 本次问答的查询侧信息 */
    public record AskQuery(String original,
                           String standalone,
                           List<String> variants,
                           List<String> keywords,
                           boolean rewriteUsed,
                           String rewriteError,
                           boolean hydeUsed,
                           int hydeChars) {
    }

    /** 本次问答的检索侧信息 */
    public record RetrievalInfo(String mode,
                                int recalled,
                                int reranked,
                                int returned,
                                String rerankMode,
                                boolean rerankDegraded,
                                String rerankDetail,
                                boolean vectorDegraded,
                                String vectorDegradedReason,
                                Map<String, Long> timingsMs) {
    }

    /**
     * 单次问答结果。
     *
     * <p>三种状态用两个字段表达清楚，互不混淆：</p>
     * <ul>
     *   <li>{@code found=false}：检索层没有任何片段通过门槛 —— 知识库里确实没有相关内容，属于<b>正常拒答</b>；</li>
     *   <li>{@code found=true, error=null}：完整成功；</li>
     *   <li>{@code found=true, error!=null}：<b>检索成功但生成失败</b>（大模型超时/欠费/限流）。
     *       这时仍然把命中的片段带回去 —— 否则用户和排错的人都无法判断问题出在检索还是生成，
     *       而这两者的排查方向完全不同。</li>
     * </ul>
     */
    public record Answer(String answer, List<Hit> hits, boolean found, String error,
                         AskQuery query, RetrievalInfo retrieval) {
    }

    /** 知识库业务异常：消息会直接返回给前端 */
    public static class KnowledgeException extends RuntimeException {
        public KnowledgeException(String message) {
            super(message);
        }
    }

    private final KnowledgeStore store;
    private final EmbeddingClient embeddingClient;
    private final HybridRetriever retriever;
    private final RerankerRouter rerankerRouter;
    private final QueryRewriter queryRewriter;
    private final AiService aiService;
    private final KnowledgeProperties properties;

    public KnowledgeService(KnowledgeStore store,
                            EmbeddingClient embeddingClient,
                            HybridRetriever retriever,
                            RerankerRouter rerankerRouter,
                            QueryRewriter queryRewriter,
                            AiService aiService,
                            KnowledgeProperties properties) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.retriever = retriever;
        this.rerankerRouter = rerankerRouter;
        this.queryRewriter = queryRewriter;
        this.aiService = aiService;
        this.properties = properties;
    }

    // ==================== 入库 ====================

    /**
     * 添加文档：切段（含章节元数据）→ 批量向量化 → 入库 → 落盘。
     *
     * @return 实际入库的段落数
     */
    public int addDocument(String content, String source) {
        if (content == null || content.isBlank()) {
            throw new KnowledgeException("文档内容不能为空！");
        }
        String src = (source == null || source.isBlank()) ? "手动粘贴" : source.strip();

        List<TextSplitter.Segment> segments =
                TextSplitter.split(content, properties.getChunkSize(), properties.getChunkOverlap());
        if (segments.isEmpty()) {
            throw new KnowledgeException("文档内容全是空白字符，没有可入库的段落");
        }

        List<String> texts = new ArrayList<>(segments.size());
        for (TextSplitter.Segment segment : segments) {
            texts.add(segment.text());
        }

        List<String> errors = new ArrayList<>();
        List<double[]> vectors = embeddingClient.embedAll(texts, properties.getBatchSize(), errors);

        List<Chunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            double[] vector = vectors.get(i);
            if (vector == null) {
                continue;
            }
            TextSplitter.Segment segment = segments.get(i);
            chunks.add(new Chunk(segment.text(), src, segment.section(),
                    store.nextOrdinal(), vector));
        }
        if (chunks.isEmpty()) {
            String detail = errors.isEmpty() ? "" : "：" + errors.get(0);
            throw new KnowledgeException("全部 " + segments.size() + " 个段落都向量化失败，请检查 Embedding 配置与网络" + detail);
        }
        store.addAll(chunks);
        return chunks.size();
    }

    // ==================== 问答 ====================

    /**
     * 基于知识库提问。
     *
     * @param question      用户问题
     * @param history       最近的对话（用于指代消解），可为 null
     * @param topK          覆盖最终送入大模型的片段数
     * @param minScore      覆盖最终分阈值
     * @param minVectorScore 覆盖向量召回阈值
     * @param useRewrite    本次是否启用 Query 改写
     * @param useHyde       本次是否启用 HyDE
     */
    public Answer ask(String question,
                      List<String> history,
                      Integer topK,
                      Double minScore,
                      Double minVectorScore,
                      Boolean useRewrite,
                      Boolean useHyde) {
        if (question == null || question.isBlank()) {
            throw new KnowledgeException("问题不能为空！");
        }
        if (store.size() == 0) {
            throw new KnowledgeException("知识库为空，请先在「第1步：添加文档」上传内容后再提问");
        }

        Map<String, Long> timings = new LinkedHashMap<>();
        long startAll = System.currentTimeMillis();

        // ---------- ① Query 改写 / HyDE ----------
        boolean rewriteEnabled = useRewrite != null ? useRewrite : properties.getRewrite().isEnabled();
        boolean hydeEnabled = useHyde != null ? useHyde : properties.getHyde().isEnabled();

        long t = System.currentTimeMillis();
        QueryRewriter.RewriteResult rewrite = rewriteEnabled
                ? queryRewriter.rewrite(question, history, properties.getRewrite().getVariants())
                : QueryRewriter.RewriteResult.passthrough(question, null);

        String hydeText = null;
        if (hydeEnabled) {
            hydeText = queryRewriter.hypotheticalAnswer(question, properties.getHyde().getMaxChars());
        }
        timings.put("rewriteMs", System.currentTimeMillis() - t);

        // ---------- ② 组装检索式并一次性向量化 ----------
        List<PlannedQuery> planned = planQueries(question, rewrite, hydeText);
        t = System.currentTimeMillis();

        // 只对需要走向量路的检索式算向量（关键词查询是"词表"，只走 BM25），
        // 并且无论几个检索式都只发一次 Embedding 请求 —— 批量接口就是为这个场景设计的。
        List<Integer> vectorSlots = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();
        for (int i = 0; i < planned.size(); i++) {
            if (planned.get(i).vector()) {
                vectorSlots.add(i);
                textsToEmbed.add(planned.get(i).text());
            }
        }

        double[][] vectors = new double[planned.size()][];
        String embedError = null;
        try {
            List<double[]> embedded = embeddingClient.embedBatch(textsToEmbed);
            for (int k = 0; k < vectorSlots.size() && k < embedded.size(); k++) {
                vectors[vectorSlots.get(k)] = embedded.get(k);
            }
        } catch (EmbeddingClient.EmbeddingException e) {
            // 向量化失败不等于问答失败：BM25 这一路完全不依赖 Embedding 服务，
            // 只要开着混合检索就还能继续关键词召回。宁可给出"降级但可用"的答案，
            // 也不要因为一个外部依赖抖动就直接 500。
            if (!properties.getHybrid().isEnabled()) {
                throw new KnowledgeException(e.getMessage());
            }
            embedError = e.getMessage();
            log.warn("向量化失败，本次问答降级为纯 BM25 关键词检索：{}", e.getMessage());
        }
        timings.put("embedMs", System.currentTimeMillis() - t);

        List<HybridRetriever.QueryVariant> variants = new ArrayList<>(planned.size());
        for (int i = 0; i < planned.size(); i++) {
            PlannedQuery query = planned.get(i);
            variants.add(new HybridRetriever.QueryVariant(query.label(), query.text(), vectors[i], query.weight()));
        }

        // ---------- ③④ 双路召回 + RRF 融合 ----------
        t = System.currentTimeMillis();
        List<Candidate> candidates = retriever.retrieve(variants, store, properties);
        int recalled = candidates.size();
        timings.put("recallMs", System.currentTimeMillis() - t);

        AskQuery queryInfo = new AskQuery(
                question,
                rewrite.standalone(),
                rewrite.variants(),
                rewrite.keywords(),
                rewrite.used(),
                rewrite.error(),
                hydeText != null,
                hydeText == null ? 0 : hydeText.length());

        if (candidates.isEmpty()) {
            timings.put("llmMs", 0L);
            timings.put("totalMs", System.currentTimeMillis() - startAll);
            return new Answer(null, List.of(), false, null, queryInfo,
                    buildRetrievalInfo(0, 0, 0, RerankerRouter.Outcome.of(rerankerRouter.configuredMode()),
                            embedError, timings));
        }

        // ---------- ⑤ 精排 ----------
        int rerankCandidates = Math.max(1, properties.getRerank().getCandidates());
        List<Candidate> toRerank = candidates.size() > rerankCandidates
                ? new ArrayList<>(candidates.subList(0, rerankCandidates))
                : candidates;

        // 精排用"改写后的独立问题"判定：它已消解指代，是用户真实意图的最完整表达
        String judgeQuery = rewrite.used() && !rewrite.standalone().isBlank()
                ? rewrite.standalone()
                : question;

        t = System.currentTimeMillis();
        RerankerRouter.Outcome rerankOutcome = rerankerRouter.rerank(judgeQuery, toRerank);
        timings.put("rerankMs", System.currentTimeMillis() - t);

        // 精排生效时按精排分重排；off 模式保持融合序（这是它的定义）
        if (!"off".equals(rerankOutcome.mode())) {
            toRerank.sort(Comparator.comparingDouble(Candidate::finalScore).reversed());
        }

        // ---------- ⑥ 阈值过滤 + 截断 ----------
        double gate = minScore != null ? minScore : properties.getRerank().getMinScore();
        int limit = (topK == null || topK <= 0) ? properties.getTopK() : topK;

        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : toRerank) {
            if (candidate.finalScore() < gate) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= limit) {
                break;
            }
        }

        if (selected.isEmpty()) {
            timings.put("llmMs", 0L);
            timings.put("totalMs", System.currentTimeMillis() - startAll);
            return new Answer(null, List.of(), false, null, queryInfo,
                    buildRetrievalInfo(recalled, toRerank.size(), 0, rerankOutcome, embedError, timings));
        }

        List<Hit> hits = toHits(selected);

        // ---------- ⑦ 拼 Prompt 交给大模型 ----------
        String prompt = buildPrompt(question, selected);
        t = System.currentTimeMillis();
        AiService.Result result = aiService.chat(prompt, 0.3);
        timings.put("llmMs", System.currentTimeMillis() - t);
        timings.put("totalMs", System.currentTimeMillis() - startAll);

        RetrievalInfo retrieval = buildRetrievalInfo(recalled, toRerank.size(), hits.size(),
                rerankOutcome, embedError, timings);

        if (!result.isSuccess()) {
            // 检索是好的、只是生成失败：把片段一并返回，让调用方能看到"找对了但答不出来"
            return new Answer(null, hits, true, "调用大模型失败：" + result.getError(), queryInfo, retrieval);
        }
        return new Answer(result.getContent(), hits, true, null, queryInfo, retrieval);
    }

    /** 把候选转成对外的命中片段（每一层分数都带上） */
    private List<Hit> toHits(List<Candidate> selected) {
        List<Hit> hits = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            Chunk chunk = candidate.chunk();
            hits.add(new Hit(
                    chunk.text(),
                    chunk.source(),
                    chunk.sectionOrEmpty(),
                    chunk.ordinal(),
                    round(candidate.finalScore()),
                    candidate.vectorScore() == null ? null : round(candidate.vectorScore()),
                    candidate.bm25Score() == null ? null : round(candidate.bm25Score()),
                    round(candidate.rrfScore()),
                    candidate.rerankScore() == null ? null : round(candidate.rerankScore()),
                    new ArrayList<>(candidate.routes()),
                    new ArrayList<>(candidate.matchedQueries())));
        }
        return hits;
    }

    /**
     * 组装参与召回的检索式。
     *
     * <p>权重体现"这一路有多可信"：原问题 1.0（用户亲口说的）&gt; 改写后的独立问题 0.9
     * （消解了指代，但毕竟经过模型转述）&gt; 同义变体 0.6（探索性召回，可能跑偏）。
     * 关键词单独走一路 BM25（权重 0.6 的变体档），因为抽取出的关键词是"词"不是"句"，
     * 拿去算向量反而会污染向量路。</p>
     */
    private List<PlannedQuery> planQueries(String question, QueryRewriter.RewriteResult rewrite, String hydeText) {
        List<PlannedQuery> planned = new ArrayList<>();
        planned.add(new PlannedQuery("原问题", question, 1.0, true));

        boolean fallbackToOriginal = !rewrite.used()
                || rewrite.standalone() == null
                || rewrite.standalone().isBlank()
                || rewrite.standalone().equals(question);
        if (!fallbackToOriginal) {
            planned.add(new PlannedQuery("改写", rewrite.standalone(),
                    properties.getRewrite().getStandaloneWeight(), true));
        }
        for (int i = 0; i < rewrite.variants().size(); i++) {
            String variant = rewrite.variants().get(i);
            if (variant.equals(question) || (!fallbackToOriginal && variant.equals(rewrite.standalone()))) {
                continue;
            }
            planned.add(new PlannedQuery("变体" + (i + 1), variant,
                    properties.getRewrite().getVariantWeight(), true));
        }
        if (!rewrite.keywords().isEmpty()) {
            String keywordQuery = String.join(" ", rewrite.keywords());
            if (!keywordQuery.equals(question)) {
                planned.add(new PlannedQuery("关键词", keywordQuery,
                        properties.getRewrite().getVariantWeight(), false));
            }
        }
        if (hydeText != null && !hydeText.isBlank()) {
            planned.add(new PlannedQuery("HyDE", hydeText, properties.getHyde().getWeight(), true));
        }
        return planned;
    }

    /** 拼装最终 Prompt：片段带上来源与章节，并强制模型"无依据就拒答" */
    private String buildPrompt(String question, List<Candidate> selected) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            Chunk chunk = selected.get(i).chunk();
            context.append("【片段").append(i + 1).append("｜来源：").append(chunk.source());
            if (!chunk.sectionOrEmpty().isEmpty()) {
                context.append("｜章节：").append(chunk.section());
            }
            context.append("】\n").append(chunk.text()).append("\n\n");
        }

        return "你是一个知识库问答助手。请严格依据下面的文档片段回答用户问题。\n"
                + "要求：\n"
                + "1. 只使用文档片段中的信息，不要编造，也不要引入片段之外的知识；\n"
                + "2. 如果文档片段不足以回答问题，直接回答「根据提供的文档，我无法回答这个问题」；\n"
                + "3. 用中文回答，条理清晰（必要时分点），可在句末用【片段N】标注依据来源。\n\n"
                + "文档片段：\n" + context
                + "用户问题：" + question.strip() + "\n\n"
                + "回答：";
    }

    private String retrievalMode(boolean vectorDegraded) {
        if (!properties.getHybrid().isEnabled()) {
            return "vector";
        }
        return vectorDegraded ? "bm25" : "hybrid";
    }

    private RetrievalInfo buildRetrievalInfo(int recalled,
                                             int reranked,
                                             int returned,
                                             RerankerRouter.Outcome outcome,
                                             String embedError,
                                             Map<String, Long> timings) {
        return new RetrievalInfo(
                retrievalMode(embedError != null),
                recalled,
                reranked,
                returned,
                outcome.mode(),
                outcome.degraded(),
                outcome.detail(),
                embedError != null,
                embedError,
                timings);
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    /**
     * 计划参与召回的一条检索式。
     *
     * @param vector 是否需要走向量路（关键词这类"词表"型查询只走 BM25，省一次向量计算）
     */
    private record PlannedQuery(String label, String text, double weight, boolean vector) {
    }

    // ==================== 状态查询 ====================

    public int size() {
        return store.size();
    }

    public List<KnowledgeStore.DocStat> documents() {
        return store.documents();
    }

    public String storePath() {
        return store.storeFile().toString();
    }

    public void clear() {
        store.clear();
    }

    public int deleteSource(String source) {
        return store.removeBySource(source);
    }
}
