package org.example.ragknowledge;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.index.Bm25Index;
import org.example.ragknowledge.index.Chunk;
import org.example.ragknowledge.index.KnowledgeStore;
import org.example.ragknowledge.index.TextSplitter;
import org.example.ragknowledge.index.Tokenizer;
import org.example.ragknowledge.retrieval.Candidate;
import org.example.ragknowledge.retrieval.HybridRetriever;
import org.example.ragknowledge.rerank.LexicalReranker;
import org.example.ragknowledge.rerank.RerankerRouter;
import org.example.ragknowledge.rerank.HttpReranker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索链路的算法测试：分词、切段、BM25、RRF 融合、本地精排、精排模式选择。
 *
 * <p>不带 Spring 上下文（毫秒级跑完，不需要网络和密钥），
 * 直接构造对象验证算法本身。这些是整条链路里最容易出错、也最难靠肉眼发现问题的部分：
 * 分词切错、IDF 算错、融合顺序不对，表现都是"答案质量变差"这种软症状，
 * 有断言才能守住。</p>
 *
 * <pre>mvnw.cmd test</pre>
 */
class RetrievalPipelineTest {

    private static final String DOC_LEAVE = "员工年假天数根据工龄确定：工龄满一年享五天年假，满三年享十天。";
    private static final String DOC_APPLY = "请假需要提前三天向直属主管提交申请，审批通过后方可休假。";
    private static final String DOC_FEE = "公司报销流程：填写报销单，附上发票，交财务审核后打款。";
    private static final String DOC_ISO = "ISO-9001 质量管理体系认证需要每年进行一次监督审核。";

    // ==================== 测试夹具 ====================

    private KnowledgeProperties properties() {
        KnowledgeProperties properties = new KnowledgeProperties();
        // 落盘到 target/，绝不碰 data/ 下的真实知识库
        properties.setStorePath("target/test-kb.json");
        return properties;
    }

    /** 四个语义无关的段落，各配一个正交向量，方便验证"哪一路召回了谁" */
    private KnowledgeStore sampleStore(KnowledgeProperties properties) {
        KnowledgeStore store = new KnowledgeStore(properties);
        store.addAll(List.of(
                new Chunk(DOC_LEAVE, "员工手册", "休假 > 年假", 1, new double[]{1, 0, 0, 0}),
                new Chunk(DOC_APPLY, "员工手册", "休假 > 请假流程", 2, new double[]{0, 1, 0, 0}),
                new Chunk(DOC_FEE, "财务制度", "报销", 3, new double[]{0, 0, 1, 0}),
                new Chunk(DOC_ISO, "质量手册", "认证", 4, new double[]{0, 0, 0, 1})
        ));
        return store;
    }

    // ==================== 分词 ====================

    @Test
    @DisplayName("中文按二元组切分，并处理全角字母与停用词")
    void tokenizerHandlesChineseBigramsAndFullWidth() {
        List<String> tokens = Tokenizer.tokenize("请假需要提前几天？");
        assertTrue(tokens.contains("请假"), "应切出 bigram「请假」，实际 " + tokens);
        assertTrue(tokens.contains("提前"), "应切出 bigram「提前」，实际 " + tokens);
        assertTrue(tokens.contains("几天"), "应切出 bigram「几天」，实际 " + tokens);
        assertFalse(tokens.contains("的"), "停用词应被过滤");

        // 全角字母必须归一化成小写 token。
        // 这是一个真实踩过的坑：全角转半角得到大写 R 后如果不再转小写，
        // 后续字符判定（只认 a-z）会把它整个丢掉。
        List<String> fullWidth = Tokenizer.tokenize("ＲＡＧ 知识库 v2");
        assertTrue(fullWidth.contains("rag"), "全角字母应归一化为小写，实际 " + fullWidth);
        assertTrue(fullWidth.contains("知识"), "全角字母不影响中文切分，实际 " + fullWidth);
        assertTrue(fullWidth.contains("v2"), "字母数字应整体成词，实际 " + fullWidth);

        assertTrue(Tokenizer.tokenize("ISO-9001").contains("9001"), "型号数字应保留");
        assertTrue(Tokenizer.tokenize("").isEmpty(), "空串应返回空列表");
    }

    // ==================== 切段 ====================

    @Test
    @DisplayName("Markdown 标题层级被记录为章节路径，且不产生只有标题的空块")
    void splitterTracksHeadingPath() {
        String markdown = """
                # 员工手册

                ## 休假制度

                ##### 年假

                工龄满一年享五天年假。

                ##### 请假流程

                提前三天提交申请。
                """;
        List<TextSplitter.Segment> segments = TextSplitter.split(markdown, 300, 60);

        // 「# 员工手册」「## 休假制度」下面直接跟着下一级标题，没有正文，不应产出空块
        assertEquals(2, segments.size(), "应只产出 2 个有正文的块，实际 " + describe(segments));
        assertEquals("员工手册 > 休假制度 > 年假", segments.get(0).section());
        assertEquals("员工手册 > 休假制度 > 请假流程", segments.get(1).section());
        assertTrue(segments.get(0).text().contains("工龄满一年享五天年假"), "正文应保留在块内");
    }

