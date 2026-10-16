# 학교 서버의 devlog 백업(DB와 사진·첨부)을 집 PC로 받아 온다 (#356). 작업 스케줄러가 켤 때와 매일 한 번 부른다.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File D:\devlog\deploy\home\pull-backup.ps1 [-Now]
#
# 설정은 setup-backup.ps1이 만든 %USERPROFILE%\.devlog-backup\config.json을 읽는다.
# 백업 전용 키로 접속하면 서버는 backup-stream.sh만 돌려 tar를 보내 준다. 집으로 들어오는 포트는 열지 않는다.
# 오늘 받은 백업이 있으면 그냥 끝난다(-Now면 다시 받는다). 받은 뒤 db.dump의 SHA-256과 파일 수를 서버 목록과 맞춰 본다.
param(
    [switch]$Now,
    [string]$Config = (Join-Path $env:USERPROFILE ".devlog-backup\config.json")
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path $Config)) { Write-Host "설정 파일이 없어요: $Config (setup-backup.ps1을 먼저 실행해 주세요)"; exit 1 }
$cfg = Get-Content -Raw -Encoding UTF8 $Config | ConvertFrom-Json
$dir = Split-Path -Parent $Config
$dest = $cfg.Dest
$keep = if ($cfg.Keep) { [int]$cfg.Keep } else { 7 }
$ssh = if ($cfg.Ssh) { $cfg.Ssh } else { "ssh.exe" }
$tarExe = if ($cfg.Tar) { $cfg.Tar } else { "tar.exe" }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$logFile = Join-Path $dest "backup.log"

function Log($msg) {
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -Encoding UTF8 -Path $logFile -Value $line
}

function Backups { Get-ChildItem -Path $dest -Directory -Filter "devlog-backup-*" | Sort-Object Name }

# 마지막 성공이 이틀 넘게 없으면 창으로 알린다(로그만 남기면 아무도 모른다)
function Alert($msg) {
    $last = Backups | Select-Object -Last 1
    if ($last -and $last.CreationTime -gt (Get-Date).AddDays(-2)) { return }
    if ($cfg.NoPopup) { return }
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show("devlog 백업을 이틀 넘게 받지 못했어요.`n`n$msg`n`n기록: $logFile", "devlog 백업") | Out-Null
    } catch { }
}

$today = Get-Date -Format "yyyyMMdd"
if (-not $Now -and (Backups | Where-Object { $_.Name -like "devlog-backup-$today-*" })) {
    Log "오늘 받은 백업이 이미 있어요. 건너뜀"
    exit 0
}

$incoming = Join-Path $dest ".incoming"
$tar = Join-Path $incoming "backup.tar"
$errLog = Join-Path $incoming "ssh.log"
$out = Join-Path $incoming "devlog-backup"

# Start-Process는 인자를 그대로 이어 붙이므로 경로마다 따옴표로 감싼다
function Q($s) { '"' + $s + '"' }
$sshArgs = @(
    "-i", (Q (Join-Path $dir "id_ed25519")),
    "-p", $cfg.Port,
    "-o", "BatchMode=yes",
    "-o", "IdentitiesOnly=yes",
    "-o", ("UserKnownHostsFile=" + (Q (Join-Path $dir "known_hosts"))),
    "-o", "StrictHostKeyChecking=yes",
    "-o", "ConnectTimeout=20",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=6",
    $cfg.Target
)

