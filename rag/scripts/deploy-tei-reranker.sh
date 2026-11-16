#!/usr/bin/env bash
set -Eeuo pipefail

# 在目标 Linux 服务器上执行。依赖 Docker、curl；GPU 模式还需要 NVIDIA Container Toolkit。
# 参数依据：
# https://huggingface.co/docs/text-embeddings-inference/quick_tour
# https://github.com/huggingface/text-embeddings-inference/tree/v1.9.3

usage() {
    cat <<'EOF'
部署 TEI 重排服务（默认模型：BAAI/bge-reranker-large）。

使用服务器已有的模型：
  MODEL_DIR=/root/.cache/modelscope/hub/models/BAAI/bge-reranker-large \
    DEVICE=0 PORT=8002 bash deploy-tei-reranker.sh

从 Hugging Face 下载模型，并缓存到当前目录下：
  DEVICE=0 PORT=8002 bash deploy-tei-reranker.sh

使用 CPU：
  DEVICE=cpu PORT=8002 bash deploy-tei-reranker.sh

环境变量：
  DEVICE               GPU 编号（默认 0），或 cpu
  PORT                 宿主机端口（默认 8002）
  CONTAINER_NAME       容器名称（默认 tei-reranker-study）
  MODEL_ID             在线模型 ID（默认 BAAI/bge-reranker-large）
  MODEL_DIR            已下载完整模型的目录；设置后优先使用本地模型
  CACHE_DIR            下载缓存目录（默认 $PWD/tei-reranker-data）
  IMAGE                自定义 TEI 镜像，用于匹配服务器硬件或使用本地镜像
  MAX_BATCH_TOKENS     单批 token 总量（默认 4096）
  MAX_CLIENT_BATCH_SIZE 单次请求最多文本数（默认 32）
  STARTUP_TIMEOUT      等待服务就绪的秒数（默认 900）
  HF_TOKEN             可选；由环境变量传递，不会打印其值

同名容器已存在时直接退出，不删除或替换现有容器。
EOF
}

