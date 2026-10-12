package org.example.ragknowledge.rerank;

import org.example.ragknowledge.config.KnowledgeProperties;
import org.example.ragknowledge.index.Chunk;
import org.example.ragknowledge.retrieval.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程精排器：调用真正的交叉编码器（bge-reranker / bge-reranker-v2-m3）。
 *
 * <p>本工程本身零新增依赖，精排模型作为<b>独立进程</b>跑（见 {@code tools/bge-reranker-service}），
 * 应用只通过 HTTP 调用它。这样做的三个理由：</p>
 * <ol>
 *   <li><b>进程隔离</b>：模型占几百 MB 到几 GB 内存、要加载 PyTorch/ONNX，崩了不该把 Web 服务带走；</li>
 *   <li><b>可替换</b>：换模型、换量化精度、换推理框架（TEI / Xinference / vLLM / Ollama）都不用改 Java 代码；</li>
 *   <li><b>可降级</b>：服务没起或超时，自动退回本地精排（见 {@link RerankerRouter}），问答链路永不中断。</li>
 * </ol>
 *
 * <p>请求体遵循 Jina / Cohere / SiliconFlow / TEI 通用的 {@code /rerank} 协议：</p>
 * <pre>
 * POST /rerank
 * {"model":"bge-reranker-v2-m3","query":"...","documents":["...","..."],"top_n":20}
 *
 * 200 OK
 * {"results":[{"index":0,"relevance_score":0.93},{"index":2,"relevance_score":0.11}]}
 * </pre>
 *
 * <p>兼容性处理：响应可能是 {@code {"results":[...]}}、{@code {"data":[...]}}，
 * 也可能直接是裸数组 {@code [{"index":0,"score":0.9}]}（TEI 风格）；
 * 分数字段可能是 {@code relevance_score} 也可能是 {@code score}。</p>
 */
@Component
public class HttpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);

    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpReranker(KnowledgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "http";
    }

    /** 是否已配置远程精排服务 */
    public boolean isConfigured() {
        String url = properties.getRerank().getApiUrl();
        return url != null && !url.isBlank();
    }

    @Override
    public boolean rerank(String query, List<Candidate> candidates) {
        if (!isConfigured() || query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        KnowledgeProperties.Rerank config = properties.getRerank();
        List<String> documents = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            documents.add(truncate(candidate.chunk().text()));
        }

        try {
            List<Score> scores = callService(config, query, documents);
            if (scores.isEmpty()) {
                return false;
            }
            boolean alreadyProbability = true;
            for (Score score : scores) {
                if (score.value() < 0 || score.value() > 1) {
                    alreadyProbability = false;
                    break;
                }
            }
            for (Score score : scores) {
                if (score.index() < 0 || score.index() >= candidates.size()) {
                    continue;
                }
                double value = alreadyProbability ? score.value() : sigmoid(score.value());
                Candidate candidate = candidates.get(score.index());
                candidate.setRerankScore(value);
                candidate.setFinalScore(value);
            }
            return true;
        } catch (Exception e) {
            log.warn("远程精排失败，将降级为本地精排：{}", e.getMessage());
            return false;
        }
    }

    private List<Score> callService(KnowledgeProperties.Rerank config, String query, List<String> documents)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());

        HttpURLConnection conn = (HttpURLConnection) new URL(config.getApiUrl()).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        int timeout = Math.max(500, config.getTimeoutMs());
        conn.setConnectTimeout(Math.min(timeout, 5000));
        conn.setReadTimeout(timeout);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseText = readBody(is);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("精排服务返回错误(" + code + ")：" + abbreviate(responseText));
        }
        return parseScores(responseText);
    }

    /** 解析响应，兼容 results / data / 裸数组 三种外壳与两种分数字段名 */
    private List<Score> parseScores(String responseText) {
        List<Score> scores = new ArrayList<>();
        JsonNode root = objectMapper.readTree(responseText);
        JsonNode array = root;
        if (root.isObject()) {
            if (root.path("results").isArray()) {
                array = root.path("results");
            } else if (root.path("data").isArray()) {
                array = root.path("data");
            } else {
                return scores;
            }
        }
        if (!array.isArray()) {
            return scores;
        }
        int fallbackIndex = 0;
        for (JsonNode item : array) {
            JsonNode indexNode = item.path("index");
            int index = indexNode.isNumber() ? indexNode.asInt() : fallbackIndex;
            fallbackIndex++;
            JsonNode scoreNode = item.path("relevance_score");
            if (scoreNode.isMissingNode() || !scoreNode.isNumber()) {
                scoreNode = item.path("score");
            }
            if (!scoreNode.isNumber()) {
                continue;
            }
            scores.add(new Score(index, scoreNode.asDouble()));
        }
        return scores;
    }

    /**
     * bge-reranker 系列在多数推理服务里输出的是<b>未经 sigmoid 的 logit</b>，
     * 直接和 [0,1] 的阈值比较会全部被拒。这里只在"明显不是概率"时才做 sigmoid 变换。
     */
    private static double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    /** 单文档过长时截断：交叉编码器的开销与序列长度呈平方关系 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        int limit = 1200;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String readBody(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    /** 一条精排结果 */
    private record Score(int index, double value) {
    }
}
