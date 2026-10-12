package org.example.ragknowledge.eval;

import org.example.ragknowledge.index.Chunk;
import org.example.ragknowledge.index.KnowledgeStore;
import org.example.ragknowledge.llm.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测集生成器：让大模型读文档、造问题、标注答案所在章节。
 *
 * <p><b>为什么要生成而不是手写？</b>手写 30 条评测用例要一两个小时，而且写完就不想再更新 ——
 * 文档一改，评测集就烂在那里没人维护。生成的用例质量略低，但 5 分钟能出一套，
 * 改文档后随时重新生成，这才是能长期活下去的做法。</p>
 *
 * <p><b>生成质量的关键是提示词里那两条约束</b>，缺一不可：</p>
 * <ol>
 *   <li>"用用户的口吻问，不要照抄原文词句" —— 否则问题里全是文档原词，
 *       任何检索方案都能命中，指标虚高到 100%，评测就失去意义了。
 *       这一条直接决定评测集能不能暴露真实问题。</li>
 *   <li>"section 必须原样照抄标题文本" —— 生成器写错章节名的话，
 *       标注就是错的，指标会莫名其妙地低，而且极难排查。所以生成后必须做校验。</li>
 * </ol>
 *
 * <p>校验不过的用例直接丢弃，宁可少几条也不要脏数据。</p>
 */
@Component
public class EvalGenerator {

    private static final Logger log = LoggerFactory.getLogger(EvalGenerator.class);

    /** 单份文档送进提示词的最大字数：太长的文档截断，避免 prompt 过长 */
    private static final int MAX_DOC_CHARS = 12_000;

    /** 每份文档最多生成几条，防止模型一口气吐几十条把评测集灌满 */
    private static final int MAX_PER_DOC = 8;

    private final KnowledgeStore store;
    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvalGenerator(KnowledgeStore store, AiService aiService) {
        this.store = store;
        this.aiService = aiService;
    }

    /** 生成结果：用例 + 每份文档的产出统计，便于发现问题（比如某份文档一条都没生成出来） */
    public record Generated(List<EvalCase> cases, Map<String, Integer> perDocument, List<String> errors) {
    }

