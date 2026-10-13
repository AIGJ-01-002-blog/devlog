# 측정 공통 함수 (issue #172). 결과는 $OUT(마크다운)에 쌓는다.
N="${N:-200}"
PATHS=("/api/auth/me" "/api/posts" "/")
OUT="${OUT:-$PWD/result.md}"
log() { echo "$*" | tee -a "$OUT"; }

# 받은 초 단위 값들(한 줄에 하나)로 p50·p95·평균 ms를 낸다
stats() { sort -n | awk '{a[NR]=$1*1000; s+=$1*1000} END {if (NR==0) {print "| - | - | - |"; exit} i=int(NR*0.95); if (i<1) i=1; printf "| %.2f | %.2f | %.2f |\n", a[int((NR+1)/2)], a[i], s/NR}'; }

# 셸 하나에서 curl을 N번 부르는 반복문. 컨테이너·파드 안에서 sh -c로 돈다 ($1=N, $2=URL, $3=Host 머리)
LOOP='i=0; while [ $i -lt $1 ]; do curl -s -o /dev/null ${3:+-H "Host: $3"} -w "%{time_total}\n" "$2"; i=$((i+1)); done'

# 부하: ab로 동시 20개 2000건 → 초당 처리 수와 p95
load() { ab -q -k -c 20 -n 2000 ${2:+-H "Host: $2"} "$1" 2>/dev/null | awk '/Requests per second/ {r=$4} /^  95%/ {p=$2} /Failed requests/ {f=$3} END {printf "| %s | %s | %s |\n", r, p, f}'; }

# 메모리: docker stats 합계(MiB)
mem_sum() { docker stats --no-stream --format '{{.MemUsage}}' "$@" | awk '{v=$1; u=v; gsub(/[0-9.]/,"",u); gsub(/[A-Za-z]/,"",v); m=(u=="GiB")?v*1024:(u=="KiB")?v/1024:(u=="B")?v/1048576:v; s+=m} END {printf "%.0f", s}'; }
cpu_sum() { docker stats --no-stream --format '{{.CPUPerc}}' "$@" | tr -d % | awk '{s+=$1} END {printf "%.1f", s}'; }
