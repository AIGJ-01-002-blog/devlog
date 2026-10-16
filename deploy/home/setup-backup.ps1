# 집 PC가 매일 학교 서버의 devlog 백업을 받아 오게 한 번만 설정한다 (#356). Windows 10/11의 기본 OpenSSH·tar를 쓴다.
#
#   powershell -ExecutionPolicy Bypass -File D:\devlog\deploy\home\setup-backup.ps1 -Target <아이디>@<학교 서버 주소> -Port <SSH 포트> `
#       -KnownHosts <SCHOOL_SSH_KNOWN_HOSTS 값을 담은 파일>
#
# 하는 일
#   1. 백업 전용 SSH 키를 %USERPROFILE%\.devlog-backup에 만든다(이 PC 밖으로 나가지 않는다)
#   2. 학교 서버 SSH 키를 -KnownHosts(GitHub 저장소 변수 SCHOOL_SSH_KNOWN_HOSTS와 같은 값)로 고정하고, 그 키가 맞을 때만
#      학교 서버에 backup.sh·backup-stream.sh를 올리고, 이 키로는 backup-stream.sh만 돌게 authorized_keys에 묶는다
#      (restrict: 셸·포트 전달·파일 복사 불가). 이때 한 번만 SSH 비밀번호를 묻는다
#   3. 한 번 받아 보고, 작업 스케줄러에 "켤 때(5분 뒤)"와 "매일 -Time"을 건다. 놓친 시각은 켜지면 따라잡는다
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [int]$Port = 22,
    # known_hosts 줄(SCHOOL_SSH_KNOWN_HOSTS 값)을 담은 파일 경로나 그 줄 자체. 한 번 고정했으면 다음 실행부터는 생략할 수 있다
    [string]$KnownHosts = "",
    [string]$Dest = "D:\devlog-backups",
    [int]$Keep = 7,
    [string]$Time = "21:00",
    [switch]$SkipTest
)
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dir = Join-Path $env:USERPROFILE ".devlog-backup"
$key = Join-Path $dir "id_ed25519"
$knownHostsFile = Join-Path $dir "known_hosts"
$TaskName = "devlog 백업 받기"

function Fail($msg) { Write-Host "실패: $msg" -ForegroundColor Red; exit 1 }
foreach ($c in "ssh.exe", "ssh-keygen.exe", "tar.exe") {
    if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { Fail "$c 이 없어요. 설정 → 시스템 → 선택적 기능에서 'OpenSSH 클라이언트'를 추가해 주세요." }
}
New-Item -ItemType Directory -Force -Path $dir, $Dest | Out-Null

Write-Host "== 1/4 백업 전용 키 만들기"
if (-not (Test-Path $key)) {
    # 빈 암호(-N "")를 PowerShell 판마다 다르게 넘기지 않도록 인자를 한 줄로 그대로 준다
    $p = Start-Process -FilePath "ssh-keygen.exe" -ArgumentList "-q -t ed25519 -N `"`" -C devlog-home-backup -f `"$key`"" -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0) { Fail "키를 만들지 못했어요." }
    # 키 파일은 이 사용자만 읽게 한다 (OpenSSH가 넓은 권한의 키를 거부한다)
    icacls $key /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "키 파일 권한을 설정하지 못했어요. $dir 를 지우고 다시 실행해 주세요." }
} else {
    Write-Host "   이미 있어요: $key"
}
$pub = (Get-Content -Raw "$key.pub").Trim()

