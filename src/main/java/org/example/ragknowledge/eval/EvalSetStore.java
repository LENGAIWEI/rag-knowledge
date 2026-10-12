package org.example.ragknowledge.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测集：一批 (问题 → 期望命中) 用例，落盘成 JSON 便于人工编辑和版本控制。
 *
 * <p>放在 {@code data/eval/eval-set.json} 并<b>纳入版本控制</b>——
 * 它是测试资产，不是运行时状态。数据集改了、指标掉了，是应该在 code review 里被看见的。</p>
 */
@Component
public class EvalSetStore {

    private static final Logger log = LoggerFactory.getLogger(EvalSetStore.class);

    /** 持久化结构；version 用于将来格式升级时做兼容判断 */
    public record EvalSet(int version, List<EvalCase> cases) {

        public static EvalSet empty() {
            return new EvalSet(1, List.of());
        }

        public int size() {
            return cases == null ? 0 : cases.size();
        }

        public List<EvalCase> safeCases() {
            return cases == null ? List.of() : cases;
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path file = Path.of("data/eval/eval-set.json").toAbsolutePath().normalize();

    public Path file() {
        return file;
    }

    /** 载入评测集；文件不存在或损坏时返回空集，而不是让应用起不来 */
    public EvalSet load() {
        if (!Files.isRegularFile(file)) {
            log.info("评测集不存在，返回空集：{}", file);
            return EvalSet.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            EvalSet set = objectMapper.readValue(json, EvalSet.class);
            if (set == null || set.cases() == null) {
                return EvalSet.empty();
            }
            // 过滤掉没配任何期望条件的用例：它们永远是"未命中"，只会把指标拉低却查不出原因
            List<EvalCase> valid = new ArrayList<>();
            int skipped = 0;
            for (EvalCase c : set.cases()) {
                if (c == null || c.question() == null || c.question().isBlank()) {
                    skipped++;
                    continue;
                }
                if (!c.hasExpectation()) {
                    log.warn("评测用例 {} 没有配任何期望条件，已跳过", c.id());
                    skipped++;
                    continue;
                }
                valid.add(c);
            }
            if (skipped > 0) {
                log.warn("评测集载入完成，跳过 {} 条无效用例", skipped);
            }
            return new EvalSet(set.version() == 0 ? 1 : set.version(), valid);
        } catch (Exception e) {
            log.warn("评测集解析失败，按空集处理：{}（原因：{}）", file, e.getMessage());
            return EvalSet.empty();
        }
    }

    /** 保存评测集（原子写：先写临时文件再 move，避免写一半把文件写坏） */
    public synchronized void save(EvalSet set) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            EvalSet normalized = new EvalSet(set.version() == 0 ? 1 : set.version(), set.safeCases());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("评测集保存失败：" + e.getMessage(), e);
        }
    }
}
