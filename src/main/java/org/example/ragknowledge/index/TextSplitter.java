package org.example.ragknowledge.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 切段器：按 Markdown 标题层级切章节，再在章节内部按字数切块。
 *
 * <p>相比"整篇按字数硬切"，标题感知切分解决三个问题：</p>
 * <ol>
 *   <li><b>跨块语义断裂</b>：一个条例被切成两半后，任何一半都不足以回答问题；按标题切能让同一节的
 *       内容尽量待在一起，只在超出 chunkSize 时才继续切；</li>
 *   <li><b>没有出处</b>：命中片段只知道来自哪个文件，不知道来自哪一节。现在每个块都带
 *       {@code section}（如「员工手册 &gt; 请假制度 &gt; 年假」），既能在界面上展示，
 *       也会参与 BM25 检索（查询章节名能召回该节全部段落）；</li>
 *   <li><b>标题被当成正文</b>：标题本身是有信息量的短行，会作为正文首行保留在块里。</li>
 * </ol>
 *
 * <p>不是 Markdown 的文档（纯 txt / 粘贴的会议纪要）会退化为"全文章节为空"的老切法，行为不变。</p>
 */
public final class TextSplitter {

    /** Markdown ATX 标题：# ~ ###### */
    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** 中文章节标题：第X章 / 第X节 / 第X篇 / 第X部分，可带标题文字 */
    private static final Pattern CN_HEADING =
            Pattern.compile("^\\s*(第[一二三四五六七八九十百零〇\\d]+\\s*[章篇节部])(?:\\s*[、.．:：]?\\s*(\\S.*)?)?$");

    /** 代码围栏：``` 或 ~~~ 开头 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(?:```|~~~)");

    private TextSplitter() {
    }

    /**
     * 切段结果：块正文 + 所属章节路径。
     *
     * @param text    块正文
     * @param section 章节路径，无标题体系时为 null
     */
    public record Segment(String text, String section) {
    }