Write-Host "== 2/4 학교 서버 SSH 키 고정하고 백업 스크립트와 키 올리기 (SSH 비밀번호를 한 번 물어요)"
# 처음 붙을 때 받은 키를 그대로 믿으면 중간에서 가로챈 서버에 비밀번호를 보낼 수 있다. 미리 아는 키로만 붙는다
if ($KnownHosts) {
    $text = $KnownHosts
    try { if (Test-Path -LiteralPath $KnownHosts -PathType Leaf) { $text = Get-Content -Raw -LiteralPath $KnownHosts } } catch { }
    $lines = @($text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") })
    if ($lines.Count -eq 0) { Fail "-KnownHosts에 학교 서버 SSH 키 줄이 없어요." }
    Set-Content -Encoding Ascii -Path $knownHostsFile -Value $lines
} elseif (-not (Test-Path $knownHostsFile)) {
    Fail "학교 서버 SSH 키를 몰라서 비밀번호를 보내지 않아요. GitHub 저장소 Settings → Secrets and variables → Actions → Variables의 SCHOOL_SSH_KNOWN_HOSTS 값을 파일에 저장해 -KnownHosts <파일>로 넘겨 주세요."
}
function B64File($path) { [Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) }
$remote = @"
set -eu
umask 077
d="`$HOME/devlog/backup"
mkdir -p "`$d" "`$HOME/.ssh"
echo $(B64File "$Here\backup.sh") | base64 -d | tr -d '\r' > "`$d/backup.sh"
echo $(B64File "$Here\backup-stream.sh") | base64 -d | tr -d '\r' > "`$d/backup-stream.sh"
chmod 700 "`$d" "`$d/backup.sh" "`$d/backup-stream.sh"
command -v flock >/dev/null || { echo "학교 서버에 flock이 없어요" >&2; exit 1; }
touch "`$HOME/.ssh/authorized_keys"
chmod 700 "`$HOME/.ssh"
chmod 600 "`$HOME/.ssh/authorized_keys"
k='$pub'
grep -vF "`$k" "`$HOME/.ssh/authorized_keys" > "`$HOME/.ssh/authorized_keys.new" || true
echo "restrict,command=\"bash `$d/backup-stream.sh\" `$k" >> "`$HOME/.ssh/authorized_keys.new"
mv "`$HOME/.ssh/authorized_keys.new" "`$HOME/.ssh/authorized_keys"
echo devlog-backup-installed
"@
# 줄 끝(CRLF)·한글이 PowerShell 판마다 다르게 넘어가지 않게 설치 스크립트 전체를 base64로 한 줄에 담는다
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($remote -replace "`r", "")))
# 고정한 키와 다른 서버에는 비밀번호를 묻기 전에 끊는다. PC 전체 known_hosts는 보지 않고 이 파일만 믿는다
$installArgs = @("-p", $Port, "-o", "UserKnownHostsFile=`"$knownHostsFile`"", "-o", "StrictHostKeyChecking=yes",
    "-o", "GlobalKnownHostsFile=none", "-o", "PubkeyAuthentication=no", $Target, "echo $b64 | base64 -d | bash")
$installLog = Join-Path $dir "install.log"
$p = Start-Process -FilePath "ssh.exe" -ArgumentList $installArgs -NoNewWindow -Wait -PassThru -RedirectStandardOutput $installLog
if ($p.ExitCode -ne 0 -or -not (Select-String -Quiet -Path $installLog -Pattern "devlog-backup-installed")) {
    Fail "학교 서버에 설치하지 못했어요 (종료 코드 $($p.ExitCode)). 주소·포트·비밀번호와 -KnownHosts 값을 확인해 주세요. 'Host key verification failed'가 보였다면 서버 키가 고정한 값과 달라요."
}

@{ Target = $Target; Port = $Port; Dest = $Dest; Keep = $Keep } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $dir "config.json")

if (-not $SkipTest) {
    Write-Host "== 3/4 한 번 받아 보기 (DB와 사진 크기에 따라 몇 분 걸려요)"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$Here\pull-backup.ps1" -Now
    if ($LASTEXITCODE -ne 0) { Fail "시험 받기가 실패했어요. $Dest\backup.log를 봐 주세요." }
}

Write-Host "== 4/4 작업 스케줄러에 걸기: 켤 때(5분 뒤)와 매일 $Time"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Here\pull-backup.ps1`""
$atLogon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$atLogon.Delay = "PT5M"
$daily = New-ScheduledTaskTrigger -Daily -At $Time
# 꺼져 있어 놓친 시각은 켜지면 바로 돌리고(StartWhenAvailable), 이미 도는 중이면 겹쳐 돌리지 않는다
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 3)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
try {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($atLogon, $daily) -Settings $settings `
        -Principal $principal -Description "학교 서버의 devlog DB·사진 백업을 $Dest 에 받아 온다 (deploy/home/pull-backup.ps1)" -Force | Out-Null
} catch {
    Fail "작업 스케줄러에 걸지 못했어요: $($_.Exception.Message) (PowerShell을 관리자 권한으로 열어 다시 실행해 보세요)"
}
Write-Host "끝: 매일 $Time 와 켤 때 $Dest 로 받아요. 최근 $Keep 개만 남기고, 기록은 $Dest\backup.log 예요."