fail() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi
[[ $# -eq 0 ]] || fail "不支持位置参数，请使用 --help 查看配置方法"

DEVICE="${DEVICE:-0}"
PORT="${PORT:-8002}"
CONTAINER_NAME="${CONTAINER_NAME:-tei-reranker-study}"
MODEL_ID="${MODEL_ID:-BAAI/bge-reranker-large}"
MODEL_DIR="${MODEL_DIR:-}"
CACHE_DIR="${CACHE_DIR:-$PWD/tei-reranker-data}"
MAX_BATCH_TOKENS="${MAX_BATCH_TOKENS:-4096}"
MAX_CLIENT_BATCH_SIZE="${MAX_CLIENT_BATCH_SIZE:-32}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-900}"

positive_integer() {
    [[ "$1" =~ ^[1-9][0-9]{0,8}$ ]]
}

positive_integer "$PORT" && (( PORT <= 65535 )) || fail "PORT 必须为 1 到 65535 的整数"
positive_integer "$MAX_BATCH_TOKENS" || fail "MAX_BATCH_TOKENS 必须为正整数"
positive_integer "$MAX_CLIENT_BATCH_SIZE" || fail "MAX_CLIENT_BATCH_SIZE 必须为正整数"
positive_integer "$STARTUP_TIMEOUT" || fail "STARTUP_TIMEOUT 必须为正整数"

gpu_args=()
if [[ "$DEVICE" == "cpu" ]]; then
    IMAGE="${IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cpu-1.9}"
    dtype="float32"
elif [[ "$DEVICE" =~ ^[0-9]+$ ]]; then
    IMAGE="${IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cuda-1.9}"
    dtype="float16"
    gpu_args=(--gpus "device=$DEVICE")
else
    fail "DEVICE 必须为单个 GPU 编号或 cpu"
fi

command -v docker >/dev/null 2>&1 || fail "请先安装 Docker"
command -v curl >/dev/null 2>&1 || fail "请先安装 curl"
docker info >/dev/null 2>&1 || fail "无法访问 Docker，请检查服务状态及当前用户权限"

if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    fail "容器 $CONTAINER_NAME 已存在；请使用其他 CONTAINER_NAME 和 PORT"
fi

mount_args=()
if [[ -n "$MODEL_DIR" ]]; then
    [[ -d "$MODEL_DIR" ]] || fail "模型目录不存在：$MODEL_DIR"
    MODEL_DIR="$(cd "$MODEL_DIR" && pwd -P)"
    [[ -f "$MODEL_DIR/config.json" ]] || fail "模型目录缺少 config.json：$MODEL_DIR"
    mount_args=(--mount "type=bind,src=$MODEL_DIR,dst=/model,readonly")
    model_argument="/model"
    printf '使用本地模型：%s\n' "$MODEL_DIR"
else
    mkdir -p "$CACHE_DIR"
    CACHE_DIR="$(cd "$CACHE_DIR" && pwd -P)"
    mount_args=(--mount "type=bind,src=$CACHE_DIR,dst=/data")
    model_argument="$MODEL_ID"
    printf '使用在线模型：%s；缓存目录：%s\n' "$MODEL_ID" "$CACHE_DIR"
fi

token_args=()
if [[ -n "${HF_TOKEN:-}" ]]; then
    export HF_TOKEN
    token_args=(--env HF_TOKEN)
fi

printf '启动容器：%s，端口：%s，设备：%s，镜像：%s\n' \
    "$CONTAINER_NAME" "$PORT" "$DEVICE" "$IMAGE"

docker run --detach \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    --publish "$PORT:80" \
    --log-driver json-file \
    --log-opt max-size=10m \
    --log-opt max-file=3 \
    "${gpu_args[@]}" \
    "${mount_args[@]}" \
    "${token_args[@]}" \
    "$IMAGE" \
    --model-id "$model_argument" \
    --hostname 0.0.0.0 \
    --port 80 \
    --dtype "$dtype" \
    --auto-truncate true \
    --max-concurrent-requests 32 \
    --max-batch-tokens "$MAX_BATCH_TOKENS" \
    --max-client-batch-size "$MAX_CLIENT_BATCH_SIZE" >/dev/null

base_url="http://127.0.0.1:$PORT"
deadline=$((SECONDS + STARTUP_TIMEOUT))
ready=false
printf '等待模型加载，最多等待 %s 秒……\n' "$STARTUP_TIMEOUT"

while (( SECONDS < deadline )); do
    if curl --fail --silent --connect-timeout 1 --max-time 2 \
        "$base_url/health" >/dev/null 2>&1; then
        ready=true
        break
    fi

    state="$(docker container inspect --format '{{.State.Status}}' "$CONTAINER_NAME")"
    if [[ "$state" != "running" && "$state" != "restarting" ]]; then
        docker logs --tail 60 "$CONTAINER_NAME" >&2
        fail "容器当前状态为 $state，请检查启动日志"
    fi
    sleep 2
done

if [[ "$ready" != "true" ]]; then
    docker logs --tail 60 "$CONTAINER_NAME" >&2
    fail "等待超时；容器仍保留，可用 docker logs -f $CONTAINER_NAME 查看进度"
fi

printf '\n服务已就绪，模型信息：\n'
curl --fail --silent --show-error --connect-timeout 5 --max-time 10 \
    "$base_url/info"

printf '\n\n执行重排测试：\n'
if ! curl --fail --silent --show-error --connect-timeout 5 --max-time 60 \
    "$base_url/rerank" \
    --header 'Content-Type: application/json' \
    --data-binary @- <<'JSON'
{
  "query": "我买的东西还没寄出，钱能退给我吗？",
  "texts": [
    "订单发货后，通常在3天内送达。",
    "未发货订单可以申请退款，申请时需要提供订单号。",
    "已签收商品可在7天内申请退货，商品须保持完好。"
  ],
  "raw_scores": false,
  "return_text": true
}
JSON
then
    fail "重排测试失败；请执行 docker logs --tail 60 $CONTAINER_NAME 检查原因"
fi

printf '\n\n部署及重排请求测试完成。Java baseUrl： http://<服务器IP>:%s\n' "$PORT"
printf '查看日志：docker logs -f %s\n' "$CONTAINER_NAME"
