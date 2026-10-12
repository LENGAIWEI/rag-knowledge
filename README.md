# RAG 知识库问答 v2（rag-knowledge）

文档入库 → 切段 → 向量化 → **混合检索 → RRF 融合 → 精排 Rerank** → 大模型依据片段作答。

独立 Spring Boot 应用，**零第三方依赖**：BM25、RRF、本地精排全部手写纯 Java。

---

## 一、相对 v1 检索层的提升

v1 只有"向量召回 + 拼 Prompt"，v2 往上加了标准的三层：

| 层 | 做了什么 | 解决什么问题 |
| --- | --- | --- |
| **① Query 改写 / HyDE** | LLM 把问题改写成独立检索式 + 生成同义变体 + 抽关键词；可选再生成一段假设答案去检索 | 指代（"它"）、词汇不匹配（"怎么请假" vs "休假申请流程"）、问题太短/太口语 |
| **② 混合检索** | 向量路 + BM25 路双路召回，用 RRF 按**排名**融合 | 向量对专有名词/编号/型号很弱（`ISO-9001` 和 `ISO-9002` 在向量空间几乎一样近），BM25 正好补上；反过来 BM25 对同义转述无能为力，向量补上 |
| **③ 精排 Rerank** | 对融合后的 20 条候选做交叉特征重排，取 topK | 召回只能保证"答案在里面"，精排负责把"答案排到最前面"，直接决定大模型看到什么 |

另外两项配套改动：

- **标题感知切段**：Markdown 按 `#` 层级切成章节，每块带章节路径元数据，并参与 BM25 检索；
- **全链路可降级**：见第五节。

---

## 一点五、评测：这套优化到底有没有用

[`docs/评测与调参.md`](docs/评测与调参.md) 记录了用 40 条评测用例做的完整消融实验。
**结论和最初的实现假设相反**，挑四条最重要的：

| 发现 | 数据 |
| --- | --- |
| BM25 的召回深度必须压浅 | 10 条时 Hit@5=81%，压到 2 条后 **95%**（+14pt，一行改动） |
| 手写的词面精排是**净负收益** | 词面权重越大越差：84% → 95% → 97%（纯余弦） |
| 真正有用的是 **Query 改写** | Hit@1 +11pt、Hit@3 +17pt、MRR +0.101，代价 +1.4s |
| **HyDE 会让拒答能力归零** | 拒答率 33% → **0%**，这是它默认关闭的实测依据 |

两个根因：

- RRF 的 `k=60` 让各名次分差只有 13%，BM25 尾部的噪声票足以翻转 top-10 的顺序 ——
  所以问题不在 BM25 权重，而在**投票的条数**；
- 这份语料只有 124 个块、`embedding-3` 又足够强，向量本身已经把答案排到前面了，
  词面信号只是在往一个**已经正确的排序**里注入噪声。典型失败：问"哪个组件比 OpenFeign 更快"，
  词面匹配会把 OpenFeign 那一节顶上来，而正确答案是 Dubbo。

评测**不调用大模型生成答案**（只评检索），所以可以放心反复跑：

```bash
curl -X POST http://localhost:8081/kb/eval/run \
  -H "Content-Type: application/json" \
  -d '{"mode":"quick","topK":10}'
```

---

## 二、目录结构

