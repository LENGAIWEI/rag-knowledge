package org.example.ragknowledge.rerank;

import org.example.ragknowledge.retrieval.Candidate;

import java.util.List;

/**
 * 精排器（Rerank）统一接口。
 *
 * <p>召回和精排是两种完全不同的模型形态，这一点是 RAG 面试的高频考点：</p>
 * <ul>
 *   <li><b>召回（双塔 / Bi-Encoder）</b>：查询和文档<b>分别</b>编码成向量，可以离线算好文档向量，
 *       线上只算查询向量再做近似最近邻。代价是查询和文档之间<b>没有任何交互</b>，
 *       只能比对"整体语义像不像"；</li>
 *   <li><b>精排（交叉编码器 / Cross-Encoder）</b>：把 {@code [查询, 文档]} 拼成一个序列送进模型，
 *       让注意力机制在两者之间自由交互，能捕捉"这个数字对不对得上""这个条件是否被满足"。
 *       精度显著更高，但<b>无法预计算</b>，每个 (查询,文档) 对都要跑一次前向，所以只能用在
 *       召回之后的小候选集上。</li>
 * </ul>
 *
 * <p>因此标准做法是"粗排召回 top50~100 → 精排 top3~5"：用廉价的召回把候选压到精排能承受的规模，
 * 再用昂贵的精排把答案排到最前面。本接口就是这个"精排"的抽象。</p>
 *
 * <p>实现类会就地写入 {@link Candidate#setRerankScore(Double)}，返回 false 表示本次没有打分，
 * 调用方应回退到融合分排序（保证任何一路失效都不会让问答挂掉）。</p>
 */
public interface Reranker {

    /** 实现名，会展示在前端（如 {@code local} / {@code http}），便于线上确认到底走的哪条路 */
    String name();

    /**
     * 对候选就地打精排分。
     *
     * @param query      用于判定的查询（通常是改写后的独立问题）
     * @param candidates 候选列表，方法内部会写入 rerankScore
     * @return 是否成功打出了分数
     */
    boolean rerank(String query, List<Candidate> candidates);
}
