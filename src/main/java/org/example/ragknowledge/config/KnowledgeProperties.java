package org.example.ragknowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库检索与切段参数，对应 application.properties 中的 {@code kb.*}。
 *
 * <p>三层检索能力各自有独立的配置块：</p>
 * <ul>
 *   <li>{@link Hybrid} —— 第 1 层：向量 + BM25 双路召回与 RRF 融合；</li>
 *   <li>{@link Rerank} —— 第 2 层：精排模型与最终阈值；</li>
 *   <li>{@link Rewrite} / {@link Hyde} —— 第 3 层：Query 改写与 HyDE。</li>
 * </ul>
 *
 * <p>运行时可通过页面「知识库设置」动态修改，重启后恢复为配置文件中的默认值。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb")
public class KnowledgeProperties {

    /** 单个知识块的目标字数 */
    private int chunkSize = 300;

    /** 相邻知识块的重叠字数：避免一句话被切断后跨块问题检索不到 */
    private int chunkOverlap = 60;

    /** 单次调用 Embedding 接口的最大条数（批量请求，减少 HTTP 往返） */
    private int batchSize = 8;

    /** 知识库持久化文件路径（相对路径基于应用启动目录） */
    private String storePath = "data/knowledge-base.json";

    /** 最终送入大模型的片段数 */
    private int topK = 5;

    private final Hybrid hybrid = new Hybrid();
    private final Rerank rerank = new Rerank();
    private final Rewrite rewrite = new Rewrite();
    private final Hyde hyde = new Hyde();

    // ==================== 第 1 层：混合检索 ====================

    /** 混合检索（向量 + BM25）配置 */
    public static class Hybrid {

        /** 是否开启混合检索；关闭后退化为纯向量检索 */
        private boolean enabled = true;

        /** 每一路各自召回的候选条数 */
        private int recallTopK = 10;

        /** 向量路召回门槛（余弦相似度），低于它的段落不进候选池；0 表示不过滤 */
        private double minVectorScore = 0.25;

        /** RRF 公式 score = Σ weight / (k + rank) 中的 k */
        private int rrfK = 60;

        /** 向量路在 RRF 融合中的权重 */
        private double vectorWeight = 1.0;

        /** BM25 路在 RRF 融合中的权重 */
        private double bm25Weight = 1.0;

        /**
         * BM25 路单独召回多少条（与向量路的 {@code recallTopK} 分开）。
         *
         * <p>BM25 的特点是<b>头部准、尾部全是噪声</b>：前几条通常是真命中，
         * 后面的都是碰巧共享一两个 bigram 的无关块。而 RRF 的分母 {@code k=60}
         * 让各名次的分差极小（第 1 名 1/61 与第 10 名 1/70 只差 13%），
         * 尾部那些噪声票足以翻转 top-10 的顺序。</p>
         *
         * <p>所以这一路需要一个比向量路更浅的召回深度。默认值与实测结论见
         * {@code docs/评测与调参.md}。</p>
         */
        private int bm25TopK = 2;

        /** BM25 词频饱和参数 k1：越大越强调词频 */
        private double bm25K1 = 1.2;

        /** BM25 长度归一化参数 b：0 不归一化，1 完全归一化 */
        private double bm25B = 0.75;

        public int getBm25TopK() {
            return bm25TopK;
        }

        public void setBm25TopK(int bm25TopK) {
            this.bm25TopK = bm25TopK;
        }

        public double getBm25K1() {
            return bm25K1;
        }

        public void setBm25K1(double bm25K1) {
            this.bm25K1 = bm25K1;
        }

        public double getBm25B() {
            return bm25B;
        }

