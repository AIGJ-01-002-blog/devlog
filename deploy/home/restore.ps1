# 백업 폴더로 devlog를 Windows 집 PC에 되살린다 (#241). 맥·리눅스는 restore.sh.
#
#   powershell -ExecutionPolicy Bypass -File deploy\home\restore.ps1 -Backup E:\devlog\devlog-backup-20261130-2100 [-Force]
#
# 순서와 하는 일은 restore.sh와 같다. Docker Desktop이 켜져 있어야 한다.
param(
    [Parameter(Mandatory = $true)][string]$Backup,
    [switch]$Force,
    [string]$Site = "http://localhost:8080",
    [string]$OldSite = "https://devlog.life",
    [string]$OldMedia = "https://devlog.life/blog-images"
)
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Fail($msg) { Write-Host "실패: $msg" -ForegroundColor Red; exit 1 }
function Dc { docker compose -f "$Here\compose.yaml" --env-file "$Here\.env" @args; if ($LASTEXITCODE -ne 0) { Fail "docker compose $args" } }
function Psql { Dc exec -T postgres psql -U blog -d blog -q @args }
# 파일 내용을 psql 표준 입력으로 넘긴다 (PowerShell 파이프는 글자로 다루므로 SQL 파일에만 쓴다)
function PsqlFile($file, $vars) {
    Get-Content -Raw -Encoding UTF8 $file | docker compose -f "$Here\compose.yaml" --env-file "$Here\.env" exec -T postgres psql -U blog -d blog -q @vars
    if ($LASTEXITCODE -ne 0) { Fail "SQL 실행: $file" }
}

if (-not (Test-Path "$Backup\db.dump") -or -not (Test-Path "$Backup\media")) { Fail "$Backup 안에 db.dump와 media 폴더가 없어요." }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker Desktop을 먼저 설치해 주세요." }
$Backup = (Resolve-Path $Backup).Path
# Docker Desktop은 E:/폴더 처럼 / 로 쓴 경로를 받는다
$media = ($Backup + "\media") -replace "\\", "/"
@(
    "DEVLOG_MEDIA=$media",
    "SITE_BASE_URL=$Site",
    "OLD_IMAGE_BASE_URL=$OldMedia"
) | Set-Content -Encoding ASCII "$Here\.env"

Write-Host "== 1/5 DB 띄우는 중"
Dc up -d --wait postgres redis

$tables = (Psql -tA -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'" | Out-String).Trim()
if ($tables -ne "0") {
    if (-not $Force) { Fail "이미 되살린 DB가 있어요. 지우고 다시 넣으려면 -Force를 붙여 주세요." }
    Write-Host "   있던 DB를 지우고 다시 넣어요"
    Psql -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
}

Write-Host "== 2/5 db.dump 넣는 중"
Dc cp "$Backup\db.dump" postgres:/tmp/db.dump
Dc exec -T postgres pg_restore -U blog -d blog --no-owner --no-privileges --exit-on-error /tmp/db.dump
Dc exec -T postgres rm -f /tmp/db.dump

Write-Host "== 3/5 옛 주소를 $Site 로 바꾸는 중"
PsqlFile "$Here\rebase-urls.sql" @("-v", "old_media=$OldMedia", "-v", "new_media=$Site/media", "-v", "old_site=$OldSite", "-v", "new_site=$Site")

Write-Host "== 4/5 주인 계정 비밀번호 로그인 만들기 (건너뛰려면 주소에서 그냥 Enter)"
$handle = Read-Host "   내 블로그 주소(@ 뒤, 예: kim755030)"
if ($handle) {
    $email = Read-Host "   로그인할 이메일"
    while ($true) {
        $p1 = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host "   새 비밀번호(8자 이상)" -AsSecureString)))
        $p2 = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host "   한 번 더" -AsSecureString)))
        if ($p1 -eq $p2 -and $p1.Length -ge 8) { break }
        Write-Host "   두 비밀번호가 다르거나 8자보다 짧아요."
    }
    # 비밀번호는 이 컴퓨터의 명령줄에 남기지 않고 환경 변수로만 컨테이너에 넘겨 bcrypt 해시로 바꾼다
    $env:DEVLOG_PW = $p1
    $out = (docker run --rm -e DEVLOG_PW httpd:2.4-alpine sh -c 'htpasswd -nbBC 10 x "$DEVLOG_PW"') | Out-String
    $code = $LASTEXITCODE
    Remove-Item Env:DEVLOG_PW
    Remove-Variable p1, p2
    $hash = ($out.Trim().Split(":") | Select-Object -Index 1)
    if ($code -ne 0 -or $hash -notmatch '^\$2[aby]\$') { Fail "비밀번호 해시를 만들지 못했어요. Docker가 httpd 이미지를 받을 수 있는지 확인해 주세요." }
    PsqlFile "$Here\owner-login.sql" @("-v", "handle=$handle", "-v", "email=$email", "-v", "hash=$hash")
}

Write-Host "== 5/5 앱 빌드하고 띄우는 중 (처음에는 몇 분 걸려요)"
Dc up -d --build --wait app
Write-Host "== 끝: $Site 에서 열어 보세요. 끄기: docker compose -f `"$Here\compose.yaml`" down (데이터는 남아요)"
