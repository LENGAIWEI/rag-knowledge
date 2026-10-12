#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bge-reranker 本地推理服务（精排/Rerank 的"真模型"后端）。

本服务是 rag-knowledge 的可选组件：
    Java 应用只通过 HTTP 调用它，双方零耦合。服务不启动时，
    Java 侧会自动降级为纯 Java 的本地精排，问答链路不会中断。

为什么单独跑一个进程，而不是塞进 Java？
    1. 模型要加载 PyTorch，占几百 MB 到几 GB 内存，崩了不该把 Web 服务带走；
    2. 换模型/换量化精度/换推理框架都不用改 Java 代码；
    3. 可以部署在另一台有 GPU 的机器上（改个 URL 就行）。

接口协议（与 Jina / Cohere / SiliconFlow / TEI 的 /rerank 一致）：
    POST /rerank
    {"model":"bge-reranker-v2-m3","query":"...","documents":["...","..."],"top_n":20}
    200 -> {"results":[{"index":0,"relevance_score":0.93}, ...]}
    GET  /health -> {"status":"ok","model":"...","device":"cpu"}

启动：
    pip install -r requirements.txt
    python app.py
    # 默认监听 0.0.0.0:8090，首次运行会自动从 HuggingFace 下载模型（约 2.2GB）

国内网络建议先设镜像：
    Windows : set HF_ENDPOINT=https://hf-mirror.com
    Linux   : export HF_ENDPOINT=https://hf-mirror.com

换成更小的模型（更快、精度略降）：
    set RERANKER_MODEL=BAAI/bge-reranker-base          # 约 1.1GB
    set RERANKER_MODEL=BAAI/bge-reranker-v2-m3         # 默认，多语言，中文效果好

有 GPU 时：
    set RERANKER_DEVICE=cuda
"""

import logging
import os
import time

import torch
from fastapi import FastAPI
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("bge-reranker")

MODEL_NAME = os.environ.get("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
DEVICE = os.environ.get("RERANKER_DEVICE", "cpu")
PORT = int(os.environ.get("RERANKER_PORT", "8090"))
HOST = os.environ.get("RERANKER_HOST", "0.0.0.0")

# 单次请求最多处理多少篇文档，防止外部拼一个超大请求把显存打爆
MAX_DOCUMENTS = int(os.environ.get("RERANKER_MAX_DOCUMENTS", "64"))
# 交叉编码器的开销与序列长度近似平方相关，超长文档截断
MAX_LENGTH = int(os.environ.get("RERANKER_MAX_LENGTH", "512"))

log.info("正在加载模型 %s（device=%s）…", MODEL_NAME, DEVICE)
tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)
model.eval()
model.to(DEVICE)
log.info("模型加载完成，监听 %s:%s", HOST, PORT)

app = FastAPI(title="bge-reranker service", version="1.0.0")


class RerankRequest(BaseModel):
    model: str | None = None
    query: str
    documents: list[str] = Field(default_factory=list)
    top_n: int | None = None


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "device": DEVICE}


@app.post("/rerank")
def rerank(req: RerankRequest):
    if not req.documents:
        return {"results": []}

    documents = req.documents[:MAX_DOCUMENTS]
    query = req.query or ""

    # bge-reranker 是交叉编码器：必须把 (query, document) 拼成一个序列送进模型，
    # 所以无法像双塔那样预先算好文档向量，只能每个候选各跑一次前向。
    # 这也是它精度更高、但只能用在召回之后小候选集上的根本原因。
    pairs = [[query, doc] for doc in documents]
    started = time.time()

    with torch.no_grad():
        inputs = tokenizer(
            pairs,
            padding=True,
            truncation=True,
            max_length=MAX_LENGTH,
            return_tensors="pt",
        ).to(DEVICE)
        logits = model(**inputs, return_dict=True).logits.view(-1).float()
        # 输出 logit，由调用方决定是否 sigmoid。
        # 这里直接给 sigmoid 后的分数（0~1），与 Java 侧的阈值语义一致。
        scores = torch.sigmoid(logits).cpu().tolist()

    results = [
        {"index": i, "relevance_score": float(scores[i])}
        for i in range(len(documents))
    ]
    results.sort(key=lambda item: item["relevance_score"], reverse=True)
    if req.top_n and req.top_n > 0:
        results = results[: req.top_n]

    log.info(
        "rerank query=%r docs=%d 耗时=%.0fms top=%.4f",
        query[:40],
        len(documents),
        (time.time() - started) * 1000,
        results[0]["relevance_score"] if results else 0.0,
    )
    return {"model": MODEL_NAME, "results": results}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