```
rag-knowledge/
├── pom.xml
├── mvnw / mvnw.cmd                 Maven Wrapper，无需预装 Maven
├── test.http                       接口调试脚本（IDEA / VS Code REST Client）
├── README.md
├── docs/检索三层详解.md             三层检索的原理、公式、取舍（面试向）
├── docs/评测与调参.md               消融实验：哪些优化真的有用（含完整数据和结论）
├── data/eval/eval-set.json         评测集（40 条：LLM 自动生成 + 手工对抗用例）
├── tools/bge-reranker-service/     可选的 bge-reranker 本地推理服务（Python）
├── data/knowledge-base.json        知识库落盘文件（兼容 v1 格式，可直接沿用；不入版本库）
└── src/main/
    ├── java/org/example/ragknowledge/
    │   ├── RagKnowledgeApplication.java
    │   ├── config/
    │   │   ├── AiProperties.java              对话模型 + Embedding 配置
    │   │   └── KnowledgeProperties.java       切段 / 混合检索 / 精排 / 改写 配置
    │   ├── llm/AiService.java                 大模型调用（改写、作答共用）
    │   ├── embed/EmbeddingClient.java         向量化（批量 + 失败降级逐条重试）
    │   ├── index/
    │   │   ├── Chunk.java                     知识块：正文 + 来源 + 章节 + 向量
    │   │   ├── Tokenizer.java                 中英混合分词（汉字 bigram）
    │   │   ├── Bm25Index.java                  BM25 倒排索引
    │   │   ├── TextSplitter.java               标题感知切段
    │   │   └── KnowledgeStore.java             内存 + 落盘 + BM25 索引缓存
    │   ├── retrieval/
    │   │   ├── Candidate.java                  候选：保留每一层的分数
    │   │   ├── HybridRetriever.java            双路召回 + RRF 融合
    │   │   └── Vectors.java                    余弦相似度
    │   ├── rerank/
    │   │   ├── Reranker.java                   精排 SPI
    │   │   ├── LexicalReranker.java            本地纯 Java 精排
    │   │   ├── HttpReranker.java               bge-reranker 远程精排
    │   │   └── RerankerRouter.java             模式选择 + 自动降级
    │   ├── query/QueryRewriter.java            改写 / 多查询扩展 / HyDE
    │   ├── service/KnowledgeService.java       两条链路的编排
    │   └── web/KnowledgeController.java        接口：/kb/**
    └── resources/
        ├── application.properties
        └── static/index.html                  页面（访问 / 即可）
```

---

## 三、本地运行

```bash
# Windows
mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

访问 <http://localhost:8081/>（默认 8081，避开翻译官的 8080）。

打包：

```bash
./mvnw clean package     # target/rag-knowledge-0.0.1-SNAPSHOT.jar
```

跑算法测试（11 个用例，覆盖分词 / 切段 / BM25 / RRF / 本地精排 / 降级，毫秒级，不需要网络和密钥）：

```bash
./mvnw test
```

这些测试值钱的地方在于：分词切错、IDF 算错、融合顺序不对，表现出来的都是
"答案质量变差"这种软症状，肉眼看不出来。**顺带一提，全角字母归一化那个 bug
就是这套测试跑出来的**——`ＲＡＧ` 转成半角大写 `R` 后没再转小写，
后续的字符判定只认 `a-z`，于是整个词被当成噪声丢掉。读代码看不出来，跑测试一眼就看到。

---

## 四、接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/kb/status` | 段落数、文档清单、向量化配置、当前检索/精排模式 |
| GET | `/kb/config` | 读取全部配置（密钥打码） |
| POST | `/kb/config` | 修改配置 |
| POST | `/kb/upload` | 粘贴文本入库 |
| POST | `/kb/upload-file` | 上传 `.txt/.md/.csv/.json/.log` 文件入库 |
| POST | `/kb/ask` | 提问，返回答案 + 检索轨迹 + 命中片段（含各层分数） |
| POST | `/kb/document/delete` | 按文档名删除 |
| POST | `/kb/clear` | 清空知识库 |

提问请求体：

```json
{
  "question": "年假有几天？",
  "history": ["Q: 公司年假怎么规定的？", "A: 工龄满一年享五天。"],
  "topK": 5,
  "minScore": 0.15,
  "minVectorScore": 0.25,
  "useRewrite": true,
  "useHyde": false
}
```

`history` 用于**指代消解**：有了它，"它需要提前几天申请？"里的"它"才能被正确改写成"年假"。
其余字段都是可选覆盖项，不传就用服务端默认值。

更多示例见 `test.http`。

---

## 五、降级策略（每一层都有兜底）

RAG 链路上串了三个外部依赖（对话模型、Embedding、精排模型），任何一个抖动都不该让问答崩掉：

| 失效的东西 | 表现 | 系统行为 |
| --- | --- | --- |
| 对话模型 | 改写失败 | 跳过改写，用原问题继续检索（`query.rewriteUsed=false`） |
| 对话模型 | 作答失败 | **返回命中的片段** + 错误信息（`found=true, success=false`）——让调用方能判断"找对了但答不出来" |
| Embedding 服务 | 向量化失败 | 降级为**纯 BM25 关键词检索**（`retrieval.mode="bm25"`） |
| 精排服务 | 超时 / 未启动 | 降级为**本地精排**（`retrieval.rerankMode="local", rerankDegraded=true`） |