    @Test
    @DisplayName("无标题文本退化为纯长度切分，章节为空")
    void splitterFallsBackForPlainText() {
        List<TextSplitter.Segment> segments =
                TextSplitter.split("这是一段没有任何标题的纯文本。".repeat(40), 300, 60);
        assertTrue(segments.size() > 1, "超长文本应被切成多块");
        for (TextSplitter.Segment segment : segments) {
            assertNull(segment.section(), "无标题文本不应有章节");
            assertTrue(segment.text().length() <= 400, "块长度应受控，实际 " + segment.text().length());
        }
    }

    // ==================== BM25 ====================

    @Test
    @DisplayName("BM25 精确命中关键词与型号，且对无关查询返回空")
    void bm25RanksExactKeywordsFirst() {
        KnowledgeStore store = sampleStore(properties());
        Bm25Index index = store.bm25();

        assertEquals(1, topOrdinal(index.search("年假", 3)), "「年假」应命中年假段落");
        assertEquals(4, topOrdinal(index.search("ISO-9001 认证", 3)), "「ISO-9001」应精确命中质量手册");
        assertEquals(2, topOrdinal(index.search("怎么请假", 3)), "「怎么请假」应命中请假流程段落");
        assertTrue(index.search("今天天气怎么样", 3).isEmpty(), "完全无关的查询不应有命中");
        assertTrue(index.idfOf("年假") > 0, "语料中出现过的词应有正的 IDF");
        assertEquals(0.0, index.idfOf("不存在的词"), "语料中没有的词 IDF 应为 0");
    }

    // ==================== 混合检索 / RRF ====================

    @Test
    @DisplayName("两路召回各自贡献候选，互不覆盖")
    void hybridRetrievalCombinesBothRoutes() {
        KnowledgeProperties properties = properties();
        KnowledgeStore store = sampleStore(properties);
        HybridRetriever retriever = new HybridRetriever();

        // 向量指向"报销"（ordinal 3），BM25 指向"年假"（ordinal 1）
        List<HybridRetriever.QueryVariant> variants = List.of(
                new HybridRetriever.QueryVariant("原问题", "年假", new double[]{0.2, 0, 0.98, 0}, 1.0));

        List<Candidate> candidates = retriever.retrieve(variants, store, properties);

        assertTrue(candidates.stream().anyMatch(c -> c.routes().contains("vector")), "应有向量路候选");
        assertTrue(candidates.stream().anyMatch(c -> c.routes().contains("bm25")), "应有 BM25 路候选");
        assertNotNull(find(candidates, 1).bm25Score(), "年假段落应由 BM25 召回");
        assertNotNull(find(candidates, 3).vectorScore(), "报销段落应由向量召回");
    }

    @Test
    @DisplayName("RRF 让被多条查询共同召回的块排到最前（共识投票）")
    void rrfRewardsConsensusAcrossQueries() {
        KnowledgeProperties properties = properties();
        KnowledgeStore store = sampleStore(properties);
        HybridRetriever retriever = new HybridRetriever();

        List<HybridRetriever.QueryVariant> variants = List.of(
                new HybridRetriever.QueryVariant("原问题", "年假有几天", new double[]{0.9, 0.1, 0, 0}, 1.0),
                new HybridRetriever.QueryVariant("改写", "年假天数", new double[]{0.9, 0.1, 0, 0}, 0.9));

        List<Candidate> candidates = retriever.retrieve(variants, store, properties);
        assertEquals(1, candidates.get(0).chunk().ordinal(), "两条查询都指向年假，它应排第一");
        assertTrue(candidates.get(0).matchedQueries().size() >= 2, "应记录被多条查询命中");
    }

    @Test
    @DisplayName("关闭混合检索时只剩向量一路")
    void hybridCanBeDisabled() {
        KnowledgeProperties properties = properties();
        properties.getHybrid().setEnabled(false);
        KnowledgeStore store = sampleStore(properties);
        HybridRetriever retriever = new HybridRetriever();

        List<HybridRetriever.QueryVariant> variants = List.of(
                new HybridRetriever.QueryVariant("原问题", "年假", new double[]{0.9, 0.1, 0, 0}, 1.0));

        List<Candidate> candidates = retriever.retrieve(variants, store, properties);
        assertTrue(candidates.stream().allMatch(c -> c.routes().equals(java.util.Set.of("vector"))),
                "关闭混合检索后不应出现 BM25 候选");
    }

    // ==================== 本地精排 ====================

