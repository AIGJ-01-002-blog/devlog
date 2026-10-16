# 집 PC가 매일 학교 서버의 devlog 백업을 받아 오게 한 번만 설정한다 (#356). Windows 10/11의 기본 OpenSSH·tar를 쓴다.
#
#   powershell -ExecutionPolicy Bypass -File D:\devlog\deploy\home\setup-backup.ps1 -Target <아이디>@<학교 서버 주소> -Port <SSH 포트>
#
# 하는 일
#   1. 백업 전용 SSH 키를 %USERPROFILE%\.devlog-backup에 만든다(이 PC 밖으로 나가지 않는다)
#   2. 학교 서버에 backup.sh·backup-stream.sh를 올리고, 이 키로는 backup-stream.sh만 돌게 authorized_keys에 묶는다
#      (restrict: 셸·포트 전달·파일 복사 불가). 이때 한 번만 SSH 비밀번호를 묻는다
#   3. 한 번 받아 보고, 작업 스케줄러에 "켤 때(5분 뒤)"와 "매일 -Time"을 건다. 놓친 시각은 켜지면 따라잡는다
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [int]$Port = 22,
    [string]$Dest = "D:\devlog-backups",
    [int]$Keep = 7,
    [string]$Time = "21:00",
    [switch]$SkipTest
)
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dir = Join-Path $env:USERPROFILE ".devlog-backup"
$key = Join-Path $dir "id_ed25519"
$knownHosts = Join-Path $dir "known_hosts"
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
} else {
    Write-Host "   이미 있어요: $key"
}
$pub = (Get-Content -Raw "$key.pub").Trim()

Write-Host "== 2/4 학교 서버에 백업 스크립트와 키 올리기 (SSH 비밀번호를 한 번 물어요)"
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
# 처음 붙는 서버의 SSH 키는 여기서 받아 known_hosts에 고정한다. 다음부터는 바뀐 키로는 붙지 않는다
$installArgs = @("-p", $Port, "-o", "UserKnownHostsFile=`"$knownHosts`"", "-o", "StrictHostKeyChecking=accept-new",
    "-o", "PubkeyAuthentication=no", $Target, "echo $b64 | base64 -d | bash")
$installLog = Join-Path $dir "install.log"
$p = Start-Process -FilePath "ssh.exe" -ArgumentList $installArgs -NoNewWindow -Wait -PassThru -RedirectStandardOutput $installLog
if ($p.ExitCode -ne 0 -or -not (Select-String -Quiet -Path $installLog -Pattern "devlog-backup-installed")) {
    Fail "학교 서버에 설치하지 못했어요 (종료 코드 $($p.ExitCode)). 주소·포트·비밀번호를 확인해 주세요."
}
Write-Host "   학교 서버 SSH 키 지문 (GitHub 변수 SCHOOL_SSH_KNOWN_HOSTS와 같은지 한 번 봐 두세요):"
ssh-keygen.exe -l -f $knownHosts

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
