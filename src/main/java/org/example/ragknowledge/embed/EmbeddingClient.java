package org.example.ragknowledge.embed;

import org.example.ragknowledge.config.AiProperties;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量化客户端（OpenAI 兼容的 {@code /embeddings} 协议，如智谱 embedding-3）。
 *
 * <p>三个刻意保留的工程细节：</p>
 * <ol>
 *   <li><b>批量 + 降级</b>：一次请求提交多段；整批失败时自动退化为逐条重试，
 *       单条失败只跳过该段（返回 null），不会因为一个坏段落毁掉整篇文档的上传；</li>
 *   <li><b>错误可读</b>：非 2xx 时读取响应体里的 {@code error.message} 抛给前端，
 *       而不是只报一个状态码 —— 线上排错时这一条能省半小时；</li>
 *   <li><b>按 index 回填</b>：严格按接口返回的 {@code index} 字段归位，
 *       不假设返回顺序与请求顺序一致（并发批处理服务不保证顺序）。</li>
 * </ol>
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingClient(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /** 向量化异常：消息会直接展示给用户 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }
    }

    /**
     * 查询向量缓存（LRU，按 模型名 + 文本 做键）。
     *
     * <p>两个场景很吃这个缓存：</p>
     * <ul>
     *   <li>评测跑消融对比时，同一批问题要用 6 套配置各跑一遍，
     *       没有缓存就是 6 倍的向量化开销；</li>
     *   <li>真实使用里用户反复问同一句话、或多次点击提问按钮。</li>
     * </ul>
     *
     * <p>键里带上模型名，换模型后旧向量不会被误用。容量固定，不会无限增长。
     * 只缓存<b>查询</b>（几十个字），不缓存入库文档块——那是一次性的，
     * 而且会挤掉真正需要复用的条目。</p>
     */
    private static final int CACHE_MAX = 512;

    private final Map<String, double[]> queryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    /** 单条向量化 */
    public double[] embedOne(String text) {
        List<double[]> vectors = embedBatch(List.of(text));
        if (vectors.isEmpty() || vectors.get(0) == null) {
            throw new EmbeddingException("向量化失败：接口未返回结果");
        }
        return vectors.get(0);
    }

    /** 带缓存的单条查询向量化：评测与检索路径都走这里 */
    public double[] embedQuery(String text) {
        if (text == null) {
            throw new EmbeddingException("待向量化文本为空");
        }
        String key = aiProperties.getEmbeddingModel() + "\u0000" + text;
        double[] cached = queryCache.get(key);
        if (cached != null) {
            return cached;
        }
        double[] vector = embedOne(text);
        queryCache.put(key, vector);
        return vector;
    }

    /** 清空查询向量缓存（换 Embedding 模型后调用） */
    public void clearQueryCache() {
        queryCache.clear();
    }

    /** 当前缓存条目数，供状态接口展示 */
    public int queryCacheSize() {
        return queryCache.size();
    }

    /**
     * 批量查询向量化（带缓存）：命中缓存的直接取，只把未命中的合并成一次请求。
     *
     * <p>这是检索路径应该用的方法 —— 它同时保住了"一次 HTTP 往返"和"不重复算同一个问题"
     * 这两个优化。</p>
     *
     * @return 与 texts 等长的列表；失败位置为 null（由调用方决定怎么降级）
     */
    public List<double[]> embedQueries(List<String> texts) {
        double[][] result = new double[texts.size()][];
        List<Integer> misses = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        String model = aiProperties.getEmbeddingModel();

        for (int i = 0; i < texts.size(); i++) {
            double[] cached = queryCache.get(model + "\u0000" + texts.get(i));
            if (cached != null) {
                result[i] = cached;
            } else {
                misses.add(i);
                missTexts.add(texts.get(i));
            }
        }

        if (!missTexts.isEmpty()) {
            List<double[]> fetched = embedBatch(missTexts);
            for (int k = 0; k < misses.size() && k < fetched.size(); k++) {
                double[] vector = fetched.get(k);
                result[misses.get(k)] = vector;
                if (vector != null) {
                    queryCache.put(model + "\u0000" + missTexts.get(k), vector);
                }
            }
        }
        return Arrays.asList(result);
    }

    /**
     * 批量向量化整段文档：按 batchSize 分组，组内失败降级为逐条重试。
     *
     * @param texts    待向量化文本
     * @param batchSize 单次请求最大条数
     * @param errors   输出参数，收集失败原因（调用方可据此给出提示）
     * @return 与 texts 等长的列表，失败位置为 null
     */
    public List<double[]> embedAll(List<String> texts, int batchSize, List<String> errors) {
        List<double[]> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
        int size = Math.max(1, batchSize);

        for (int start = 0; start < texts.size(); start += size) {
            int end = Math.min(start + size, texts.size());
            List<String> batch = texts.subList(start, end);

            List<double[]> vectors = null;
            try {
                vectors = embedBatch(batch);
            } catch (EmbeddingException e) {
                if (batch.size() == 1) {
                    log.warn("段落 #{} 向量化失败：{}", start + 1, e.getMessage());
                    errors.add(e.getMessage());
                    continue;
                }
                log.warn("批量向量化失败（{} 条），改为逐条重试：{}", batch.size(), e.getMessage());
            }

            if (vectors != null) {
                for (int i = 0; i < vectors.size() && i < batch.size(); i++) {
                    result.set(start + i, vectors.get(i));
                }
                continue;
            }

            for (int i = 0; i < batch.size(); i++) {
                try {
                    result.set(start + i, embedBatch(List.of(batch.get(i))).get(0));
                } catch (EmbeddingException e) {
                    log.warn("段落 #{} 向量化失败：{}", start + i + 1, e.getMessage());
                    errors.add(e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * 一次 HTTP 调用完成一批向量化；任何一个位置缺失都视为整批失败（由上层决定是否降级）。
     */
    public List<double[]> embedBatch(List<String> texts) {
        String apiKey = aiProperties.getEmbeddingApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingException("尚未配置 Embedding API Key，请在「知识库设置」中填写");
        }
        String apiUrl = aiProperties.getEmbeddingApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new EmbeddingException("尚未配置 Embedding 接口地址");
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", aiProperties.getEmbeddingModel());
            body.put("input", texts);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseText = readBody(is);

            if (code < 200 || code >= 300) {
                throw new EmbeddingException("Embedding 接口返回错误(" + code + ")：" + extractErrorMessage(responseText));
            }

            JsonNode root = objectMapper.readTree(responseText);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                throw new EmbeddingException("Embedding 接口返回内容为空或格式异常：" + abbreviate(responseText));
            }

            double[][] ordered = new double[texts.size()][];
            int position = 0;
            for (JsonNode item : data) {
                JsonNode indexNode = item.path("index");
                int index = indexNode.isNumber() ? indexNode.asInt() : position;
                position++;
                if (index < 0 || index >= ordered.length) {
                    continue;
                }
                JsonNode vec = item.path("embedding");
                if (!vec.isArray() || vec.size() == 0) {
                    continue;
                }
                double[] vector = new double[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    vector[i] = vec.get(i).asDouble();
                }
                ordered[index] = vector;
            }

            for (int i = 0; i < ordered.length; i++) {
                if (ordered[i] == null) {
                    throw new EmbeddingException("Embedding 返回条数与请求不一致（请求 " + texts.size()
                            + " 条，收到 " + data.size() + " 条）");
                }
            }
            return Arrays.asList(ordered);

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("调用 Embedding 接口失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
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

    private String extractErrorMessage(String responseText) {
        try {
            JsonNode root = objectMapper.readTree(responseText);
            JsonNode msg = root.path("error").path("message");
            if (!msg.isMissingNode() && !msg.asString().isBlank()) {
                return msg.asString();
            }
        } catch (Exception ignored) {
            // 解析失败时返回截断后的原始响应
        }
        return abbreviate(responseText);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
