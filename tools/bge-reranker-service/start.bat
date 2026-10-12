@echo off
REM 启动 bge-reranker 本地精排服务（Windows）
REM 首次运行会自动下载模型（bge-reranker-v2-m3 约 2.2GB）

setlocal

REM 国内网络建议打开镜像（下载快很多）
if "%HF_ENDPOINT%"=="" set HF_ENDPOINT=https://hf-mirror.com

REM 默认模型与设备；有 GPU 时改成 cuda 会快一个数量级
if "%RERANKER_MODEL%"=="" set RERANKER_MODEL=BAAI/bge-reranker-v2-m3
if "%RERANKER_DEVICE%"=="" set RERANKER_DEVICE=cpu
if "%RERANKER_PORT%"=="" set RERANKER_PORT=8090

cd /d "%~dp0"

if not exist ".venv" (
    echo [1/3] 创建虚拟环境...
    py -3.12 -m venv .venv || python -m venv .venv
)

echo [2/3] 安装依赖...
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip -q
python -m pip install -r requirements.txt -q

echo [3/3] 启动服务 %RERANKER_MODEL% (%RERANKER_DEVICE%) on port %RERANKER_PORT%
echo 健康检查: http://127.0.0.1:%RERANKER_PORT%/health
python app.py

endlocal
