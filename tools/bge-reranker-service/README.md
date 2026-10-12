# bge-reranker 本地精排服务

给 `rag-knowledge` 提供**真正的交叉编码器**精排能力，本地跑、免费、可离线。

> 这是**可选组件**。不启动它，Java 应用会自动使用纯 Java 的本地精排
> （`kb.rerank.mode=local`），问答完全可用，只是精排精度略低。

---

## 1. 为什么要单独跑一个服务

`rag-knowledge` 主工程**零第三方依赖**——BM25、RRF、本地精排全是手写纯 Java。
但真正的 bge-reranker 是一个 Transformer 交叉编码器，必须要有推理框架才能跑。
把 PyTorch 塞进 Java 进程有三种做法，都不划算：

| 做法 | 问题 |
| --- | --- |
| Java 里加载 ONNX Runtime + 模型 | 要引入 DJL/onnxruntime 依赖，模型权重几百 MB 到几 GB 打进 jar，启动慢、内存高 |
| 用 Java 重写推理 | 不现实 |
| **独立进程 + HTTP**（本方案） | 进程隔离、可换模型、可放到 GPU 机器、崩了不影响 Web 服务 |

代价是多一跳 HTTP（本机约 1~3ms），换来的是**可降级、可替换、可观测**。

---

## 2. 快速开始

```bash
# Windows
start.bat

# Linux / macOS
chmod +x start.sh && ./start.sh
```

脚本会依次：创建虚拟环境 → 安装依赖 → 启动服务。
首次运行会自动下载模型（`bge-reranker-v2-m3` 约 2.2GB）。

**国内网络务必先设镜像**，否则下载会非常慢：

```bash
# Windows
set HF_ENDPOINT=https://hf-mirror.com

# Linux / macOS
export HF_ENDPOINT=https://hf-mirror.com
```

> **Python 版本要求**：PyTorch 对 3.13+ 支持不完整，建议用 **Python 3.10 ~ 3.12**。
> 本机若只有 3.14，先装一个 3.12：`winget install Python.Python.3.12`，
> 脚本会用 `py -3.12` 创建虚拟环境。

启动成功后：

```bash
curl http://127.0.0.1:8090/health
# {"status":"ok","model":"BAAI/bge-reranker-v2-m3","device":"cpu"}
```

---

## 3. 接到 Java 应用上

在 `rag-knowledge` 页面右上角「设置 → 第 2 层 · 精排 Rerank」里填：

| 配置项 | 值 |
| --- | --- |
| 模式 | `auto`（推荐）或 `http` |
| 服务地址 | `http://127.0.0.1:8090/rerank` |
| 精排模型 | `bge-reranker-v2-m3` |

或者直接改 `application.properties`：

```properties
kb.rerank.mode=auto
kb.rerank.api-url=http://127.0.0.1:8090/rerank
kb.rerank.model=bge-reranker-v2-m3
kb.rerank.timeout-ms=8000
```

保存后提问，界面的「检索轨迹」里精排那一节点会显示 `精排 bge-reranker`，
命中片段卡片的「精排」一列就是模型打的真实分数。

**怎么确认真的生效了？** 把服务停掉再问一次，节点会变成
`精排 local`，并且页面顶部出现黄色提示「远程精排服务不可用，已降级为本地精排」。
这就是降级路径在工作。

---

## 4. 可调环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RERANKER_MODEL` | `BAAI/bge-reranker-v2-m3` | 模型名或本地路径 |
| `RERANKER_DEVICE` | `cpu` | `cuda` 会快一个数量级 |
| `RERANKER_PORT` | `8090` | 监听端口 |
| `RERANKER_HOST` | `0.0.0.0` | 监听地址 |
| `RERANKER_MAX_DOCUMENTS` | `64` | 单请求最多文档数，防打爆显存 |
| `RERANKER_MAX_LENGTH` | `512` | 截断长度，交叉编码器开销与长度近似平方相关 |
| `HF_ENDPOINT` | — | HuggingFace 镜像地址 |

### 模型怎么选

| 模型 | 体积 | 说明 |
| --- | --- | --- |
| `BAAI/bge-reranker-v2-m3` | ~2.2GB | **默认**，多语言，中文效果好，推荐 |
| `BAAI/bge-reranker-base` | ~1.1GB | 更小更快，纯中英文场景够用 |
| `BAAI/bge-reranker-large` | ~2.2GB | 精度略高，速度更慢 |

### CPU 上大概多快

CPU（8 核）上 `bge-reranker-v2-m3` 处理 20 个 300 字候选，一次大约 **0.6 ~ 2 秒**。
召回阶段把候选压到 20 条以内，就是为了让这一步的开销可接受——这正是
"粗排召回 + 精排重排" 这个架构存在的意义。

---

## 5. 换个推理框架也行

`/rerank` 接口遵循 Jina / Cohere / SiliconFlow / TEI 的通用协议，
以下任何一个都可以直接替换本脚本，改个 URL 即可：

- **TEI**（HuggingFace text-embeddings-inference）：`text-embeddings-router --reranker-model-id BAAI/bge-reranker-v2-m3`
- **Xinference**：`xinference launch --model-name bge-reranker-v2-m3 --model-type rerank`
- **Ollama / vLLM** 的 rerank 端点（若版本支持）
- 云端 API（硅基流动、Jina、Cohere）——把 `api-key` 一起填上即可

Java 侧不需要任何改动。
