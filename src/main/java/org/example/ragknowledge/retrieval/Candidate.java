package org.example.ragknowledge.retrieval;

import org.example.ragknowledge.index.Chunk;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索候选：一个知识块在整条检索链路上的全部中间分数。
 *
 * <p>把每一段的分数<b>逐层保留下来</b>（向量分 → BM25 分 → RRF 融合分 → 精排分 → 最终分），
 * 而不是只留一个最终数字。这样界面上能直接看到"哪一路把它召回的、精排把它提上来了还是压下去了"，
 * 线上出现"答非所问"时也能立刻定位是召回问题还是精排问题。</p>
 */
public class Candidate {

    private final Chunk chunk;
    private final Set<String> routes = new LinkedHashSet<>();
    private final Set<String> matchedQueries = new LinkedHashSet<>();

    /** RRF 融合分（多路多查询累加） */
    private double rrfScore;

    /** 向量路最高余弦相似度；没有走过向量路时为 null */
    private Double vectorScore;

    /** BM25 最高分；没有走过 BM25 路时为 null */
    private Double bm25Score;

    /** 精排分；未精排时为 null */
    private Double rerankScore;

    /** 最终用于排序与阈值判断的分数（0~1） */
    private double finalScore;

    public Candidate(Chunk chunk) {
        this.chunk = chunk;
    }

    public Chunk chunk() {
        return chunk;
    }

    public Set<String> routes() {
        return routes;
    }

    public Set<String> matchedQueries() {
        return matchedQueries;
    }

    public double rrfScore() {
        return rrfScore;
    }

    public void addRrfScore(double delta) {
        this.rrfScore += delta;
    }

    public Double vectorScore() {
        return vectorScore;
    }

    /** 只保留更高的那条：同一块可能被多个改写查询召回，取最相似的一次 */
    public void mergeVectorScore(double score) {
        if (vectorScore == null || score > vectorScore) {
            vectorScore = score;
        }
    }

    public Double bm25Score() {
        return bm25Score;
    }

    public void mergeBm25Score(double score) {
        if (bm25Score == null || score > bm25Score) {
            bm25Score = score;
        }
    }

    public Double rerankScore() {
        return rerankScore;
    }

    public void setRerankScore(Double rerankScore) {
        this.rerankScore = rerankScore;
    }

    public double finalScore() {
        return finalScore;
    }

    public void setFinalScore(double finalScore) {
        this.finalScore = finalScore;
    }

    /** 命中了哪几路（vector / bm25），用于前端徽章展示 */
    public void markRoute(String route) {
        routes.add(route);
    }

    public void markQuery(String queryLabel) {
        matchedQueries.add(queryLabel);
    }
}