页面会把每一次降级用黄色提示条明确显示出来——降级是设计的一部分，
但**必须让人看见**，否则会变成"悄悄变差"的隐形故障。

---

## 六、配置

完整项见 `src/main/resources/application.properties`，页面「设置」里都能改。核心项：

```properties
server.port=8081

# 对话模型（改写 + 作答）
# 密钥不写在这里：支持环境变量或本地文件，见下方「密钥怎么配」
ai.api-key=${AI_API_KEY:}
ai.api-url=https://api.deepseek.com/v1/chat/completions
ai.model=deepseek-chat

# 向量化（与对话模型是两套独立接口和 Key）
ai.embedding-api-key=${AI_EMBEDDING_API_KEY:}
ai.embedding-api-url=https://open.bigmodel.cn/api/paas/v4/embeddings
ai.embedding-model=embedding-3

# 切段
kb.chunk-size=300
kb.chunk-overlap=60
kb.batch-size=8
kb.store-path=data/knowledge-base.json

# 第 1 层：混合检索
kb.hybrid.enabled=true
kb.recall.top-k=10                 # 每一路各自召回多少条
kb.recall.min-vector-score=0.25    # 向量路门槛
kb.rrf.k=60
kb.rrf.vector-weight=1.0
kb.rrf.bm25-weight=1.0

# 第 2 层：精排
kb.top-k=5                         # 最终送入大模型的片段数
kb.rerank.candidates=20            # 送入精排的候选数
kb.rerank.mode=auto                # off | local | http | auto
kb.rerank.min-score=0.15
kb.rerank.api-url=                 # 留空则用本地精排
kb.rerank.model=bge-reranker-v2-m3
kb.rerank.timeout-ms=8000

# 第 3 层：改写 / HyDE
kb.rewrite.enabled=true
kb.rewrite.variants=2
kb.hyde.enabled=false              # 默认关：会多一次大模型调用
kb.hyde.max-chars=400
```

要点：

- **密钥怎么配**（`application.properties` 会提交到仓库，所以里面绝不能有真实 Key）。
  三种方式任选其一，优先级从高到低：

  | 方式 | 怎么做 | 适用 |
  | --- | --- | --- |
  | 环境变量 | `AI_API_KEY=sk-xxx`、`AI_EMBEDDING_API_KEY=xxx` | 部署到服务器 |
  | 本地文件 | 复制 `application-local.properties.example` 为 `application-local.properties` 并填入 Key，该文件已被 `.gitignore` | 本地开发 |
  | 页面设置 | 右上角「设置」里直接填 | 临时试一下（重启失效） |

  Spring Boot 的松绑定会把 `AI_API_KEY` 自动映射到 `ai.api-key`，不需要额外代码。

- `data/` 下的知识库落盘文件**不纳入版本控制**：它是运行时状态，每次上传都会整体重写，
  而且可以由源文档重新入库得到。想让 clone 下来就能演示，把源 Markdown 放进 `data/source/` 即可；
- `kb.store-path` 是**相对应用启动目录**的路径，务必在 jar 所在目录启动，否则读不到 `data/`；
- **更换 Embedding 模型后向量维度会变**，旧向量会被自动跳过并在日志里告警，需要清空知识库后重新入库；
- `data/knowledge-base.json` 兼容 v1 格式（v1 没有 `section` 字段，载入后按空章节处理），
  可以直接沿用老的落盘文件，不需要重灌。

---

## 七、想接真正的 bge-reranker 模型

```bash
cd tools/bge-reranker-service
start.bat          # 或 ./start.sh
```

首次会下载模型（`bge-reranker-v2-m3` 约 2.2GB，国内请先设 `HF_ENDPOINT=https://hf-mirror.com`）。
启动后把页面的「精排服务地址」填成 `http://127.0.0.1:8090/rerank` 即可。

详见 [`tools/bge-reranker-service/README.md`](tools/bge-reranker-service/README.md)。
