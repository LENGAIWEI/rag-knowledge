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

    /** 单条向量化 */
    public double[] embedOne(String text) {
        List<double[]> vectors = embedBatch(List.of(text));
        if (vectors.isEmpty() || vectors.get(0) == null) {
            throw new EmbeddingException("向量化失败：接口未返回结果");
        }
        return vectors.get(0);
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
