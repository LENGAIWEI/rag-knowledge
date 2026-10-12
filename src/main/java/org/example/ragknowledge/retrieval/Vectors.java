package org.example.ragknowledge.retrieval;

/**
 * 向量工具：余弦相似度。
 */
public final class Vectors {

    private Vectors() {
    }

    /**
     * 余弦相似度。
     *
     * <p>维度不一致或出现零向量时返回 0 而不是抛异常 —— 知识库里可能残留着用旧 Embedding 模型
     * 算出来的向量，检索时应该跳过它，而不是让整个问答挂掉。</p>
     */
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
