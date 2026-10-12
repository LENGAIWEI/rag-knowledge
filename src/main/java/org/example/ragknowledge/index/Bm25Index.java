package org.example.ragknowledge.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 倒排索引（纯 Java 实现，零依赖）。
 *
 * <p>BM25 是关键词检索的事实标准，也是"向量检索之外必须有的一路"，因为两者互补：</p>
 * <ul>
 *   <li><b>向量检索</b>擅长语义近似（"怎么请假" ↔ "休假申请流程"），但对<b>专有名词、编号、型号、
 *       精确短语</b>很弱（"ISO-9001" 和 "ISO-9002" 在向量空间里几乎一样近）；</li>
 *   <li><b>BM25</b>正好相反：精确词命中极准，但同义词、转述完全无能为力。</li>
 * </ul>
 *
 * <p>打分公式（对每个查询词 t 累加）：</p>
 * <pre>
 *   IDF(t)   = ln(1 + (N - df + 0.5) / (df + 0.5))
 *   score   += IDF(t) * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl))
 * </pre>
 * <ul>
 *   <li>{@code tf}：词 t 在该块中出现的次数 —— 出现越多越相关，但用 {@code k1} 做饱和，
 *       避免"同一个词刷 20 遍"就冲到第一；</li>
 *   <li>{@code df}：包含 t 的块数 —— 越稀有越有区分度（IDF）；</li>
 *   <li>{@code dl / avgdl}：块长度归一化，用 {@code b} 控制强度，避免长块靠"词多"占便宜。</li>
 * </ul>
 *
 * <p>倒排表用扁平 {@code int[]} 存储（{@code [ordinal, tf, ordinal, tf, ...]}），
 * 相比 {@code List<Posting>} 省掉大量小对象，检索时只做数组遍历。</p>
 */
public final class Bm25Index {

    /** 检索结果：块序号 + BM25 分 */
    public record Scored(int ordinal, double score) {
    }

    private static final Bm25Index EMPTY = new Bm25Index(Map.of(), Map.of(), 0, 1.0, 1.2, 0.75, Map.of());

    /** term -> [ordinal, tf, ordinal, tf, ...] */
    private final Map<String, int[]> postings;

    /** ordinal -> 该块的总词数 */
    private final Map<Integer, Integer> docLengths;

    private final int docCount;
    private final double avgDocLength;
    private final double k1;
    private final double b;

    /** 预计算 IDF，避免每次查询重复算一遍 */
    private final Map<String, Double> idf;

    private Bm25Index(Map<String, int[]> postings, Map<Integer, Integer> docLengths, int docCount,
                      double avgDocLength, double k1, double b, Map<String, Double> idf) {
        this.postings = postings;
        this.docLengths = docLengths;
        this.docCount = docCount;
        this.avgDocLength = avgDocLength;
        this.k1 = k1;
        this.b = b;
        this.idf = idf;
    }

    public static Bm25Index empty() {
        return EMPTY;
    }

    /** 构建索引；语料为空时返回空索引 */
    public static Bm25Index build(List<Chunk> chunks, double k1, double b) {
        if (chunks == null || chunks.isEmpty()) {
            return empty();
        }
        // ordinal -> term -> tf
        Map<Integer, Map<String, Integer>> docTerms = new HashMap<>();
        Map<Integer, Integer> lengths = new HashMap<>();
        Map<String, Integer> docFreq = new HashMap<>();

        for (Chunk chunk : chunks) {
            List<String> tokens = Tokenizer.tokenize(chunk.indexableText());
            if (tokens.isEmpty()) {
                continue;
            }
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            docTerms.put(chunk.ordinal(), tf);
            lengths.put(chunk.ordinal(), tokens.size());
            for (String term : tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }

        int n = lengths.size();
        if (n == 0) {
            return empty();
        }

        // 倒排表：term -> 扁平数组 [ordinal, tf, ...]
        Map<String, List<Integer>> building = new HashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : docTerms.entrySet()) {
            int ordinal = entry.getKey();
            for (Map.Entry<String, Integer> term : entry.getValue().entrySet()) {
                List<Integer> list = building.computeIfAbsent(term.getKey(), k -> new ArrayList<>());
                list.add(ordinal);
                list.add(term.getValue());
            }
        }
        Map<String, int[]> postings = new HashMap<>(building.size() * 2);
        for (Map.Entry<String, List<Integer>> entry : building.entrySet()) {
            List<Integer> list = entry.getValue();
            int[] flat = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                flat[i] = list.get(i);
            }
            postings.put(entry.getKey(), flat);
        }

        double avg = lengths.values().stream().mapToInt(Integer::intValue).average().orElse(1.0);
        Map<String, Double> idf = new HashMap<>(docFreq.size() * 2);
        for (Map.Entry<String, Integer> entry : docFreq.entrySet()) {
            idf.put(entry.getKey(), computeIdf(n, entry.getValue()));
        }

        return new Bm25Index(postings, lengths, n, avg <= 0 ? 1.0 : avg, k1, b, idf);
    }

    /**
     * 计算某个词在当前语料下的 IDF；语料里没有这个词时返回 0（视为无区分度）。
     * 精排阶段要用它做"IDF 加权覆盖率"。
     */
    public double idfOf(String term) {
        Double value = idf.get(term);
        return value == null ? 0.0 : value;
    }

    public int size() {
        return docCount;
    }

    public boolean isEmpty() {
        return docCount == 0;
    }

    /**
     * 检索：对查询分词后逐个查倒排表累加得分，返回分数 &gt; 0 的前 topK 条。
     *
     * @param query 查询原文
     * @param topK  返回条数上限
     */
    public List<Scored> search(String query, int topK) {
        if (docCount == 0 || topK <= 0) {
            return List.of();
        }
        List<String> tokens = Tokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        Map<Integer, Double> scores = new HashMap<>();
        for (String term : tokens) {
            int[] posting = postings.get(term);
            if (posting == null) {
                continue;
            }
            double termIdf = idfOf(term);
            if (termIdf <= 0) {
                continue;
            }
            for (int i = 0; i < posting.length; i += 2) {
                int ordinal = posting[i];
                int tf = posting[i + 1];
                int dl = docLengths.getOrDefault(ordinal, 1);
                double denominator = tf + k1 * (1 - b + b * dl / avgDocLength);
                double contribution = termIdf * (tf * (k1 + 1)) / denominator;
                scores.merge(ordinal, contribution, Double::sum);
            }
        }

        List<Scored> ranked = new ArrayList<>(scores.size());
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            ranked.add(new Scored(entry.getKey(), entry.getValue()));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());
        return ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked;
    }

    private static double computeIdf(int docCount, int docFreq) {
        return Math.log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
    }
}
