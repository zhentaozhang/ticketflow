# =============================================================
# 被测机版本切换脚本（v5-baseline <-> v5-optimization）
# 用途：压测对比时在被测机切换代码版本并重启服务
#   v5-baseline      = 优化前基线（压测阶段一）
#   v5-optimization  = 优化后（压测阶段二）
#
# 用法（管理员 PowerShell）：
#   .\switch-version.ps1 -Branch v5-baseline -JasyptPassword <密码>
#   .\switch-version.ps1 -Branch v5-optimization -JasyptPassword <密码>
# =============================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("v5-baseline", "v5-optimization")]
    [string]$Branch,
    [string]$JasyptPassword = "",
    [string]$WorkDir = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrEmpty($WorkDir)) { $WorkDir = "D:\ticketflow" }
if (-not (Test-Path (Join-Path $WorkDir ".git"))) {
    throw "目录 $WorkDir 不是 git 仓库（请先用 setup-target-machine.ps1 初始化）"
}
Push-Location $WorkDir

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host " 切换版本: $Branch"
Write-Host " 目录:     $WorkDir"
Write-Host "==============================================" -ForegroundColor Cyan

# 1. 停止旧服务（按端口找 PID，避免误杀其他 java 进程）
Write-Host "[1/4] 停止 6086/8081 上的服务..."
$pids = Get-NetTCPConnection -LocalPort 6086,8081 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
foreach ($pid_ in $pids) {
    Write-Host "  停止 PID $pid_"
    Stop-Process -Id $pid_ -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 3

# 2. 切换分支 + 拉取
Write-Host "[2/4] git checkout $Branch + pull..."
git fetch origin
git checkout $Branch
if ($LASTEXITCODE -ne 0) { throw "git checkout 失败（远端需存在 $Branch）" }
git pull origin $Branch 2>$null | Out-Null

# 3. 重新构建
Write-Host "[3/4] Maven 构建（约 2-5 分钟）..."
mvn install -DskipTests -Dspotless.check.skip=true -q
if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }

# 4. 重启服务
Write-Host "[4/4] 启动服务..."
if (-not [string]::IsNullOrEmpty($JasyptPassword)) {
    $env:JASYPT_ENCRYPTOR_PASSWORD = $JasyptPassword
}
$progJar = Get-ChildItem (Join-Path $WorkDir "ticketflow-server/ticketflow-program-service/target") -Filter "*exec.jar" | Select-Object -First 1
$orderJar = Get-ChildItem (Join-Path $WorkDir "ticketflow-server/ticketflow-order-service/target") -Filter "*exec.jar" | Select-Object -First 1
if (-not $progJar -or -not $orderJar) { throw "未找到 exec.jar" }

Start-Process java -ArgumentList "-Xmx1g","-jar",$progJar.FullName `
    -RedirectStandardOutput (Join-Path $WorkDir "logs-program.out") `
    -RedirectStandardError (Join-Path $WorkDir "logs-program.err") -WindowStyle Hidden
Start-Process java -ArgumentList "-Xmx1g","-jar",$orderJar.FullName `
    -RedirectStandardOutput (Join-Path $WorkDir "logs-order.out") `
    -RedirectStandardError (Join-Path $WorkDir "logs-order.err") -WindowStyle Hidden

# 等待 program-service 健康
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:6086/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if ($ok) {
    Write-Host "  $Branch 服务已就绪 ✅（program-service :6086 健康）" -ForegroundColor Green
    Write-Host "  可以在 Mac 压测机上执行: bash benchmark/scripts/run-staircase.sh v5 \"80 120 160 200 300 400\" 60 3"
} else {
    Write-Host "  ⚠️ 服务未就绪，查看 logs-program.err / logs-order.err" -ForegroundColor Yellow
}
Pop-Location
