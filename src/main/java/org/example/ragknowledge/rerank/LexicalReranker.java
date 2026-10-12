package org.example.ragknowledge.rerank;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.index.Chunk;
import org.example.ragknowledge.index.KnowledgeStore;
import org.example.ragknowledge.index.Tokenizer;
import org.example.ragknowledge.retrieval.Candidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地精排器：用<b>纯 Java 的手工特征</b>模拟交叉编码器的行为，零模型、零依赖、零下载。
 *
 * <p>真正的 cross-encoder（bge-reranker）靠注意力在查询和文档之间做交互。没有模型时，
 * 能做的是把这些"交互信号"手工拆出来加权：</p>
 *
 * <table border="1">
 *   <caption>特征与权重</caption>
 *   <tr><th>特征</th><th>默认权重</th><th>模拟的模型能力</th></tr>
 *   <tr><td>向量余弦（绝对）</td><td>0.30</td><td>整体语义相似度（双塔已经给出的信息）</td></tr>
 *   <tr><td>IDF 加权词项覆盖率</td><td>0.25</td><td>"查询里的关键信息，文档答到了几个"</td></tr>
 *   <tr><td>邻近密度</td><td>0.20</td><td>"这些词是凑在一起，还是散落在全篇"</td></tr>
 *   <tr><td>最长公共子串</td><td>0.15</td><td>精确短语命中（条款号、型号、原文摘抄）</td></tr>
 *   <tr><td>BM25（饱和归一）</td><td>0.10</td><td>关键词强度</td></tr>
 * </table>
 *
 * <p><b>关键设计：所有特征都输出绝对分数（0~1），不做"候选集内 min-max 归一化"。</b>
 * 因为一旦按候选集归一化，排名第一的候选永远得 1.0，就<b>永远无法拒答</b>了 ——
 * 知识库里没有答案时也会硬凑一段给大模型。保持绝对分，{@code kb.rerank.min-score} 才是一道
 * 有意义的门槛。</p>
 */
@Component
public class LexicalReranker implements Reranker {

    /** BM25 饱和归一化的半饱和点：score/(score+HALF_SATURATION) */
    private static final double BM25_HALF_SATURATION = 8.0;

    private final KnowledgeStore store;
    private final KnowledgeProperties properties;

    public LexicalReranker(KnowledgeStore store, KnowledgeProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public boolean rerank(String query, List<Candidate> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        KnowledgeProperties.Rerank.Lexical weights = properties.getRerank().getLexical();
        double weightSum = weights.getWeightVector() + weights.getWeightCoverage()
                + weights.getWeightProximity() + weights.getWeightPhrase() + weights.getWeightBm25();
        if (weightSum <= 0) {
            return false;
        }

        String normalizedQuery = Tokenizer.normalize(query);
        Set<String> queryTokens = new LinkedHashSet<>(Tokenizer.tokenize(query));

        for (Candidate candidate : candidates) {
            Chunk chunk = candidate.chunk();
            String text = chunk.text();

            double coverage = idfCoverage(queryTokens, text);
            double proximity = proximityDensity(queryTokens, text);
            double phrase = phraseScore(normalizedQuery, text);
            double bm25 = candidate.bm25Score() == null
                    ? 0.0
                    : candidate.bm25Score() / (candidate.bm25Score() + BM25_HALF_SATURATION);
            double vector = candidate.vectorScore() == null ? 0.0 : clamp01(candidate.vectorScore());

            double score = (weights.getWeightVector() * vector
                    + weights.getWeightCoverage() * coverage
                    + weights.getWeightProximity() * proximity
                    + weights.getWeightPhrase() * phrase
                    + weights.getWeightBm25() * bm25) / weightSum;

            candidate.setRerankScore(clamp01(score));
            candidate.setFinalScore(clamp01(score));
        }
        return true;
    }

    /**
     * IDF 加权词项覆盖率：分母是查询里所有词的 IDF 之和，分子是文档命中的那些词的 IDF 之和。
     *
     * <p>用 IDF 加权而不是简单计数，是因为"的""是"这类高频词命中没有意义，
     * 而"年假""ISO-9001"这类稀有词命中一次就说明问题。</p>
     */
    private double idfCoverage(Set<String> queryTokens, String text) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> docTokens = Tokenizer.tokenSet(text);
        double total = 0;
        double hit = 0;
        for (String token : queryTokens) {
            // 语料里没出现过该词时 idf 为 0，此时给它一个极小权重，避免分母为 0
            double weight = Math.max(store.bm25().idfOf(token), 1e-6);
            total += weight;
            if (docTokens.contains(token)) {
                hit += weight;
            }
        }
        return total <= 0 ? 0 : hit / total;
    }

