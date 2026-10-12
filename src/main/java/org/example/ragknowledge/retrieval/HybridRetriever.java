package org.example.ragknowledge.retrieval;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.index.Bm25Index;
import org.example.ragknowledge.index.Chunk;
import org.example.ragknowledge.index.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器：<b>向量 + BM25 双路召回 → RRF 融合</b>。
 *
 * <h3>为什么是 RRF，而不是把两路分数加权相加？</h3>
 * <p>两路的分数<b>不可比</b>：余弦相似度在 [0,1] 之间，BM25 是无上界的正数（本文档集里可能是 3，
 * 换一批语料就可能是 30）。直接 {@code 0.5*cos + 0.5*bm25} 会因为量纲不同而实质上被 BM25 主导，
 * 而且每换一次语料就要重新调权重。</p>
 *
 * <p>RRF（Reciprocal Rank Fusion）只用<b>排名</b>不用分数：</p>
 * <pre>
 *   RRF(d) = Σ_i  weight_i / (k + rank_i(d))        k 默认 60
 * </pre>
 * <p>排名是无量纲的，所以天然免疫量纲差异；{@code k} 让前几名之间的差距不至于过分悬殊
 * （k 越大越"民主"，越小越"赢者通吃"）。这个套路来自 2009 年 Cormack 的 SIGIR 论文，
 * 是工业界混合检索的默认做法。</p>
 *
 * <h3>多查询扩展也复用同一套融合</h3>
 * <p>Query 改写会产生多个查询变体，HyDE 会额外产生一路"假设答案"。它们各自召回一批候选，
 * 再用同一个 RRF 公式融合，权重体现"这一路有多可信"（原问题 1.0 &gt; 改写后 0.9 &gt; 同义变体 0.6）。
 * 被多条查询共同召回的块会自然浮到前面 —— 这正是"共识投票"的效果。</p>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /**
     * 一个参与召回的查询变体。
     *
     * @param label  展示用标签，如 "原问题" / "改写" / "变体1" / "HyDE"
     * @param text   查询文本（BM25 用）
     * @param vector 查询向量（向量路用）；为 null 表示这一路只做 BM25
     * @param weight RRF 融合权重
     */
    public record QueryVariant(String label, String text, double[] vector, double weight) {
    }

    /**
     * 执行混合检索。
     *
     * @return 按 RRF 分降序排列的候选（未截断，交由调用方决定送入精排的条数）
     */
    public List<Candidate> retrieve(List<QueryVariant> variants,
                                    KnowledgeStore store,
                                    KnowledgeProperties properties) {
        List<Candidate> candidates = new ArrayList<>();
        List<Chunk> chunks = store.all();
        if (chunks.isEmpty() || variants.isEmpty()) {
            return candidates;
        }

        KnowledgeProperties.Hybrid config = properties.getHybrid();
        int recallTopK = Math.max(1, config.getRecallTopK());
        double k = Math.max(1, config.getRrfK());
        double vectorWeight = config.getVectorWeight();
        double bm25Weight = config.getBm25Weight();
        boolean hybridEnabled = config.isEnabled();

        // ordinal -> 候选，保证同一个块被多路召回时只累积分数、不产生重复条目
        Map<Integer, Candidate> index = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            index.put(chunk.ordinal(), new Candidate(chunk));
        }

        Bm25Index bm25 = hybridEnabled ? store.bm25() : null;
        int dimMismatch = countDimMismatch(chunks, variants);

        for (QueryVariant variant : variants) {
            // ---------- 第 1 路：向量召回 ----------
            if (variant.vector() != null) {
                List<Map.Entry<Integer, Double>> vectorHits =
                        vectorRecall(chunks, variant.vector(), recallTopK, config.getMinVectorScore());
                int rank = 0;
                for (Map.Entry<Integer, Double> hit : vectorHits) {
                    rank++;
                    Candidate candidate = index.get(hit.getKey());
                    if (candidate == null) {
                        continue;
                    }
                    candidate.markRoute("vector");
                    candidate.markQuery(variant.label());
                    candidate.mergeVectorScore(hit.getValue());
                    candidate.addRrfScore(variant.weight() * vectorWeight / (k + rank));
                }
            }

            // ---------- 第 2 路：BM25 关键词召回 ----------
            if (hybridEnabled && bm25 != null && !bm25.isEmpty()) {
                List<Bm25Index.Scored> bm25Hits = bm25.search(variant.text(), recallTopK);
                int rank = 0;
                for (Bm25Index.Scored hit : bm25Hits) {
                    rank++;
                    Candidate candidate = index.get(hit.ordinal());
                    if (candidate == null) {
                        continue;
                    }
                    candidate.markRoute("bm25");
                    candidate.markQuery(variant.label());
                    candidate.mergeBm25Score(hit.score());
                    candidate.addRrfScore(variant.weight() * bm25Weight / (k + rank));
                }
            }
        }

        if (dimMismatch > 0) {
            log.warn("有 {} 个段落的向量维度与当前 Embedding 模型不一致，已跳过；建议清空知识库后重新上传", dimMismatch);
        }

        for (Candidate candidate : index.values()) {
            if (candidate.rrfScore() > 0) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::rrfScore).reversed());
        return candidates;
    }

    /**
     * 向量路召回：暴力扫描全量段落算余弦相似度，过滤阈值后取 topK。
     *
     * <p>这里是 O(N·d) 的全量扫描。当前规模（几千个块、1024 维）单次检索在毫秒级，
     * 不值得为它引入向量数据库；一旦块数上万，把这一步换成 HNSW/IVF 索引即可，
     * 调用方接口不变。</p>
     */
    private List<Map.Entry<Integer, Double>> vectorRecall(List<Chunk> chunks,
                                                          double[] queryVector,
                                                          int topK,
                                                          double minScore) {
        List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.vector() == null || chunk.vector().length != queryVector.length) {
                continue;
            }
            double score = Vectors.cosine(queryVector, chunk.vector());
            if (score < minScore) {
                continue;
            }
            scored.add(Map.entry(chunk.ordinal(), score));
        }
        scored.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }

    private int countSameDim(List<Chunk> chunks, double[] queryVector) {
        int same = 0;
        for (Chunk chunk : chunks) {
            if (chunk.vector() != null && chunk.vector().length == queryVector.length) {
                same++;
            }
        }
        return same;
    }

    /** 统计向量维度与当前 Embedding 模型不一致的段落数（更换模型后会大量出现） */
    private int countDimMismatch(List<Chunk> chunks, List<QueryVariant> variants) {
        double[] reference = null;
        for (QueryVariant variant : variants) {
            if (variant.vector() != null) {
                reference = variant.vector();
                break;
            }
        }
        if (reference == null) {
            return 0;
        }
        return chunks.size() - countSameDim(chunks, reference);
    }
}
