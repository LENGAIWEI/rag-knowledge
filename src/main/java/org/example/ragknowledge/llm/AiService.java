package org.example.ragknowledge.llm;

import org.example.ragknowledge.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型调用：只保留知识库需要的"通用对话"能力。
 *
 * <p>相比拆分前的版本，这里刻意去掉了 {@code translate} / {@code lookup}：那是翻译官的业务，
 * 放在知识库里只会让两个项目继续藕断丝连。</p>
 *
 * <p>三个使用场景共用这一个入口，靠 {@code temperature} 区分：</p>
 * <ul>
 *   <li>Query 改写 / HyDE —— {@code 0.1}，要的是稳定、可复现，不能有创造性；</li>
 *   <li>最终作答 —— {@code 0.3}，比翻译松一点，但仍然要求"只依据片段"。</li>
 * </ul>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiService(AiProperties properties) {
        this.properties = properties;
    }

    /**
     * 通用对话：直接把 prompt 交给大模型。
     *
     * @param prompt      完整提示词
     * @param temperature 采样温度
     * @return 模型输出（含成功标记与错误信息，不抛异常，由调用方决定如何降级）
     */
    public Result chat(String prompt, double temperature) {
        try {
            return Result.ok(callApi(prompt, temperature));
        } catch (Exception e) {
            log.warn("大模型调用失败：{}", e.getMessage());
            return Result.error(e.getMessage() == null ? "未知错误" : e.getMessage());
        }
    }

    /** 默认温度 0.3：用于最终作答 */
    public Result chat(String prompt) {
        return chat(prompt, 0.3);
    }

    private String callApi(String prompt, double temperature) throws Exception {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("尚未配置对话模型 API Key，请点击右上角「设置」填写");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", temperature);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        String jsonBody = objectMapper.writeValueAsString(body);

        HttpURLConnection conn = (HttpURLConnection) new URL(properties.getApiUrl()).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseText = readBody(is);

        if (code < 200 || code >= 300) {
            throw new RuntimeException("AI 接口返回错误(" + code + ")：" + extractErrorMessage(responseText));
        }

        JsonNode root = objectMapper.readTree(responseText);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        String content = contentNode.isMissingNode() ? "" : contentNode.asString();
        if (content.isBlank()) {
            throw new RuntimeException("AI 返回内容为空或解析失败");
        }
        return content.trim();
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
            if (!msg.isMissingNode()) {
                return msg.asString();
            }
        } catch (Exception ignored) {
            // 解析失败时直接返回原始响应
        }
        return responseText;
    }

    /** 对话结果封装 */
    public static class Result {
        private final boolean success;
        private final String content;
        private final String error;

        private Result(boolean success, String content, String error) {
            this.success = success;
            this.content = content;
            this.error = error;
        }

        public static Result ok(String content) {
            return new Result(true, content, null);
        }

        public static Result error(String error) {
            return new Result(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getContent() {
            return content;
        }

        public String getError() {
            return error;
        }
    }
}
