package org.example.ragknowledge.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 消融实验的一个配置档位。
 *
 * <p>评测的价值不在于"算出当前配置的分数"，而在于<b>能回答"这一层到底有没有用"</b>。
 * 所以数据集只写一份，配置做成可切换的档位，同一批用例跑多遍，横向对比。</p>
 *
 * @param name        档位名，出现在报告表头
 * @param description 一句话说明这个档位关掉了什么
 * @param hybrid      是否开启混合检索（false = 退化成纯向量，即 v1 的行为）
 * @param rewrite     是否开启 Query 改写
 * @param hyde        是否开启 HyDE
 * @param rerankMode  精排模式：off / local / http
 */
public record EvalProfile(String name,
                          String description,
                          boolean hybrid,
                          boolean rewrite,
                          boolean hyde,
                          String rerankMode) {

    /**
     * 标准消融档位：从 v1 的原始行为逐层叠加，每一档只比上一档多开一个能力。
     *
     * <p>这样跑出来的表格能直接读成"加了 X 之后，Recall@5 涨了多少，延迟涨了多少"——
     * 而不是一堆无法归因的孤立数字。</p>
     */
    public static List<EvalProfile> standard() {
        List<EvalProfile> profiles = new ArrayList<>();
        profiles.add(new EvalProfile("① 纯向量",
                "v1 的原始行为：只走向量召回，不精排、不改写", false, false, false, "off"));
        profiles.add(new EvalProfile("② +精排",
                "在第①档基础上加本地精排", false, false, false, "local"));
        profiles.add(new EvalProfile("③ +混合检索",
                "在第①档基础上加 BM25 双路召回与 RRF 融合",
                true, false, false, "off"));
        profiles.add(new EvalProfile("④ 混合+精排",
                "当前默认配置：混合检索 + 本地精排", true, false, false, "local"));
        profiles.add(new EvalProfile("⑤ +Query改写",
                "在第④档基础上打开 Query 改写（指代消解 + 多查询扩展）",
                true, true, false, "local"));
        profiles.add(new EvalProfile("⑥ +HyDE",
                "在第⑤档基础上再打开 HyDE", true, true, true, "local"));
        return profiles;
    }

    /** 只跑最关心的两档：v1 基线和当前默认，用于快速回归 */
    public static List<EvalProfile> quick() {
        List<EvalProfile> profiles = new ArrayList<>();
        profiles.add(new EvalProfile("① 纯向量", "v1 原始行为", false, false, false, "off"));
        profiles.add(new EvalProfile("④ 混合+精排", "当前默认配置", true, false, false, "local"));
        return profiles;
    }
}
