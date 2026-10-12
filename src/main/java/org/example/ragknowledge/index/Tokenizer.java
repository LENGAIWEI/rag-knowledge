package org.example.ragknowledge.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 中英文混合分词器（纯 Java，零依赖）。
 *
 * <p>BM25 的效果几乎完全取决于分词。中文没有空格，常规做法有三种：接一个分词器（jieba/HanLP）、
 * 单字切分、<b>二元组（bigram）切分</b>。这里选 bigram，原因：</p>
 * <ul>
 *   <li>不需要词典和模型，零依赖，启动即用；</li>
 *   <li>召回率高于单字（"请假" 是一个词，单字切分后 "请" 和 "假" 会和无数无关文档撞车）；</li>
 *   <li>精度接近分词器，且不会因为未登录词（新词、人名、产品名）切错而彻底漏召回。</li>
 * </ul>
 *
 * <p>切分规则：</p>
 * <ol>
 *   <li>全角转半角 + 转小写；</li>
 *   <li>连续汉字串：长度 1 时输出该字，长度 ≥ 2 时输出全部相邻二元组；</li>
 *   <li>连续字母/数字串：整体作为一个 token（如 {@code embedding-3} 会拆成 {@code embedding} 和 {@code 3}）；</li>
 *   <li>过滤中英文停用词与无意义的单字母。</li>
 * </ol>
 *
 * <p>例：{@code "请假需要提前几天?"} → {@code [请假, 假需, 需要, 要提, 提前, 前天, 几天]}</p>
 */
public final class Tokenizer {

    /** 英文停用词：这些词在任何文档里都高频出现，保留只会稀释信号 */
    private static final Set<String> EN_STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "of", "to", "in", "on", "at", "for", "and", "or", "not", "no",
            "it", "its", "this", "that", "these", "those", "with", "as", "by",
            "from", "into", "than", "then", "so", "such", "can", "could", "will",
            "would", "should", "may", "might", "must", "do", "does", "did", "done",
            "have", "has", "had", "i", "you", "he", "she", "we", "they", "them",
            "his", "her", "our", "your", "their", "what", "which", "who", "whom",
            "when", "where", "why", "how", "all", "any", "both", "each", "few",
            "more", "most", "other", "some", "only", "own", "same", "too", "very",
            "just", "but", "if", "because", "while", "about", "over", "after", "before"
    );

    /** 中文停用词：只对单字 token 生效（二元组不会命中此表，因此"需要"不会被误杀） */
    private static final Set<String> ZH_STOPWORDS = Set.of(
            "的", "了", "是", "在", "和", "我", "有", "就", "不", "人", "都", "一", "上", "也",
            "很", "到", "说", "去", "你", "会", "着", "看", "好", "这", "那", "他", "她", "它",
            "们", "个", "把", "被", "让", "给", "对", "与", "或", "及", "等", "吗", "呢", "吧",
            "啊", "呀", "过", "得", "地", "而", "并", "但", "如", "若", "则", "其", "之"
    );

    private Tokenizer() {
    }

    /**
     * 分词，保留重复（BM25 需要词频 tf）。
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String normalized = normalize(text);
        int length = normalized.length();
        int i = 0;
        while (i < length) {
            char c = normalized.charAt(i);
            if (isHan(c)) {
                int start = i;
                while (i < length && isHan(normalized.charAt(i))) {
                    i++;
                }
                appendHan(tokens, normalized, start, i);
            } else if (isLatinOrDigit(c)) {
                int start = i;
                while (i < length && isLatinOrDigit(normalized.charAt(i))) {
                    i++;
                }
                String word = normalized.substring(start, i);
                if (isUsefulLatin(word)) {
                    tokens.add(word);
                }
            } else {
                i++;
            }
        }
        return tokens;
    }

    /** 去重分词，用于覆盖率 / 邻近度这类不关心词频的特征 */
    public static Set<String> tokenSet(String text) {
        return new HashSet<>(tokenize(text));
    }

    /** 汉字串 → 单字（长度 1）或二元组（长度 ≥ 2） */
    private static void appendHan(List<String> tokens, String text, int start, int end) {
        int len = end - start;
        if (len == 1) {
            String single = text.substring(start, end);
            if (!ZH_STOPWORDS.contains(single)) {
                tokens.add(single);
            }
            return;
        }
        for (int i = start; i < end - 1; i++) {
            tokens.add(text.substring(i, i + 2));
        }
    }

    private static boolean isUsefulLatin(String word) {
        if (EN_STOPWORDS.contains(word)) {
            return false;
        }
        // 纯字母且长度为 1 的（如 "a"）没有检索价值；单个数字（如 "3"）保留
        return word.length() > 1 || Character.isDigit(word.charAt(0));
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容表意文字
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * 归一化：全角字符转半角 + 转小写。
     * 用户的输入法经常打出全角字母数字（如 {@code ＲＡＧ}），不归一化就会检索不到。
     * 公开供精排阶段计算"最长公共子串"时复用，保证两处用的是同一套归一化规则。
     */
    public static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u3000') {
                c = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }
            // 顺序很重要：全角字母先转成半角大写（Ｒ→R），这里再统一转小写，
            // 否则 'R' 既不是半角小写、又不属于全角区间，会被当成噪声字符整体丢掉。
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