function Pull {
    if (Test-Path $incoming) { Remove-Item -Recurse -Force $incoming }
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    # PowerShell의 > 와 Start-Process -RedirectStandardOutput은 출력을 글자로 바꿔 tar를 망가뜨린다.
    # 표준 출력의 바이트를 그대로 파일에 옮긴다
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $ssh
    $psi.Arguments = $sshArgs -join " "
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $errTask = $proc.StandardError.ReadToEndAsync()
    $fs = [System.IO.File]::Create($tar)
    try { $proc.StandardOutput.BaseStream.CopyTo($fs) } finally { $fs.Dispose() }
    $proc.WaitForExit()
    [System.IO.File]::WriteAllText($errLog, $errTask.Result)
    if ($proc.ExitCode -ne 0) {
        $tail = (Get-Content -Encoding UTF8 $errLog -Tail 5) -join " / "
        throw "서버에서 받지 못했어요 (ssh 종료 코드 $($proc.ExitCode)). $tail"
    }
    if (-not (Test-Path $tar) -or (Get-Item $tar).Length -eq 0) { throw "받은 파일이 비어 있어요." }

    & $tarExe -xf $tar -C $out | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "tar를 풀지 못했어요 (종료 코드 $LASTEXITCODE)." }

    $manifestPath = Join-Path $out "MANIFEST.txt"
    $dump = Join-Path $out "db.dump"
    if (-not (Test-Path $manifestPath) -or -not (Test-Path $dump)) { throw "받은 백업에 MANIFEST.txt나 db.dump가 없어요." }
    $manifest = Get-Content -Raw -Encoding UTF8 $manifestPath
    if ($manifest -notmatch "db\.dump: (\d+) bytes, sha256 ([0-9a-f]{64})") { throw "MANIFEST.txt에서 db.dump 정보를 읽지 못했어요." }
    $wantSize = [long]$Matches[1]; $wantSum = $Matches[2]
    $gotSize = (Get-Item $dump).Length
    $gotSum = (Get-FileHash -Algorithm SHA256 $dump).Hash.ToLowerInvariant()
    if ($gotSize -ne $wantSize -or $gotSum -ne $wantSum) { throw "db.dump가 서버 것과 달라요." }
    if ($manifest -notmatch "media: 파일 (\d+)개") { throw "MANIFEST.txt에서 파일 수를 읽지 못했어요." }
    $wantFiles = [int]$Matches[1]
    $media = Join-Path $out "media"
    $gotFiles = if (Test-Path $media) { @(Get-ChildItem -Recurse -File $media).Count } else { 0 }
    if ($gotFiles -ne $wantFiles) { throw "사진·파일 수가 달라요 (서버 $wantFiles, 받은 것 $gotFiles)." }

    $name = "devlog-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss")
    # 같은 초에 두 번 받아도 앞 백업 안으로 들어가지 않게 이름을 비켜 준다
    $n = 1; $base = $name
    while (Test-Path (Join-Path $dest $name)) { $n++; $name = "$base-$n" }
    Move-Item $out (Join-Path $dest $name)
    Remove-Item -Recurse -Force $incoming
    return @{ Name = $name; Files = $gotFiles; Size = $gotSize }
}

# 학교 서버가 잠깐 안 되거나 다른 백업과 겹쳐도(종료 코드 75) 10분 간격으로 세 번까지 해 본다
$attempts = if ($cfg.Attempts) { [int]$cfg.Attempts } else { 3 }
$wait = if ($null -ne $cfg.RetrySeconds) { [int]$cfg.RetrySeconds } else { 600 }
for ($i = 1; $i -le $attempts; $i++) {
    try {
        $r = Pull
        Log ("받음: {0} (DB {1:N0} bytes, 파일 {2}개)" -f $r.Name, $r.Size, $r.Files)
        break
    } catch {
        Log "실패 ($i/$attempts): $($_.Exception.Message)"
        if ($i -eq $attempts) {
            if (Test-Path $incoming) { Remove-Item -Recurse -Force $incoming }
            Alert $_.Exception.Message
            exit 1
        }
        Start-Sleep -Seconds $wait
    }
}

# 오래된 백업은 최근 ${keep}개만 남긴다
$all = @(Backups)
if ($all.Count -gt $keep) {
    $all | Select-Object -First ($all.Count - $keep) | ForEach-Object {
        Remove-Item -Recurse -Force $_.FullName
        Log "지움: $($_.Name) (최근 ${keep}개만 남김)"
    }
}
exit 0