    /**
     * 邻近密度：查询词在文档中是否"扎堆出现"。
     *
     * <p>做法是把文档里命中查询词的位置抽出来，用滑动窗口找到<b>包含最多个不同查询词的最小区间</b>，
     * 然后：</p>
     * <pre>
     *   proximity = (窗口内不同查询词数 / 查询词总数) * (窗口内不同查询词数 / 窗口长度)
     * </pre>
     * <p>前一项是"答到了多少"，后一项是"有多紧凑"。两项都必要：只算紧凑度的话，
     * 只命中一个词但恰好在一个位置也会得满分。</p>
     */
    private double proximityDensity(Set<String> queryTokens, String text) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        List<String> docTokens = Tokenizer.tokenize(text);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < docTokens.size(); i++) {
            if (queryTokens.contains(docTokens.get(i))) {
                positions.add(i);
            }
        }
        if (positions.isEmpty()) {
            return 0;
        }

        Map<String, Integer> window = new HashMap<>();
        int left = 0;
        int bestDistinct = 0;
        int bestSpan = Integer.MAX_VALUE;
        for (int right = 0; right < positions.size(); right++) {
            window.merge(docTokens.get(positions.get(right)), 1, Integer::sum);
            // 收缩左边界：只要窗口内仍然包含同样多的不同查询词就继续收
            while (true) {
                String leftToken = docTokens.get(positions.get(left));
                if (window.getOrDefault(leftToken, 0) > 1) {
                    window.merge(leftToken, -1, Integer::sum);
                    left++;
                } else {
                    break;
                }
            }
            int distinct = window.size();
            int span = positions.get(right) - positions.get(left) + 1;
            if (distinct > bestDistinct || (distinct == bestDistinct && span < bestSpan)) {
                bestDistinct = distinct;
                bestSpan = span;
            }
        }
        if (bestSpan <= 0) {
            bestSpan = 1;
        }
        double answered = (double) bestDistinct / queryTokens.size();
        double compact = (double) bestDistinct / bestSpan;
        return clamp01(answered * compact);
    }

    /**
     * 最长公共子串：查询中有多长的一段在文档里原样出现。
     *
     * <p>这一项专门抓"向量看不懂、BM25 又太散"的精确匹配，例如查 {@code "ISO-9001"} 时
     * 文档里也写着 {@code "ISO-9001"}，整串命中直接得满分。</p>
     */
    private double phraseScore(String normalizedQuery, String text) {
        if (normalizedQuery.isEmpty() || text == null || text.isEmpty()) {
            return 0;
        }
        String normalizedText = Tokenizer.normalize(text);
        int longest = longestCommonSubstring(normalizedQuery, normalizedText);
        return clamp01((double) longest / normalizedQuery.length());
    }

    /**
     * 最长公共子串（滚动数组 DP，只保留上一行）。
     * 查询通常十几个字符、段落几百字符，单次不到万级比较，20 个候选合计在毫秒量级。
     */
    static int longestCommonSubstring(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0 || m == 0) {
            return 0;
        }
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        int best = 0;
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ca == b.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    if (current[j] > best) {
                        best = current[j];
                    }
                } else {
                    current[j] = 0;
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
            java.util.Arrays.fill(current, 0);
        }
        return best;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }
}
