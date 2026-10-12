#!/usr/bin/env bash
# 启动 bge-reranker 本地精排服务（Linux / macOS）
# 首次运行会自动下载模型（bge-reranker-v2-m3 约 2.2GB）
set -euo pipefail

export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
export RERANKER_MODEL="${RERANKER_MODEL:-BAAI/bge-reranker-v2-m3}"
export RERANKER_DEVICE="${RERANKER_DEVICE:-cpu}"
export RERANKER_PORT="${RERANKER_PORT:-8090}"

cd "$(dirname "$0")"

if [ ! -d ".venv" ]; then
    echo "[1/3] 创建虚拟环境..."
    python3 -m venv .venv
fi

echo "[2/3] 安装依赖..."
# shellcheck disable=SC1091
source .venv/bin/activate
python -m pip install --upgrade pip -q
python -m pip install -r requirements.txt -q

echo "[3/3] 启动服务 ${RERANKER_MODEL} (${RERANKER_DEVICE}) on port ${RERANKER_PORT}"
echo "健康检查: http://127.0.0.1:${RERANKER_PORT}/health"
exec python app.py
