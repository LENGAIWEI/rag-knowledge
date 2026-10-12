package org.example.ragknowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 知识库问答服务入口。
 *
 * <p>检索链路：Query 改写 / HyDE → 向量 + BM25 双路召回 → RRF 融合 → 精排 Rerank → 拼 Prompt 作答。</p>
 */
@SpringBootApplication
public class RagKnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagKnowledgeApplication.class, args);
    }
}