    /**
     * 为知识库里的文档生成评测用例。
     *
     * @param sources    要处理的文档名；为空表示全部文档
     * @param perDocument 每份文档生成几条
     */
    public Generated generate(List<String> sources, int perDocument) {
        int limit = Math.max(1, Math.min(perDocument, MAX_PER_DOC));
        Map<String, List<Chunk>> grouped = groupBySource();

        List<EvalCase> cases = new ArrayList<>();
        Map<String, Integer> perDoc = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, List<Chunk>> entry : grouped.entrySet()) {
            String source = entry.getKey();
            if (sources != null && !sources.isEmpty() && !sources.contains(source)) {
                continue;
            }
            try {
                List<EvalCase> generated = generateForDocument(source, entry.getValue(), limit, cases.size());
                cases.addAll(generated);
                perDoc.put(source, generated.size());
            } catch (Exception e) {
                log.warn("文档「{}」评测用例生成失败：{}", source, e.getMessage());
                errors.add(source + "：" + e.getMessage());
                perDoc.put(source, 0);
            }
        }
        return new Generated(cases, perDoc, errors);
    }

    private List<EvalCase> generateForDocument(String source, List<Chunk> chunks, int limit, int startIndex) {
        // 按章节聚合正文，既能让模型看清结构，也能顺带得到"合法章节名"的清单用于校验
        Map<String, StringBuilder> bySection = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            String key = chunk.sectionOrEmpty().isBlank() ? "(无章节)" : chunk.section();
            bySection.computeIfAbsent(key, k -> new StringBuilder()).append(chunk.text()).append('\n');
        }
        if (bySection.isEmpty()) {
            return List.of();
        }

        StringBuilder doc = new StringBuilder();
        for (Map.Entry<String, StringBuilder> entry : bySection.entrySet()) {
            doc.append("【章节：").append(entry.getKey()).append("】\n")
               .append(entry.getValue()).append('\n');
            if (doc.length() > MAX_DOC_CHARS) {
                doc.append("……（文档过长，已截断）\n");
                break;
            }
        }

        String sectionList = String.join("\n", bySection.keySet());

        String prompt = "下面是一份知识库文档，正文按【章节：xxx】分段。请针对它生成 "
                + limit + " 个用户可能会问的问题，并标注每个问题的答案在哪个章节。\n\n"
                + "要求（非常重要，请严格遵守）：\n"
                + "1. 用真实用户的口吻提问。**不要照抄文档里的词句**，要换一种说法 ——\n"
                + "   比如文档写「IOC 控制反转」，用户可以问「对象是谁创建的」；\n"
                + "2. 每个问题必须能由它标注的那个章节的内容直接回答；\n"
                + "3. 尽量覆盖不同的章节，不要都集中在同一节；\n"
                + "4. section 字段必须**原样照抄**下面列出的章节名之一，一个字都不要改；\n"
                + "5. note 用一句话说明这个问题在考察什么；\n"
                + "6. 只输出 JSON，不要解释、不要 markdown 代码块。\n\n"
                + "合法章节名列表：\n" + sectionList + "\n\n"
                + "文档正文：\n" + doc + "\n"
                + "输出格式：\n"
                + "{\"cases\":[{\"question\":\"...\",\"section\":\"...\",\"note\":\"...\"}]}";

        AiService.Result result = aiService.chat(prompt, 0.4);
        if (!result.isSuccess()) {
            throw new IllegalStateException("大模型调用失败：" + result.getError());
        }

        Set<String> validSections = new LinkedHashSet<>(bySection.keySet());
        return parse(source, result.getContent(), validSections, limit, startIndex);
    }

    private List<EvalCase> parse(String source, String content, Set<String> validSections,
                                 int limit, int startIndex) {
        List<EvalCase> cases = new ArrayList<>();
        JsonNode root = objectMapper.readTree(extractJson(content));
        JsonNode array = root.path("cases");
        if (!array.isArray()) {
            throw new IllegalStateException("返回内容里没有 cases 数组");
        }

        int index = startIndex;
        int dropped = 0;
        for (JsonNode item : array) {
            if (cases.size() >= limit) {
                break;
            }
            String question = text(item, "question");
            String section = text(item, "section");
            if (question == null || section == null) {
                dropped++;
                continue;
            }
            // 校验章节名：模型改写章节名是常见错误，不校验的话标注就是错的
            if (!isValidSection(section, validSections)) {
                log.debug("丢弃用例（章节名不匹配）：question={} section={}", question, section);
                dropped++;
                continue;
            }
            index++;
            cases.add(new EvalCase(
                    String.format("auto-%03d", index),
                    question,
                    source,
                    section,
                    null,
                    false,
                    text(item, "note")));
        }
        if (dropped > 0) {
            log.info("文档「{}」生成时丢弃 {} 条（章节名对不上或字段缺失）", source, dropped);
        }
        return cases;
    }

    /** 章节名是否匹配：允许模型少写/多写层级，只要有一段的原文能对上就算合法 */
    private boolean isValidSection(String section, Set<String> validSections) {
        String normalized = section.strip();
        for (String valid : validSections) {
            if (valid.equals(normalized)
                    || valid.contains(normalized)
                    || normalized.contains(valid)) {
                return true;
            }
        }
        return false;
    }

    /** 按来源文档聚合，跳过没有向量的块 */
    private Map<String, List<Chunk>> groupBySource() {
        Map<String, List<Chunk>> grouped = new LinkedHashMap<>();
        for (Chunk chunk : store.all()) {
            if (chunk.vector() == null || chunk.vector().length == 0) {
                continue;
            }
            grouped.computeIfAbsent(chunk.source(), k -> new ArrayList<>()).add(chunk);
        }
        return grouped;
    }

    private String extractJson(String content) {
        String text = content.strip();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            return null;
        }
        return value.asString().strip();
    }
}
