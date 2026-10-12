package org.example.ragknowledge.web;

import org.example.ragknowledge.config.AiProperties;
import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.index.KnowledgeStore;
import org.example.ragknowledge.rerank.RerankerRouter;
import org.example.ragknowledge.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口。
 *
 * <pre>
 * GET  /kb/status            知识库状态（段落数、文档清单、Embedding 是否已配置）
 * GET  /kb/config            读取全部配置（Key 打码）
 * POST /kb/config            修改配置
 * POST /kb/upload            粘贴文本入库      {"content":"...","source":"可选文档名"}
 * POST /kb/upload-file       上传文本文件入库（multipart：file、source）
 * POST /kb/ask               基于知识库提问    {"question":"...","topK":5,"minScore":0.05,
 *                                              "minVectorScore":0.25,"useRewrite":true,"useHyde":false,
 *                                              "history":["Q:...","A:..."]}
 * POST /kb/document/delete   按文档名删除      {"source":"文档名"}
 * POST /kb/clear             清空知识库
 * </pre>
 */
@RestController
@RequestMapping("/kb")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    /** 单次上传的纯文本上限，避免把内存打满 */
    private static final int MAX_CHARS = 200_000;

    private final KnowledgeService knowledgeService;
    private final AiProperties aiProperties;
    private final KnowledgeProperties kbProperties;
    private final RerankerRouter rerankerRouter;

    public KnowledgeController(KnowledgeService knowledgeService,
                               AiProperties aiProperties,
                               KnowledgeProperties kbProperties,
                               RerankerRouter rerankerRouter) {
        this.knowledgeService = knowledgeService;
        this.aiProperties = aiProperties;
        this.kbProperties = kbProperties;
        this.rerankerRouter = rerankerRouter;
    }

    // ==================== 写入 ====================

    /** 粘贴文本入库 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        String source = body == null ? null : body.get("source");

        if (content == null || content.isBlank()) {
            return fail("文档内容不能为空！");
        }
        if (content.length() > MAX_CHARS) {
            return fail("文档过长（" + content.length() + " 字），请拆分后分次上传，单次上限 " + MAX_CHARS + " 字");
        }
        try {
            int added = knowledgeService.addDocument(content, source);
            String name = (source == null || source.isBlank()) ? "手动粘贴" : source.strip();
            Map<String, Object> result = ok("文档「" + name + "」入库成功：新增 " + added + " 个段落");
            result.put("added", added);
            result.put("chunkCount", knowledgeService.size());
            return result;
        } catch (KnowledgeService.KnowledgeException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("文档入库失败", e);
            return fail("文档入库失败：" + e.getMessage());
        }
    }

    /** 上传 .txt / .md 等文本文件入库 */
    @PostMapping(value = "/upload-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "source", required = false) String source) {
        if (file == null || file.isEmpty()) {
            return fail("请选择要上传的文件！");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "未命名文件";
        }
        String lower = originalName.toLowerCase();
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".log"))) {
            return fail("只支持纯文本文件（.txt/.md/.csv/.json/.log），当前文件：" + originalName);
        }

        try {
            String text = decodeText(file.getBytes());
            if (text.length() > MAX_CHARS) {
                return fail("文件内容过长（" + text.length() + " 字），请拆分后分次上传，单次上限 " + MAX_CHARS + " 字");
            }
            String name = (source == null || source.isBlank()) ? originalName : source.strip();
            int added = knowledgeService.addDocument(text, name);
            Map<String, Object> result = ok("文件「" + name + "」入库成功：新增 " + added + " 个段落");
            result.put("added", added);
            result.put("chunkCount", knowledgeService.size());
            return result;
        } catch (KnowledgeService.KnowledgeException e) {
            return fail(e.getMessage());
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            return fail("读取上传文件失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("文件入库失败", e);
            return fail("文件入库失败：" + e.getMessage());
        }
    }

    /** 按文档名删除 */
    @PostMapping("/document/delete")
    public Map<String, Object> deleteDocument(@RequestBody Map<String, String> body) {
        String source = body == null ? null : body.get("source");
        if (source == null || source.isBlank()) {
            return fail("请指定要删除的文档名");
        }
        int removed = knowledgeService.deleteSource(source.strip());
        if (removed == 0) {
            return fail("未找到文档「" + source + "」");
        }
        return ok("已删除文档「" + source + "」的 " + removed + " 个段落");
    }

    /** 清空知识库（同时删除落盘文件） */
    @PostMapping("/clear")
    public Map<String, Object> clear() {
        int before = knowledgeService.size();
        knowledgeService.clear();
        return ok("知识库已清空（移除 " + before + " 个段落）");
    }

    // ==================== 问答 / 状态 ====================

    /** 基于知识库提问，返回答案 + 三层检索的中间结果 */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, Object> body) {
        String question = body == null ? null : asString(body.get("question"));
        if (question == null || question.isBlank()) {
            return fail("问题不能为空！");
        }

        List<String> history = asStringList(body.get("history"));
        Integer topK = asInteger(body.get("topK"));
        Double minScore = asDouble(body.get("minScore"));
        Double minVectorScore = asDouble(body.get("minVectorScore"));
        Boolean useRewrite = asBoolean(body.get("useRewrite"));
        Boolean useHyde = asBoolean(body.get("useHyde"));

        try {
            KnowledgeService.Answer answer = knowledgeService.ask(
                    question, history, topK, minScore, minVectorScore, useRewrite, useHyde);

            Map<String, Object> result = new LinkedHashMap<>();
            boolean generationFailed = answer.error() != null;
            result.put("success", !generationFailed);
            result.put("found", answer.found());
            result.put("message", generationFailed
                    ? answer.error()
                    : (answer.found() ? "回答成功" : "未在知识库中找到足够相关的内容，可降低相似度阈值或补充文档"));
            result.put("answer", answer.answer() == null ? "" : answer.answer());
            result.put("hits", toHitList(answer.hits()));
            result.put("query", toQueryMap(answer.query()));
            result.put("retrieval", toRetrievalMap(answer.retrieval()));
            return result;
        } catch (KnowledgeService.KnowledgeException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("知识库问答失败", e);
            return fail("问答失败：" + e.getMessage());
        }
    }

    /** 知识库状态 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chunkCount", knowledgeService.size());
        result.put("documents", toDocList(knowledgeService.documents()));
        result.put("embeddingConfigured", isConfigured(aiProperties.getEmbeddingApiKey()));
        result.put("embeddingModel", aiProperties.getEmbeddingModel());
        result.put("storePath", knowledgeService.storePath());
        result.put("retrievalMode", kbProperties.getHybrid().isEnabled() ? "hybrid" : "vector");
        result.put("rerankMode", rerankerRouter.configuredMode());
        return result;
    }

    // ==================== 配置 ====================

    /** 读取配置（密钥打码返回） */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        KnowledgeProperties.Hybrid hybrid = kbProperties.getHybrid();
        KnowledgeProperties.Rerank rerank = kbProperties.getRerank();
        KnowledgeProperties.Rewrite rewrite = kbProperties.getRewrite();
        KnowledgeProperties.Hyde hyde = kbProperties.getHyde();

        Map<String, Object> m = new LinkedHashMap<>();
        // 对话模型（用于改写与作答）
        m.put("chatModel", aiProperties.getModel());
        m.put("chatApiUrl", aiProperties.getApiUrl());
        // 向量化
        m.put("embeddingApiUrl", aiProperties.getEmbeddingApiUrl());
        m.put("embeddingModel", aiProperties.getEmbeddingModel());
        m.put("embeddingApiKey", mask(aiProperties.getEmbeddingApiKey()));
        m.put("hasEmbeddingKey", isConfigured(aiProperties.getEmbeddingApiKey()));
        // 切段
        m.put("chunkSize", kbProperties.getChunkSize());
        m.put("chunkOverlap", kbProperties.getChunkOverlap());
        m.put("batchSize", kbProperties.getBatchSize());
        m.put("storePath", kbProperties.getStorePath());
        // 第 1 层：混合检索
        m.put("hybridEnabled", hybrid.isEnabled());
        m.put("recallTopK", hybrid.getRecallTopK());
        m.put("minVectorScore", hybrid.getMinVectorScore());
        m.put("rrfK", hybrid.getRrfK());
        m.put("vectorWeight", hybrid.getVectorWeight());
        m.put("bm25Weight", hybrid.getBm25Weight());
        // 第 2 层：精排
        m.put("topK", kbProperties.getTopK());
        m.put("rerankCandidates", rerank.getCandidates());
        m.put("rerankMode", rerank.getMode());
        m.put("rerankActiveMode", rerankerRouter.configuredMode());
        m.put("rerankMinScore", rerank.getMinScore());
        m.put("rerankApiUrl", rerank.getApiUrl());
        m.put("rerankModel", rerank.getModel());
        m.put("rerankTimeoutMs", rerank.getTimeoutMs());
        m.put("hasRerankKey", isConfigured(rerank.getApiKey()));
        // 第 3 层：改写 / HyDE
        m.put("rewriteEnabled", rewrite.isEnabled());
        m.put("rewriteVariants", rewrite.getVariants());
        m.put("hydeEnabled", hyde.isEnabled());
        return m;
    }

    /** 更新配置；字段缺省表示不修改，数值会被裁剪到合法区间 */
    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return getConfig();
        }
        KnowledgeProperties.Hybrid hybrid = kbProperties.getHybrid();
        KnowledgeProperties.Rerank rerank = kbProperties.getRerank();
        KnowledgeProperties.Rewrite rewrite = kbProperties.getRewrite();
        KnowledgeProperties.Hyde hyde = kbProperties.getHyde();

        // ---- 向量化 ----
        String embeddingApiKey = asString(body.get("embeddingApiKey"));
        String embeddingApiUrl = asString(body.get("embeddingApiUrl"));
        String embeddingModel = asString(body.get("embeddingModel"));
        if (embeddingApiKey != null && !embeddingApiKey.isBlank() && !embeddingApiKey.contains("••••")) {
            aiProperties.setEmbeddingApiKey(embeddingApiKey.trim());
        }
        if (embeddingApiUrl != null && !embeddingApiUrl.isBlank()) {
            aiProperties.setEmbeddingApiUrl(embeddingApiUrl.trim());
        }
        if (embeddingModel != null && !embeddingModel.isBlank()) {
            aiProperties.setEmbeddingModel(embeddingModel.trim());
        }

        // ---- 切段 ----
        Integer chunkSize = asInteger(body.get("chunkSize"));
        Integer chunkOverlap = asInteger(body.get("chunkOverlap"));
        Integer batchSize = asInteger(body.get("batchSize"));
        if (chunkSize != null) {
            kbProperties.setChunkSize(clamp(chunkSize, 50, 2000));
        }
        if (chunkOverlap != null) {
            kbProperties.setChunkOverlap(clamp(chunkOverlap, 0, Math.max(0, kbProperties.getChunkSize() / 2)));
        }
        if (batchSize != null) {
            kbProperties.setBatchSize(clamp(batchSize, 1, 64));
        }

        // ---- 第 1 层：混合检索 ----
        Boolean hybridEnabled = asBoolean(body.get("hybridEnabled"));
        Integer recallTopK = asInteger(body.get("recallTopK"));
        Double minVectorScore = asDouble(body.get("minVectorScore"));
        Integer rrfK = asInteger(body.get("rrfK"));
        Double vectorWeight = asDouble(body.get("vectorWeight"));
        Double bm25Weight = asDouble(body.get("bm25Weight"));
        if (hybridEnabled != null) {
            hybrid.setEnabled(hybridEnabled);
        }
        if (recallTopK != null) {
            hybrid.setRecallTopK(clamp(recallTopK, 1, 200));
        }
        if (minVectorScore != null) {
            hybrid.setMinVectorScore(clamp01(minVectorScore));
        }
        if (rrfK != null) {
            hybrid.setRrfK(clamp(rrfK, 1, 200));
        }
        if (vectorWeight != null) {
            hybrid.setVectorWeight(Math.max(0, Math.min(10, vectorWeight)));
        }
        if (bm25Weight != null) {
            hybrid.setBm25Weight(Math.max(0, Math.min(10, bm25Weight)));
        }

        // ---- 第 2 层：精排 ----
        Integer topK = asInteger(body.get("topK"));
        Integer rerankCandidates = asInteger(body.get("rerankCandidates"));
        String rerankMode = asString(body.get("rerankMode"));
        Double rerankMinScore = asDouble(body.get("rerankMinScore"));
        String rerankApiUrl = asString(body.get("rerankApiUrl"));
        String rerankModel = asString(body.get("rerankModel"));
        String rerankApiKey = asString(body.get("rerankApiKey"));
        Integer rerankTimeoutMs = asInteger(body.get("rerankTimeoutMs"));
        if (topK != null) {
            kbProperties.setTopK(clamp(topK, 1, 50));
        }
        if (rerankCandidates != null) {
            rerank.setCandidates(clamp(rerankCandidates, 1, 200));
        }
        if (rerankMode != null && !rerankMode.isBlank()) {
            String normalized = rerankMode.trim().toLowerCase();
            if (normalized.equals("off") || normalized.equals("local")
                    || normalized.equals("http") || normalized.equals("auto")) {
                rerank.setMode(normalized);
            }
        }
        if (rerankMinScore != null) {
            rerank.setMinScore(clamp01(rerankMinScore));
        }
        if (rerankApiUrl != null) {
            // 允许清空：清空即关闭远程精排，回到本地精排
            rerank.setApiUrl(rerankApiUrl.trim());
        }
        if (rerankModel != null && !rerankModel.isBlank()) {
            rerank.setModel(rerankModel.trim());
        }
        if (rerankApiKey != null && !rerankApiKey.isBlank() && !rerankApiKey.contains("••••")) {
            rerank.setApiKey(rerankApiKey.trim());
        }
        if (rerankTimeoutMs != null) {
            rerank.setTimeoutMs(clamp(rerankTimeoutMs, 500, 120_000));
        }

        // ---- 第 3 层：改写 / HyDE ----
        Boolean rewriteEnabled = asBoolean(body.get("rewriteEnabled"));
        Integer rewriteVariants = asInteger(body.get("rewriteVariants"));
        Boolean hydeEnabled = asBoolean(body.get("hydeEnabled"));
        if (rewriteEnabled != null) {
            rewrite.setEnabled(rewriteEnabled);
        }
        if (rewriteVariants != null) {
            rewrite.setVariants(clamp(rewriteVariants, 0, 5));
        }
        if (hydeEnabled != null) {
            hyde.setEnabled(hydeEnabled);
        }

        return getConfig();
    }

    // ==================== 工具方法 ====================

    private Map<String, Object> toQueryMap(KnowledgeService.AskQuery query) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("original", query.original());
        m.put("standalone", query.standalone());
        m.put("variants", query.variants());
        m.put("keywords", query.keywords());
        m.put("rewriteUsed", query.rewriteUsed());
        m.put("rewriteError", query.rewriteError());
        m.put("hydeUsed", query.hydeUsed());
        m.put("hydeChars", query.hydeChars());
        return m;
    }

    private Map<String, Object> toRetrievalMap(KnowledgeService.RetrievalInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", info.mode());
        m.put("recalled", info.recalled());
        m.put("reranked", info.reranked());
        m.put("returned", info.returned());
        m.put("rerankMode", info.rerankMode());
        m.put("rerankDegraded", info.rerankDegraded());
        m.put("rerankDetail", info.rerankDetail());
        m.put("vectorDegraded", info.vectorDegraded());
        m.put("vectorDegradedReason", info.vectorDegradedReason());
        m.put("timingsMs", info.timingsMs());
        return m;
    }

    private List<Map<String, Object>> toHitList(List<KnowledgeService.Hit> hits) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeService.Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", h.text());
            m.put("source", h.source());
            m.put("section", h.section());
            m.put("ordinal", h.ordinal());
            m.put("score", h.score());
            m.put("vectorScore", h.vectorScore());
            m.put("bm25Score", h.bm25Score());
            m.put("rrfScore", h.rrfScore());
            m.put("rerankScore", h.rerankScore());
            m.put("routes", h.routes());
            m.put("matchedQueries", h.matchedQueries());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> toDocList(List<KnowledgeStore.DocStat> docs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeStore.DocStat d : docs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", d.source());
            m.put("count", d.count());
            list.add(m);
        }
        return list;
    }

    /**
     * 解码上传的文本：优先 UTF-8，失败（Windows 记事本常见的 GBK 文件）时回退 GBK，
     * 并去掉 UTF-8 BOM。
     */
    private String decodeText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("GBK"));
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private boolean isConfigured(String key) {
        return key != null && !key.isBlank();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Boolean.parseBoolean(text);
    }

    private List<String> asStringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> raw) {
            for (Object item : raw) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return list;
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 10) {
            return "••••••";
        }
        return key.substring(0, 6) + "••••••" + key.substring(key.length() - 4);
    }

    private Map<String, Object> ok(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        result.put("answer", "");
        result.put("hits", List.of());
        return result;
    }
}