    /**
     * 切段主入口。
     *
     * @param text      原文
     * @param chunkSize 单块目标字数
     * @param overlap   相邻块的重叠字数
     */
    public static List<Segment> split(String text, int chunkSize, int overlap) {
        List<Segment> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        int size = Math.max(50, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        // 标题栈：保存 (层级, 标题文本)，用于拼出 "一级 > 二级 > 三级"
        Deque<int[]> levels = new ArrayDeque<>();
        Deque<String> titles = new ArrayDeque<>();

        StringBuilder body = new StringBuilder();
        String currentSection = null;
        boolean sawHeading = false;
        boolean inFence = false;

        for (String line : lines) {
            // 代码块必须整体跳过标题判定，否则 YAML 的 "# 注释"、shell 的 "# 说明"
            // 会被当成 Markdown 标题。尤其是一行 "# xxx" 属于一级标题，会把整个标题栈清空，
            // 导致后面所有章节路径全错 —— 这是用真实笔记跑出来的问题。
            if (isFence(line)) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            Heading heading = inFence ? null : parseHeading(line);
            if (heading != null) {
                sawHeading = true;
                // 章节切换前，先把上一节的内容切块落盘
                flush(body, currentSection, size, ov, result);

                while (!levels.isEmpty() && levels.peek()[0] >= heading.level()) {
                    levels.pop();
                    titles.pop();
                }
                levels.push(new int[]{heading.level()});
                titles.push(heading.title());
                currentSection = String.join(" > ", reverse(titles));
            }
            body.append(line).append('\n');
        }
        flush(body, currentSection, size, ov, result);

        // 整篇没有标题：section 统一置空，前端不展示章节
        if (!sawHeading) {
            List<Segment> plain = new ArrayList<>(result.size());
            for (Segment s : result) {
                plain.add(new Segment(s.text(), null));
            }
            return plain;
        }
        return result;
    }

    /** 把一节的内容切块并写入结果 */
    private static void flush(StringBuilder body, String section, int size, int overlap, List<Segment> out) {
        String content = body.toString();
        body.setLength(0);
        if (content.isBlank() || !hasRealContent(content)) {
            // 整节只有标题行（例如 "# 员工手册" 紧跟着 "## 请假制度"），不产生空块
            return;
        }
        String normalizedSection = (section == null || section.isBlank()) ? null : section;
        for (String piece : chunkSection(content, size, overlap)) {
            out.add(new Segment(piece, normalizedSection));
        }
    }

    /** 判断一节里是否存在标题行之外的正文（代码块内的内容算正文） */
    private static boolean hasRealContent(String content) {
        boolean inFence = false;
        for (String line : content.split("\n")) {
            if (isFence(line)) {
                inFence = !inFence;
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            if (inFence) {
                return true;
            }
            if (parseHeading(line) == null) {
                return true;
            }
        }
        return false;
    }

    /** 是否为代码围栏行（``` 或 ~~~） */
    private static boolean isFence(String line) {
        return line != null && FENCE.matcher(line).find();
    }

    /**
     * 章节内切块：
     * 1. 按换行拆成逻辑行、去掉空行；
     * 2. 超长行按中英文句读切分，仍超长则硬切；
     * 3. 聚合到 chunkSize 附近，相邻块保留 overlap 字重叠。
     */
    static List<String> chunkSection(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        List<String> units = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= size) {
                units.add(trimmed);
            } else {
                units.addAll(splitLongLine(trimmed, size));
            }
        }

        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() + 1 > size) {
                String finished = current.toString().strip();
                if (!finished.isEmpty()) {
                    result.add(finished);
                }
                String tail = tailOf(finished, overlap);
                current.setLength(0);
                if (!tail.isEmpty()) {
                    current.append(tail).append('\n');
                }
            }
            current.append(unit).append('\n');
        }
        String last = current.toString().strip();
        if (!last.isEmpty()) {
            result.add(last);
        }
        return result;
    }

    /** 超长行：优先在句末标点处断开，其次在逗号处，最后按长度硬切 */
    private static List<String> splitLongLine(String line, int size) {
        List<String> pieces = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            sb.append(ch);
            boolean sentenceEnd = "。！？；!?;".indexOf(ch) >= 0;
            boolean softEnd = "，,、".indexOf(ch) >= 0;
            if (sb.length() >= size || (sentenceEnd && sb.length() >= size / 3)
                    || (softEnd && sb.length() >= size / 2)) {
                String piece = sb.toString().strip();
                if (!piece.isEmpty()) {
                    pieces.add(piece);
                }
                sb.setLength(0);
            }
        }
        String rest = sb.toString().strip();
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }

    private static String tailOf(String text, int n) {
        if (text == null || n <= 0 || text.isEmpty()) {
            return "";
        }
        return text.length() <= n ? text : text.substring(text.length() - n);
    }

    /** 识别标题行；返回 null 表示这是正文 */
    private static Heading parseHeading(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher md = MD_HEADING.matcher(line);
        if (md.matches()) {
            return new Heading(md.group(1).length(), md.group(2).strip());
        }
        // 中文式标题只在短行上生效，避免把「第三节讲的是……」这种正文误判成标题
        if (line.strip().length() > 40) {
            return null;
        }
        Matcher cn = CN_HEADING.matcher(line);
        if (cn.matches()) {
            String label = cn.group(1).replaceAll("\\s+", "");
            String rest = cn.group(2) == null ? "" : cn.group(2).strip();
            // 只有「第X节」算第二级，其余按第一级
            int level = label.endsWith("节") ? 2 : 1;
            return new Heading(level, rest.isEmpty() ? label : label + " " + rest);
        }
        return null;
    }

    private static List<String> reverse(Deque<String> stack) {
        List<String> list = new ArrayList<>(stack);
        java.util.Collections.reverse(list);
        return list;
    }

    private record Heading(int level, String title) {
    }
}
