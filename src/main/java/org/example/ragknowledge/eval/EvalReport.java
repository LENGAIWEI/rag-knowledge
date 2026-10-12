package org.example.ragknowledge.eval;

import java.util.List;

/**
 * 一次评测（一个配置档位跑完整评测集）的结果与指标。
 *
 * <p>指标的选择刻意保持克制，只留四个能真正指导决策的：</p>
 * <ul>
 *   <li><b>Hit@K（命中率）</b>：期望片段有没有出现在前 K 条里。这是 RAG 最重要的指标 ——
 *       只要没进 topK，后面的大模型再强也答不出来，属于"硬失败"。</li>
 *   <li><b>MRR@K</b>：第一个正确片段的排名倒数均值。衡量"排得够不够前"。
 *       两个配置可能 Hit@5 一样，但 MRR 差一倍，说明一个把答案排在第 1、另一个排在第 5。</li>
 *   <li><b>拒答准确率</b>：知识库里本来没有答案的用例，系统有没有正确拒答。
 *       这是最容易被忽略、但线上最要命的指标 —— 不拒答就等于放任大模型编。</li>
 *   <li><b>P95 延迟</b>：只看均值会被少数快请求掩盖，P95 才是用户真正感受到的慢。</li>
 * </ul>
 *
 * @param profile          档位名
 * @param description      档位说明
 * @param answerableCount  可回答用例数
 * @param refusalCount     应拒答用例数
 * @param hit1             前 1 条命中率
 * @param hit3             前 3 条命中率
 * @param hit5             前 5 条命中率
 * @param hit10            前 10 条命中率
 * @param mrr              前 10 条内的 MRR
 * @param refusalAccuracy  拒答准确率
 * @param avgLatencyMs     平均检索耗时
 * @param p95LatencyMs     P95 检索耗时
 * @param details          逐条用例结果
 */
public record EvalReport(String profile,
                         String description,
                         int answerableCount,
                         int refusalCount,
                         double hit1,
                         double hit3,
                         double hit5,
                         double hit10,
                         double mrr,
                         double refusalAccuracy,
                         double avgLatencyMs,
                         double p95LatencyMs,
                         List<CaseResult> details) {

    /**
     * 单条用例的执行结果。
     *
     * @param firstRank  第一个满足期望的片段排名（1 起）；0 表示没找到
     * @param refused    系统是否判定为"知识库中没有相关内容"
     * @param passed     本条用例是否通过（可回答用例看 firstRank>0；拒答用例看 refused）
     * @param topSources 前几条命中的来源与章节，未通过时用来定位问题
     */
    public record CaseResult(String id,
                             String question,
                             boolean answerable,
                             int firstRank,
                             boolean refused,
                             boolean passed,
                             List<String> topSources,
                             String error) {
    }

    /**
     * 由逐条结果汇总出指标。
     *
     * @param topK 本次实际请求的条数，用于计算百分位
     */
    public static EvalReport summarize(String profile,
                                       String description,
                                       List<CaseResult> details,
                                       List<Long> latencies,
                                       int topK) {
        int answerable = 0;
        int refusals = 0;
        int hit1 = 0;
        int hit3 = 0;
        int hit5 = 0;
        int hit10 = 0;
        int hitAtTopK = 0;
        double reciprocalSum = 0;
        int refusalCorrect = 0;

        for (CaseResult d : details) {
            if (d.answerable()) {
                answerable++;
                if (d.firstRank() > 0) {
                    if (d.firstRank() <= 1) hit1++;
                    if (d.firstRank() <= 3) hit3++;
                    if (d.firstRank() <= 5) hit5++;
                    if (d.firstRank() <= 10) hit10++;
                    if (d.firstRank() <= topK) hitAtTopK++;
                    reciprocalSum += 1.0 / d.firstRank();
                }
            } else {
                refusals++;
                if (d.refused()) {
                    refusalCorrect++;
                }
            }
        }

        return new EvalReport(
                profile,
                description,
                answerable,
                refusals,
                ratio(hit1, answerable),
                ratio(hit3, answerable),
                ratio(hit5, answerable),
                ratio(hit10, answerable),
                answerable == 0 ? 0 : reciprocalSum / answerable,
                ratio(refusalCorrect, refusals),
                average(latencies),
                percentile(latencies, 0.95),
                details);
    }

    /** 本次实际请求条数下的命中率，报告里单独标注，避免和 Hit@K 混淆 */
    public double hitAtRequestedTopK(List<CaseResult> details, int topK) {
        int answerable = 0;
        int hit = 0;
        for (CaseResult d : details) {
            if (d.answerable()) {
                answerable++;
                if (d.firstRank() > 0 && d.firstRank() <= topK) {
                    hit++;
                }
            }
        }
        return ratio(hit, answerable);
    }

    /** 综合通过率：可回答用例命中 + 应拒答用例正确拒答 */
    public double passRate() {
        if (details.isEmpty()) {
            return 0;
        }
        long passed = details.stream().filter(CaseResult::passed).count();
        return (double) passed / details.size();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.size();
    }

    private static double percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }
}
