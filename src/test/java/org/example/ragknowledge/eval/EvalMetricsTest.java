package org.example.ragknowledge.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测指标的算法测试。
 *
 * <p>指标算错的危害比"没有指标"更大 —— 它会把错误的结论包装成"数据支持的结论"。
 * 所以这里的每个用例都手算一遍期望值，而不是跑一遍看结果"差不多"。</p>
 */
class EvalMetricsTest {

    /** 造一条可回答用例的结果；rank=0 表示未命中 */
    private EvalReport.CaseResult hit(String id, int rank) {
        return new EvalReport.CaseResult(id, "q-" + id, true, rank, rank == 0, rank > 0, List.of(), null);
    }

    /** 造一条应拒答用例的结果 */
    private EvalReport.CaseResult refusal(String id, boolean refused) {
        return new EvalReport.CaseResult(id, "q-" + id, false, 0, refused, refused, List.of(), null);
    }

    @Test
    @DisplayName("Hit@K 与 MRR 按手算结果一致")
    void metricsMatchHandCalculation() {
        // 5 条可回答用例，期望命中排名分别为 1、3、7、0、2
        List<EvalReport.CaseResult> details = List.of(
                hit("a", 1),
                hit("b", 3),
                hit("c", 7),
                hit("d", 0),
                hit("e", 2));

        List<Long> latencies = List.of(10L, 20L, 30L, 40L, 50L);
        EvalReport report = EvalReport.summarize("测试档", "desc", details, latencies, 10);

        assertEquals(5, report.answerableCount());
        assertEquals(0, report.refusalCount());

        // Hit@1: 只有 a 是第 1 名 → 1/5
        assertEquals(0.2, report.hit1(), 1e-9);
        // Hit@3: a(1)、e(2)、b(3) → 3/5
        assertEquals(0.6, report.hit3(), 1e-9);
        // Hit@5: 加上没有 → 仍然 3/5（c 是第 7）
        assertEquals(0.6, report.hit5(), 1e-9);
        // Hit@10: 加上 c(7) → 4/5
        assertEquals(0.8, report.hit10(), 1e-9);

        // MRR = (1/1 + 1/3 + 1/7 + 0 + 1/2) / 5
        double expectedMrr = (1.0 + 1.0 / 3 + 1.0 / 7 + 0 + 1.0 / 2) / 5;
        assertEquals(expectedMrr, report.mrr(), 1e-9);

        // 延迟：平均 30，P95 取排序后 ceil(0.95*5)-1 = 第 5 个 = 50
        assertEquals(30.0, report.avgLatencyMs(), 1e-9);
        assertEquals(50.0, report.p95LatencyMs(), 1e-9);
    }

    @Test
    @DisplayName("未命中的用例 MRR 贡献为 0，但仍然是分母")
    void missesStillCountInDenominator() {
        // 两条全未命中 → MRR 必须是 0 而不是"没有数据"
        EvalReport report = EvalReport.summarize("档", "",
                List.of(hit("a", 0), hit("b", 0)), List.of(1L, 1L), 10);
        assertEquals(0.0, report.mrr(), 1e-9);
        assertEquals(0.0, report.hit1(), 1e-9);
    }

    @Test
    @DisplayName("拒答准确率只统计应拒答的用例，且两者互不干扰")
    void refusalAccuracyIsIndependent() {
        List<EvalReport.CaseResult> details = List.of(
                hit("a", 1),              // 可回答，命中
                hit("b", 0),              // 可回答，未命中
                refusal("c", true),       // 应拒答，正确拒答
                refusal("d", true),       // 应拒答，正确拒答
                refusal("e", false));     // 应拒答，但没拒答 → 会放任模型编

        EvalReport report = EvalReport.summarize("档", "", details, List.of(1L), 10);

        assertEquals(2, report.answerableCount());
        assertEquals(3, report.refusalCount());
        // 可回答的命中率只由 a、b 决定
        assertEquals(0.5, report.hit1(), 1e-9);
        // 拒答率只由 c、d、e 决定
        assertEquals(2.0 / 3, report.refusalAccuracy(), 1e-9);
    }

    @Test
    @DisplayName("全是应拒答用例时不除零")
    void noAnswerableCasesDoesNotDivideByZero() {
        EvalReport report = EvalReport.summarize("档", "",
                List.of(refusal("a", true), refusal("b", false)), List.of(5L, 5L), 10);
        assertEquals(0, report.answerableCount());
        assertEquals(0.0, report.hit5(), 1e-9, "没有可回答用例时命中率应为 0，不能是 NaN");
        assertEquals(0.5, report.refusalAccuracy(), 1e-9);
    }

    @Test
    @DisplayName("空集合不抛异常")
    void emptySetIsSafe() {
        EvalReport report = EvalReport.summarize("档", "", List.of(), List.of(), 10);
        assertEquals(0, report.answerableCount());
        assertEquals(0.0, report.mrr(), 1e-9);
        assertEquals(0.0, report.avgLatencyMs(), 1e-9);
        assertTrue(Double.isFinite(report.hit10()), "空集合下的指标必须是有限数，不能是 NaN");
    }

    @Test
    @DisplayName("综合通过率把两类用例一起算")
    void passRateCoversBothKinds() {
        List<EvalReport.CaseResult> details = List.of(
                hit("a", 1),            // 通过
                hit("b", 0),            // 不通过
                refusal("c", true),     // 通过
                refusal("d", false));   // 不通过
        EvalReport report = EvalReport.summarize("档", "", details, new ArrayList<>(), 10);
        assertEquals(0.5, report.passRate(), 1e-9);
    }

    @Test
    @DisplayName("逐条结果的 passed 与 firstRank/refused 一致")
    void caseResultConsistency() {
        assertTrue(hit("a", 3).passed(), "命中的可回答用例应算通过");
        assertTrue(!hit("a", 0).passed(), "未命中的可回答用例不应算通过");
        assertTrue(refusal("r", true).passed(), "正确拒答应算通过");
        assertTrue(!refusal("r", false).passed(), "该拒答却没拒答不应算通过");
    }
}
