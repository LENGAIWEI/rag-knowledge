package org.example.ragknowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 接口配置。
 *
 * <p>对话模型与向量化模型是<b>两套独立的接口和 Key</b>，必须分开配置：
 * 对话用 DeepSeek，向量化用智谱 embedding-3。运行时可通过页面「设置」动态修改，
 * 重启后恢复为 application.properties 中的默认值。</p>
 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 对话模型 Key */
    private String apiKey = "";

    /** 对话模型接口地址（OpenAI 兼容 /chat/completions） */
    private String apiUrl = "https://api.deepseek.com/v1/chat/completions";

    /** 对话模型名 */
    private String model = "deepseek-chat";

    /** 向量化接口 Key */
    private String embeddingApiKey = "";

    /** 向量化接口地址 */
    private String embeddingApiUrl = "https://open.bigmodel.cn/api/paas/v4/embeddings";

    /** 向量化模型名（更换模型会导致向量维度变化，需清空知识库重新入库） */
    private String embeddingModel = "embedding-3";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public String getEmbeddingApiUrl() {
        return embeddingApiUrl;
    }

    public void setEmbeddingApiUrl(String embeddingApiUrl) {
        this.embeddingApiUrl = embeddingApiUrl;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }
}
