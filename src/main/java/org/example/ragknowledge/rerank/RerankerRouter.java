package org.example.ragknowledge.rerank;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.retrieval.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 精排调度器：按配置选择精排实现，并保证<b>任何情况下都不会让问答挂掉</b>。
 *
 * <p>模式：</p>
 * <table border="1">
 *   <caption>kb.rerank.mode 取值</caption>
 *   <tr><th>取值</th><th>行为</th></tr>
 *   <tr><td>{@code off}</td><td>不精排，直接用 RRF 融合分（候选集内归一化后展示）</td></tr>
 *   <tr><td>{@code local}</td><td>纯 Java 本地精排</td></tr>
 *   <tr><td>{@code http}</td><td>调 bge-reranker 服务；失败/超时自动降级为本地精排，并标记 degraded</td></tr>
 *   <tr><td>{@code auto}（默认）</td><td>配了 {@code kb.rerank.api-url} 就走 http，否则走 local</td></tr>
 * </table>
 *
 * <p><b>为什么 http 模式失败不直接报错？</b>检索链路里精排是"锦上添花"的一层：
 * 它挂掉时，融合分排序依然能给出可用的答案。把它做成硬依赖，等于让一个可选组件
 * 拥有拖垮整个服务的能力 —— 降级 + 明确标记（{@code degraded=true} 会显示在界面上）
 * 才是正确的取舍：用户能看到"这次没用上精排模型"，而不是看到一个 500。</p>
 */
@Component
public class RerankerRouter {

    private static final Logger log = LoggerFactory.getLogger(RerankerRouter.class);

    /** BM25 饱和归一化的半饱和点，与 {@link LexicalReranker} 保持一致 */
    private static final double BM25_HALF_SATURATION = 8.0;

    /**
     * 精排执行结果。
     *
     * @param mode     实际生效的模式：off / local / http
     * @param degraded 是否发生了降级（http 失败退回 local）
     * @param detail   降级原因，用于界面提示与排错
     */
    public record Outcome(String mode, boolean degraded, String detail) {

        public static Outcome of(String mode) {
            return new Outcome(mode, false, null);
        }
    }

    private final KnowledgeProperties properties;
    private final LexicalReranker lexicalReranker;
    private final HttpReranker httpReranker;

    public RerankerRouter(KnowledgeProperties properties,
                          LexicalReranker lexicalReranker,
                          HttpReranker httpReranker) {
        this.properties = properties;
        this.lexicalReranker = lexicalReranker;
        this.httpReranker = httpReranker;
    }

    /** 当前配置下"应该"使用的精排模式（不涉及运行时降级） */
    public String configuredMode() {
        String mode = properties.getRerank().getMode();
        if (mode == null || mode.isBlank()) {
            mode = "auto";
        }
        mode = mode.trim().toLowerCase();
        if ("auto".equals(mode)) {
            return httpReranker.isConfigured() ? "http" : "local";
        }
        return mode;
    }

    /**
     * 执行精排；就地写入候选的 rerankScore / finalScore。
     *
     * @param query      判定用的查询（优先使用改写后的独立问题）
     * @param candidates 候选（已按融合分降序，调用方已截断到精排规模）
     */
    public Outcome rerank(String query, List<Candidate> candidates) {
        String mode = configuredMode();
        if (candidates == null || candidates.isEmpty()) {
            return Outcome.of(mode);
        }

        switch (mode) {
            case "off" -> {
                applyFusionScore(candidates);
                return Outcome.of("off");
            }
            case "http" -> {
                if (httpReranker.rerank(query, candidates)) {
                    return Outcome.of("http");
                }
                log.warn("远程精排不可用，自动降级为本地精排（配置：{}）", properties.getRerank().getApiUrl());
                lexicalReranker.rerank(query, candidates);
                return new Outcome("local", true, "远程精排服务不可用，已降级为本地精排");
            }
            default -> {
                String normalized = mode;
                if (!"local".equals(normalized)) {
                    log.warn("未知的精排模式「{}」，按 local 处理（可选值：off/local/http/auto）", normalized);
                }
                lexicalReranker.rerank(query, candidates);
                return Outcome.of("local");
            }
        }
    }

    /**
     * 关闭精排时的兜底：不使用任何交叉特征，直接取两路各自的<b>绝对证据分</b>中较大的那个。
     *
     * <p>向量余弦本身就是绝对分；BM25 无上界，用 {@code x/(x+8)} 饱和到 [0,1]，
     * 再乘 0.6 —— 因为"只有关键词命中"比"语义高度相似"的证据弱一档，
     * 不折算的话一个偶然的词命中就能混进最终答案。这个折算系数是这一路唯一的经验值，
     * 它只影响 {@code off} 模式，不影响任何精排路径。</p>
     *
     * <p>候选顺序<b>仍然保持 RRF 融合序</b>：{@code off} 的语义就是"不做精排、直接看融合结果"，
     * 是给面试对比"精排到底带来了多少提升"用的消融开关。绝对分只用于阈值过滤。</p>
     */
    private void applyFusionScore(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            double vector = candidate.vectorScore() == null ? 0 : clamp01(candidate.vectorScore());
            double bm25 = candidate.bm25Score() == null
                    ? 0
                    : candidate.bm25Score() / (candidate.bm25Score() + BM25_HALF_SATURATION);
            candidate.setRerankScore(null);
            candidate.setFinalScore(Math.max(vector, 0.6 * bm25));
        }
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }
}