    @Test
    @DisplayName("本地精排把精确型号命中排到第一，且给绝对高分")
    void lexicalRerankerPrefersExactMatch() {
        KnowledgeProperties properties = properties();
        KnowledgeStore store = sampleStore(properties);
        LexicalReranker reranker = new LexicalReranker(store, properties);

        List<Candidate> candidates = candidatesWithVector(store, 0.5);
        assertTrue(reranker.rerank("ISO-9001 认证每年审核几次", candidates));

        Candidate best = candidates.stream().max((a, b) -> Double.compare(a.finalScore(), b.finalScore())).orElseThrow();
        assertEquals(4, best.chunk().ordinal(), "精确型号命中应排第一");
        assertTrue(best.finalScore() > 0.5, "精确命中应得到绝对高分，实际 " + best.finalScore());

        // 分数必须是绝对的：换一批候选项，同一个块的分不应因为"排名第一"就变成 1.0
        assertTrue(best.finalScore() < 1.0, "绝对分不应被归一化到满分");
    }

    @Test
    @DisplayName("本地精排能识别语义转述（靠向量分 + 邻近密度）")
    void lexicalRerankerPrefersSemanticMatch() {
        KnowledgeProperties properties = properties();
        KnowledgeStore store = sampleStore(properties);
        LexicalReranker reranker = new LexicalReranker(store, properties);

        List<Candidate> candidates = candidatesWithVector(store, 0.4);
        reranker.rerank("休假申请要走什么流程", candidates);

        Candidate best = candidates.stream().max((a, b) -> Double.compare(a.finalScore(), b.finalScore())).orElseThrow();
        assertEquals(2, best.chunk().ordinal(), "「休假申请流程」应命中请假流程段落");
    }

    // ==================== 精排模式 ====================

    @Test
    @DisplayName("精排模式：auto 在没配服务地址时落到 local，配了则落到 http")
    void rerankerRouterSelectsMode() {
        KnowledgeProperties properties = properties();
        HttpReranker httpReranker = new HttpReranker(properties);
        LexicalReranker lexicalReranker = new LexicalReranker(new KnowledgeStore(properties), properties);
        RerankerRouter router = new RerankerRouter(properties, lexicalReranker, httpReranker);

        properties.getRerank().setMode("auto");
        properties.getRerank().setApiUrl("");
        assertEquals("local", router.configuredMode(), "没配服务地址时 auto 应落到 local");

        properties.getRerank().setApiUrl("http://127.0.0.1:8090/rerank");
        assertEquals("http", router.configuredMode(), "配了服务地址时 auto 应落到 http");

        properties.getRerank().setMode("off");
        assertEquals("off", router.configuredMode());
    }

    @Test
    @DisplayName("远程精排不可用时自动降级为本地精排，并标记 degraded")
    void rerankerRouterDegradesGracefully() {
        KnowledgeProperties properties = properties();
        properties.getRerank().setMode("http");
        // 指向一个不会有人监听的端口
        properties.getRerank().setApiUrl("http://127.0.0.1:59999/rerank");
        properties.getRerank().setTimeoutMs(800);

        KnowledgeStore store = sampleStore(properties);
        HttpReranker httpReranker = new HttpReranker(properties);
        LexicalReranker lexicalReranker = new LexicalReranker(store, properties);
        RerankerRouter router = new RerankerRouter(properties, lexicalReranker, httpReranker);

        List<Candidate> candidates = candidatesWithVector(store, 0.5);
        RerankerRouter.Outcome outcome = router.rerank("年假有几天", candidates);

        assertEquals("local", outcome.mode(), "远程精排失败应降级为本地精排");
        assertTrue(outcome.degraded(), "应标记为降级");
        assertNotNull(outcome.detail(), "应给出降级原因");
        assertTrue(candidates.stream().allMatch(c -> c.rerankScore() != null),
                "降级后仍应打出精排分，候选不能被丢下");
    }

    // ==================== 工具 ====================

    private List<Candidate> candidatesWithVector(KnowledgeStore store, double vectorScore) {
        List<Candidate> candidates = new ArrayList<>();
        for (Chunk chunk : store.all()) {
            Candidate candidate = new Candidate(chunk);
            candidate.mergeVectorScore(vectorScore);
            candidates.add(candidate);
        }
        return candidates;
    }

    private static int topOrdinal(List<Bm25Index.Scored> scored) {
        return scored.isEmpty() ? -1 : scored.get(0).ordinal();
    }

    private static Candidate find(List<Candidate> candidates, int ordinal) {
        return candidates.stream().filter(c -> c.chunk().ordinal() == ordinal).findFirst().orElseThrow();
    }

    private static String describe(List<TextSplitter.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (TextSplitter.Segment segment : segments) {
            sb.append("\n  [").append(segment.section()).append("] ").append(segment.text().replace("\n", "\\n"));
        }
        return sb.toString();
    }
}