        public void setBm25B(double bm25B) {
            this.bm25B = bm25B;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public double getMinVectorScore() {
            return minVectorScore;
        }

        public void setMinVectorScore(double minVectorScore) {
            this.minVectorScore = minVectorScore;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getBm25Weight() {
            return bm25Weight;
        }

        public void setBm25Weight(double bm25Weight) {
            this.bm25Weight = bm25Weight;
        }
    }

    // ==================== 第 2 层：精排 ====================

    /** 精排（Rerank）配置 */
    public static class Rerank {

        /** 送入精排的候选数 */
        private int candidates = 20;

        /** off | local | http | auto */
        private String mode = "auto";

        /** 精排后的最终分阈值，低于它的候选直接丢弃；全部低于则拒答。0 表示不过滤 */
        private double minScore = 0.15;

        /** 外部 bge-reranker 推理服务地址（Jina / Cohere / TEI 兼容的 /rerank 协议） */
        private String apiUrl = "";

        /** 外部精排服务 Key（本地自建可留空） */
        private String apiKey = "";

        /** 外部精排模型名 */
        private String model = "bge-reranker-v2-m3";

        /** 外部精排服务超时（毫秒），超时自动降级为本地精排 */
        private int timeoutMs = 8000;

        private final Lexical lexical = new Lexical();

        public int getCandidates() {
            return candidates;
        }

        public void setCandidates(int candidates) {
            this.candidates = candidates;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Lexical getLexical() {
            return lexical;
        }

        /**
         * 本地纯 Java 精排的特征权重。
         *
         * <p><b>这几个默认值是实测扫出来的，不是拍的。</b>在 40 条评测用例上的结果（Hit@5）：</p>
         * <pre>
         *   向量/覆盖/邻近/短语/BM25
         *   0.30/0.25/0.20/0.15/0.10  → 84%   （最初的"想当然"配比）
         *   0.60/0.20/0.10/0.05/0.05  → 84%
         *   1.00/0.15/0.08/0.04/0.03  → 95%   ← 现在的默认
         *   1.00/0.00/0.00/0.00/0.00  → 97%   （纯余弦，等价于不精排）
         * </pre>
         *
         * <p>结论是反直觉的：<b>词面特征加得越多，排序越差</b>。原因是这个语料只有
         * 124 个块、embedding-3 又足够强，向量本身已经把答案排到前面了，
         * 任何词面信号都是在往一个已经正确的排序里注入噪声 ——
         * 最典型的失败是"问题里提到 A、答案却是 B"（例如问"哪个组件比 OpenFeign 更快"，
         * 词面匹配会把 OpenFeign 那一节顶上来，而正确答案是 Dubbo）。</p>
         *
         * <p>所以这里的原则是：<b>把向量当主信号，词面特征只当轻微的并列打破器</b>。
         * 语料变大、embedding 变弱时，这个配比应当重新扫一遍（见 docs/评测与调参.md）。</p>
         */
        public static class Lexical {

            /** 原始向量余弦相似度：主信号 */
            private double weightVector = 1.00;

            /** IDF 加权的词项覆盖率 */
            private double weightCoverage = 0.15;

            /** 查询词在文中的邻近密度 */
            private double weightProximity = 0.08;

            /** 最长公共子串（短语命中） */
            private double weightPhrase = 0.04;

            /** 归一化后的 BM25 分 */
            private double weightBm25 = 0.03;

            public double getWeightVector() {
                return weightVector;
            }

            public void setWeightVector(double weightVector) {
                this.weightVector = weightVector;
            }

            public double getWeightCoverage() {
                return weightCoverage;
            }

            public void setWeightCoverage(double weightCoverage) {
                this.weightCoverage = weightCoverage;
            }

            public double getWeightProximity() {
                return weightProximity;
            }

            public void setWeightProximity(double weightProximity) {
                this.weightProximity = weightProximity;
            }

            public double getWeightPhrase() {
                return weightPhrase;
            }

            public void setWeightPhrase(double weightPhrase) {
                this.weightPhrase = weightPhrase;
            }

            public double getWeightBm25() {
                return weightBm25;
            }

            public void setWeightBm25(double weightBm25) {
                this.weightBm25 = weightBm25;
            }
        }
    }

    // ==================== 第 3 层：Query 改写 / HyDE ====================

    /** Query 改写配置 */
    public static class Rewrite {

        /** 是否开启 Query 改写（指代消解 + 多查询扩展） */
        private boolean enabled = true;

        /** 额外生成的同义改写条数，0 表示只做指代消解 */
        private int variants = 2;

        /** 改写后的独立问题在 RRF 融合中的权重 */
        private double standaloneWeight = 0.9;

        /** 同义变体在 RRF 融合中的权重 */
        private double variantWeight = 0.6;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getVariants() {
            return variants;
        }

        public void setVariants(int variants) {
            this.variants = variants;
        }

        public double getStandaloneWeight() {
            return standaloneWeight;
        }

        public void setStandaloneWeight(double standaloneWeight) {
            this.standaloneWeight = standaloneWeight;
        }

        public double getVariantWeight() {
            return variantWeight;
        }

        public void setVariantWeight(double variantWeight) {
            this.variantWeight = variantWeight;
        }
    }

    /** HyDE 配置 */
    public static class Hyde {

        /** 是否开启 HyDE（先生成假设答案再检索），默认关：会多一次 LLM 调用 */
        private boolean enabled = false;

        /** HyDE 假设答案在 RRF 融合中的权重 */
        private double weight = 0.8;

        /** 假设答案的最大字数，太长会稀释向量 */
        private int maxChars = 400;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }
    }

    // ==================== 顶层 getter / setter ====================

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getStorePath() {
        return storePath;
    }

    public void setStorePath(String storePath) {
        this.storePath = storePath;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public Hybrid getHybrid() {
        return hybrid;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Rewrite getRewrite() {
        return rewrite;
    }

    public Hyde getHyde() {
        return hyde;
    }
}
