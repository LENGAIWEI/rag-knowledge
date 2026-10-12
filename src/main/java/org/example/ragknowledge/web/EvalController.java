package org.example.ragknowledge.web;

import org.example.ragknowledge.eval.EvalCase;
import org.example.ragknowledge.eval.EvalGenerator;
import org.example.ragknowledge.eval.EvalProfile;
import org.example.ragknowledge.eval.EvalReport;
import org.example.ragknowledge.eval.EvalRunner;
import org.example.ragknowledge.eval.EvalSetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测接口。
 *
 * <pre>
 * GET  /kb/eval/set       读取评测集
 * POST /kb/eval/set       整体替换评测集
 * POST /kb/eval/generate  让大模型按文档自动生成评测用例（合并进现有评测集）
 * GET  /kb/eval/profiles  列出内置的消融档位
 * POST /kb/eval/run       跑评测，返回各档位的指标对比
 * </pre>
 *
 * <p>评测跑的是纯检索链路，不调用大模型生成答案，所以可以放心地反复跑。</p>
 */
@RestController
@RequestMapping("/kb/eval")
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    /** 一次评测请求里每条用例的最大检索条数，防止有人传 1000 把内存打满 */
    private static final int MAX_TOP_K = 50;

    private final EvalSetStore setStore;
    private final EvalRunner runner;
    private final EvalGenerator generator;

    public EvalController(EvalSetStore setStore, EvalRunner runner, EvalGenerator generator) {
        this.setStore = setStore;
        this.runner = runner;
        this.generator = generator;
    }

    /** 读取评测集 */
    @GetMapping("/set")
    public Map<String, Object> getSet() {
        EvalSetStore.EvalSet set = setStore.load();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("path", setStore.file().toString());
        result.put("version", set.version());
        result.put("count", set.size());
        result.put("cases", set.safeCases());
        return result;
    }

    /** 整体替换评测集（前端编辑器或脚本用） */
    @PostMapping("/set")
    public Map<String, Object> saveSet(@RequestBody Map<String, Object> body) {
        try {
            List<EvalCase> cases = parseCases(body == null ? null : body.get("cases"));
            setStore.save(new EvalSetStore.EvalSet(1, cases));
            Map<String, Object> result = ok("评测集已保存：" + cases.size() + " 条");
            result.put("count", cases.size());
            return result;
        } catch (Exception e) {
            log.error("评测集保存失败", e);
            return fail("保存失败：" + e.getMessage());
        }
    }

    /** 列出内置消融档位 */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("standard", EvalProfile.standard());
        result.put("quick", EvalProfile.quick());
        return result;
    }

    /**
     * 自动生成评测用例并合并进现有评测集。
     *
     * <p>请求体：{@code {"perDocument": 3, "sources": ["MySQL-知识库"], "replace": false}}</p>
     */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody(required = false) Map<String, Object> body) {
        int perDocument = asInt(body == null ? null : body.get("perDocument"), 3);
        boolean replace = asBool(body == null ? null : body.get("replace"));
        List<String> sources = asStringList(body == null ? null : body.get("sources"));

        try {
            EvalGenerator.Generated generated = generator.generate(sources, perDocument);
            EvalSetStore.EvalSet existing = setStore.load();

            List<EvalCase> merged = new ArrayList<>();
            if (!replace) {
                merged.addAll(existing.safeCases());
            }
            merged.addAll(generated.cases());
            setStore.save(new EvalSetStore.EvalSet(1, merged));

            Map<String, Object> result = ok("生成 " + generated.cases().size() + " 条，评测集现有 "
                    + merged.size() + " 条");
            result.put("generated", generated.cases().size());
            result.put("total", merged.size());
            result.put("perDocument", generated.perDocument());
            result.put("errors", generated.errors());
            return result;
        } catch (Exception e) {
            log.error("评测用例生成失败", e);
            return fail("生成失败：" + e.getMessage());
        }
    }

    /**
     * 跑评测。
     *
     * <p>请求体：{@code {"mode":"standard","topK":10,"profiles":["④ 混合+精排"]}}</p>
     * <p>{@code profiles} 非空时只跑这些档位，否则按 {@code mode}（quick / standard）取全集。</p>
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> body) {
        EvalSetStore.EvalSet set = setStore.load();
        if (set.size() == 0) {
            return fail("评测集为空，请先调用 /kb/eval/generate 生成，或手动写入 data/eval/eval-set.json");
        }

        int topK = Math.max(1, Math.min(MAX_TOP_K, asInt(body == null ? null : body.get("topK"), 10)));
        List<EvalProfile> profiles = selectProfiles(body);
        Double minScore = asDouble(body == null ? null : body.get("minScore"));
        Double minVectorScore = asDouble(body == null ? null : body.get("minVectorScore"));

        long started = System.currentTimeMillis();
        try {
            List<EvalReport> reports = runner.runAll(set, profiles, topK, minScore, minVectorScore);
            Map<String, Object> result = ok("评测完成：" + set.size() + " 条用例 × "
                    + reports.size() + " 个档位");
            result.put("caseCount", set.size());
            result.put("topK", topK);
            result.put("minScore", minScore);
            result.put("minVectorScore", minVectorScore);
            result.put("elapsedMs", System.currentTimeMillis() - started);
            result.put("reports", reports);
            return result;
        } catch (Exception e) {
            log.error("评测执行失败", e);
            return fail("评测失败：" + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /** 选择要跑的档位：显式指定优先，否则按 mode 取内置集合 */
    private List<EvalProfile> selectProfiles(Map<String, Object> body) {
        List<EvalProfile> all = EvalProfile.standard();
        List<String> wanted = asStringList(body == null ? null : body.get("profiles"));
        if (wanted != null && !wanted.isEmpty()) {
            List<EvalProfile> selected = new ArrayList<>();
            for (EvalProfile profile : all) {
                if (wanted.contains(profile.name())) {
                    selected.add(profile);
                }
            }
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        String mode = body == null ? null : asString(body.get("mode"));
        if ("quick".equalsIgnoreCase(mode)) {
            return EvalProfile.quick();
        }
        return all;
    }

    /** 把请求体里的 cases 数组转成评测用例 */
    private List<EvalCase> parseCases(Object raw) {
        List<EvalCase> cases = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return cases;
        }
        int index = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            index++;
            String id = str(map.get("id"));
            cases.add(new EvalCase(
                    (id == null || id.isBlank()) ? String.format("case-%03d", index) : id,
                    str(map.get("question")),
                    str(map.get("expectSource")),
                    str(map.get("expectSection")),
                    str(map.get("expectText")),
                    Boolean.TRUE.equals(map.get("expectNoAnswer")),
                    str(map.get("note"))));
        }
        return cases;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean asBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private List<String> asStringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> raw) {
            for (Object item : raw) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return list;
    }

    private Map<String, Object> ok(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}
