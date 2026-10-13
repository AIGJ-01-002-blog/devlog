# 측정 공통 함수 (issue #172). 결과는 $OUT(마크다운)에 쌓는다.
N="${N:-200}"
PATHS=("/api/auth/me" "/api/posts" "/")
OUT="${OUT:-$PWD/result.md}"
log() { echo "$*" | tee -a "$OUT"; }

# "상태코드 초" 줄들로 p50·p95·평균 ms와 실패 수를 낸다. 2xx·3xx만 표본으로 쓰고, 연결 실패(000)·4xx·5xx는 실패로 센다
stats() { awk '$1>=200 && $1<400 {print $2; next} {f++} END {if (f) print "FAIL " f}' | sort -n | awk '/^FAIL/ {f=$2; next} {a[++n]=$1*1000; s+=$1*1000} END {if (n==0) {printf "| - | - | - | %d |\n", f; exit} m=(n%2)?a[(n+1)/2]:(a[n/2]+a[n/2+1])/2; i=int(n*0.95); if (i<n*0.95) i++; printf "| %.2f | %.2f | %.2f | %d |\n", m, a[i], s/n, f}'; }

# 셸 하나에서 curl을 N번 부르는 반복문. 컨테이너·파드 안에서 sh -c로 돈다 ($1=N, $2=URL, $3=Host 머리)
LOOP='i=0; while [ $i -lt $1 ]; do curl -s -o /dev/null ${3:+-H "Host: $3"} -w "%{http_code} %{time_total}\n" "$2" || :; i=$((i+1)); done'

# ab 결과 → | 초당 처리 | p95 | 실패 |. 실패는 연결 실패(Failed requests)와 2xx가 아닌 응답(Non-2xx responses)을 더한 수
AB_PARSE='/Requests per second/ {r=$4} /^  95%/ {q=$2} /Failed requests/ {f=$3} /Non-2xx responses/ {x=$3} END {printf "| %s | %s | %d |\n", r, q, f+x}'

# 메모리: docker stats 합계(MiB)
mem_sum() { docker stats --no-stream --format '{{.MemUsage}}' "$@" | awk '{v=$1; u=v; gsub(/[0-9.]/,"",u); gsub(/[A-Za-z]/,"",v); m=(u=="GiB")?v*1024:(u=="KiB")?v/1024:(u=="B")?v/1048576:v; s+=m} END {printf "%.0f", s}'; }
cpu_sum() { docker stats --no-stream --format '{{.CPUPerc}}' "$@" | tr -d % | awk '{s+=$1} END {printf "%.1f", s}'; }
