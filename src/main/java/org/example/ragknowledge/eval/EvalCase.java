package org.example.ragknowledge.eval;

import org.example.ragknowledge.service.KnowledgeService;

/**
 * 一条评测用例。
 *
 * <p><b>怎么标记"正确答案"是评测集设计里最关键的决定。</b>三种做法各有代价：</p>
 * <ul>
 *   <li><b>按 ordinal（块序号）</b> —— 最省事，但重新入库后序号全变，评测集直接失效；</li>
 *   <li><b>按正文指纹</b>（片段里应出现的原文）—— 对重新切块最鲁棒，但写起来啰嗦，
 *       而且换个 embedding 模型后块边界变了也可能对不上；</li>
 *   <li><b>按「来源文档 + 章节路径」</b>（本工程采用）—— 人类可读、改起来直观、
 *       重新入库和重新切块都不影响，因为章节路径是从标题推导出来的稳定属性。</li>
 * </ul>
 *
 * <p>三个匹配字段都是<b>子串匹配</b>，且都可以留空：留空的字段不参与判定。
 * 只留 {@code expectText} 时退化成"正文指纹"模式。</p>
 *
 * @param id            用例标识，用于在报告里定位
 * @param question      用户会怎么问（要尽量口语化，不要照抄文档用词，否则评测虚高）
 * @param expectSource  期望命中的文档名（子串匹配），可空
 * @param expectSection 期望命中的章节路径（子串匹配），可空
 * @param expectText    期望片段正文里出现的内容（子串匹配），可空
 * @param expectNoAnswer true 表示知识库里本来就没有答案，系统应当拒答
 * @param note          备注，比如这条用例想验证什么
 */
public record EvalCase(String id,
                       String question,
                       String expectSource,
                       String expectSection,
                       String expectText,
                       boolean expectNoAnswer,
                       String note) {

    /** 这条用例是否可回答（用于区分召回率和拒答率两个指标） */
    public boolean answerable() {
        return !expectNoAnswer;
    }

    /** 某个命中片段是否满足本用例的期望 */
    public boolean matches(KnowledgeService.Hit hit) {
        if (expectNoAnswer || hit == null) {
            return false;
        }
        if (expectSource != null && !expectSource.isBlank()
                && !contains(hit.source(), expectSource)) {
            return false;
        }
        if (expectSection != null && !expectSection.isBlank()
                && !contains(hit.section(), expectSection)) {
            return false;
        }
        if (expectText != null && !expectText.isBlank()
                && !contains(hit.text(), expectText)) {
            return false;
        }
        return true;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    /** 至少配了一个匹配条件，否则这条用例永远是"未命中"，等于白写 */
    public boolean hasExpectation() {
        return expectNoAnswer
                || (expectSource != null && !expectSource.isBlank())
                || (expectSection != null && !expectSection.isBlank())
                || (expectText != null && !expectText.isBlank());
    }
}
