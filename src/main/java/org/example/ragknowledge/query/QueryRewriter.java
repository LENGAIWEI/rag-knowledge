package org.example.ragknowledge.query;

import org.example.ragknowledge.llm.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Query 改写 / HyDE：把"用户怎么说"翻译成"知识库里怎么写"。
 *
 * <p>这一步解决的是<b>查询侧</b>的问题。召回和精排都在优化"怎么找"，
 * 但如果问题本身就没问清楚，后面再强也找不回来。三种典型病症：</p>
 *
 * <ol>
 *   <li><b>指代消解</b>——"它需要提前几天？"里的"它"是上文提到的年假，直接拿去检索必然失败。
 *       改写后变成"年假需要提前几天申请"。</li>
 *   <li><b>词汇不匹配（Lexical Gap）</b>——用户说"怎么请假"，文档写"休假申请流程"。
 *       同一个意思两套词，向量能缓解一部分，但生成几个同义改写做<b>多查询扩展</b>更稳：
 *       只要有一条改写和文档用词对上了，那一块就会被召回，再靠 RRF 的"共识投票"浮上来。</li>
 *   <li><b>问题太短或太口语</b>——"年假？"两个字，向量空间里离什么都远。
 *       HyDE（Hypothetical Document Embeddings）先让模型<b>编一段假答案</b>，
 *       再用假答案的向量去检索。假答案可能事实全错，但它的<b>用词和句式</b>与真实文档高度相似，
 *       所以能大幅拉近与目标段落的距离。注意：假答案本身绝不进 Prompt，
 *       它只当"探针"用，最终答案仍然只能来自真实检索到的片段。</li>
 * </ol>
 *
 * <p><b>失败一律降级</b>：改写是可选增强，LLM 超时或返回的 JSON 解析不出来时，
 * 直接使用原问题继续检索，绝不因此让问答失败。</p>
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    /**
     * 改写结果。
     *
     * @param original    用户原问题
     * @param standalone  指代消解后的独立问题；改写未生效时等于 original
     * @param variants    同义改写（多查询扩展）
     * @param keywords    抽取出的关键词（单独走一路 BM25）
     * @param used        改写是否真的生效
     * @param error       未生效原因（用于界面提示，正常时为 null）
     */
    public record RewriteResult(String original,
                                String standalone,
                                List<String> variants,
                                List<String> keywords,
                                boolean used,
                                String error) {

        public static RewriteResult passthrough(String question, String error) {
            return new RewriteResult(question, question, List.of(), List.of(), false, error);
        }
    }

    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryRewriter(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * 改写问题：一次 LLM 调用同时拿到「独立问题 + 同义变体 + 关键词」。
     *
     * <p>刻意合并成一次调用而不是三次：改写处在问答的关键路径上，
     * 每多一次往返就是多一两秒的首字延迟，而这三种产物本来就可以一起生成。</p>
     *
     * @param question    用户问题
     * @param history     最近对话（用于指代消解），可为空
     * @param variantCount 需要几条同义改写
     */
    public RewriteResult rewrite(String question, List<String> history, int variantCount) {
        if (question == null || question.isBlank()) {
            return RewriteResult.passthrough("", "问题为空");
        }
        int variants = Math.max(0, Math.min(variantCount, 5));

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是检索系统的查询分析器。请把用户问题整理成适合知识库检索的形式。\n\n");
        if (history != null && !history.isEmpty()) {
            prompt.append("最近的对话上下文（用于理解「它」「这个」等指代）：\n");
            for (String line : history) {
                prompt.append("- ").append(line).append('\n');
            }
            prompt.append('\n');
        }
        prompt.append("用户问题：").append(question.strip()).append("\n\n");
        prompt.append("要求：\n");
        prompt.append("1. standalone：把问题改写成不依赖上下文、可以独立检索的完整问题");
        prompt.append("（消解代词，补全省略的主语/宾语）；如果问题本身已经完整，原样返回；\n");
        prompt.append("2. variants：给出 ").append(variants).append(" 条语义相同但用词不同的检索式");
        prompt.append("（换用知识库文档里更可能出现的书面说法、同义词、简称与全称）；");
        prompt.append("数量为 0 时返回空数组；\n");
        prompt.append("3. keywords：抽取 3~8 个最有区分度的关键词或短语（专有名词、编号、术语优先）；\n");
        prompt.append("4. 只输出 JSON，不要任何解释、不要 markdown 代码块。\n\n");
        prompt.append("输出格式：\n");
        prompt.append("{\"standalone\":\"...\",\"variants\":[\"...\"],\"keywords\":[\"...\"]}");

        AiService.Result result = aiService.chat(prompt.toString(), 0.1);
        if (!result.isSuccess()) {
            log.warn("Query 改写失败，使用原问题继续检索：{}", result.getError());
            return RewriteResult.passthrough(question, result.getError());
        }

        try {
            JsonNode root = objectMapper.readTree(extractJson(result.getContent()));
            String standalone = text(root, "standalone", question);
            List<String> variantList = stringList(root.path("variants"), variants);
            List<String> keywords = stringList(root.path("keywords"), 8);
            return new RewriteResult(question, standalone, variantList, keywords, true, null);
        } catch (Exception e) {
            log.warn("Query 改写结果解析失败，使用原问题继续检索：{}", e.getMessage());
            return RewriteResult.passthrough(question, "改写结果解析失败");
        }
    }

    /**
     * HyDE：生成一段"假答案"，只用它的向量去检索。
     *
     * @return 假答案文本；失败时返回 null（调用方跳过这一路）
     */
    public String hypotheticalAnswer(String question, int maxChars) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String prompt = "请针对下面的问题，写一段可能出现在专业知识库文档里的答案。\n"
                + "要求：\n"
                + "1. 用文档的语气和书面表达来写（像是从手册或规范里摘出来的段落），不要口语化；\n"
                + "2. 可以使用该领域常见的术语和表述方式，不必保证事实完全准确；\n"
                + "3. 不要写「我不确定」「根据我的了解」这类话，不要重复问题，不要解释你在做什么；\n"
                + "4. 直接输出答案正文，控制在 " + Math.max(80, maxChars) + " 字以内。\n\n"
                + "问题：" + question.strip() + "\n\n"
                + "答案：";

        AiService.Result result = aiService.chat(prompt, 0.1);
        if (!result.isSuccess()) {
            log.warn("HyDE 生成失败，跳过这一路检索：{}", result.getError());
            return null;
        }
        String content = result.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        String cleaned = content.strip();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars);
    }

    // ==================== 工具方法 ====================

    /** LLM 有时仍会包一层 ```json 代码块，这里剥掉后再解析 */
    private String extractJson(String content) {
        String text = content.strip();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).strip();
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String text(JsonNode root, String field, String fallback) {
        JsonNode node = root.path(field);
        if (!node.isString() || node.asString().isBlank()) {
            return fallback;
        }
        return node.asString().strip();
    }

    /** 读取字符串数组并去重、限长；同时兼容 LLM 偶尔返回单个字符串的情况 */
    private List<String> stringList(JsonNode node, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isString() && !item.asString().isBlank()) {
                    seen.add(item.asString().strip());
                }
                if (seen.size() >= limit) {
                    break;
                }
            }
        } else if (node.isString() && !node.asString().isBlank()) {
            seen.add(node.asString().strip());
        }
        return new ArrayList<>(seen);
    }
}
