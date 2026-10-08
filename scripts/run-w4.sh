#!/usr/bin/env bash
# 4주차 부록 실험: 설정별로 앱을 새로 띄우고 같은 k6 부하를 준 뒤 HikariCP 지표를 모은다.
# 사용: ./scripts/run-w4.sh   (compose.yml 의 MySQL 이 떠 있어야 한다)
set -euo pipefail
cd "$(dirname "$0")/.."

RATE=${RATE:-40}
DURATION=${DURATION:-30s}
STAMP=$(date +%Y%m%d-%H%M%S)
OUT=evidence/w4-osiv-$STAMP
mkdir -p "$OUT"

./gradlew -q bootJar
JAR=$(ls build/libs/*-0.1.0.jar | grep -v plain)

run_case() {
  local name=$1 target=$2; shift 2
  echo "== $name (target=$target, $*)"
  java -jar "$JAR" --lab=w4 "$@" > "$OUT/$name-app.log" 2>&1 &
  local pid=$!
  until curl -sf localhost:8080/actuator/health > /dev/null; do sleep 1; done

  # 단건 호출 결과(정상 응답인지, 예외인지)를 먼저 남긴다
  curl -s -o "$OUT/$name-single.json" -w '%{http_code}\n' -X POST "localhost:8080/w4/payments/1/$target" > "$OUT/$name-single-status.txt"

  docker run --rm -v "$PWD/k6:/k6" -v "$PWD/$OUT:/out" grafana/k6:1.3.0 run --quiet \
    -e TARGET="$target" -e RATE="$RATE" -e DURATION="$DURATION" \
    --summary-export "/out/$name-k6.json" /k6/w4-approve.js > "$OUT/$name-k6.txt" 2>&1 || true

  for m in hikaricp.connections.usage hikaricp.connections.acquire hikaricp.connections.timeout; do
    curl -s "localhost:8080/actuator/metrics/$m" > "$OUT/$name-$m.json"
  done
  curl -s localhost:8080/w4/pool > "$OUT/$name-pool.json"
  grep -c LazyInitializationException "$OUT/$name-app.log" > "$OUT/$name-lazy-count.txt" || true
  kill "$pid"; wait "$pid" 2>/dev/null || true
}

run_case A_osiv_on_entity      approve       --labs.osiv=true
run_case A2_osiv_on_pool15     approve       --labs.osiv=true --spring.datasource.hikari.maximum-pool-size=15
run_case B_osiv_off_entity     approve       --labs.osiv=false
run_case C_osiv_off_dto        approve-dto   --labs.osiv=false
run_case D_osiv_off_call_in_tx approve-in-tx --labs.osiv=false

echo "saved: $OUT"
