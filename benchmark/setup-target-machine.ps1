# =============================================================
# TicketFlow 被测机初始化脚本（Windows / PowerShell）
# 用途：在第二台 Windows 电脑上部署完整压测服务栈（被测机），
#       主电脑（macOS）作为压测机通过局域网访问。
#
# 用法（PowerShell 5.1 或 7，建议管理员运行以便执行防火墙放行）：
#   .\setup-target-machine.ps1 -RepoUrl https://github.com/zhentaozhang/ticketflow.git `
#                              -Branch v5-optimization `
#                              -WorkDir D:\ticketflow `
#                              -JasyptPassword <你的jasypt密码>
#
# 参数：
#   -RepoUrl         git 仓库地址（默认 HTTPS，避免 SSH key 配置）
#   -Branch          要 checkout 的分支（默认 v5-optimization，含 V5 优化）
#   -WorkDir         项目存放目录（默认当前目录下 ticketflow）
#   -JasyptPassword  应用配置的 JASYPT_ENCRYPTOR_PASSWORD（与 Mac 上一致）
#
# 前置：本机已安装 Docker Desktop（WSL2 后端）、JDK 17、Git、Maven
#       未安装时脚本会提示，不自动安装（避免静默安装风险）
#
# 注意：脚本含中文注释，若 PowerShell 5.1 显示乱码请用 UTF-8 BOM 重新保存
# =============================================================
[CmdletBinding()]
param(
    [string]$RepoUrl = "https://github.com/zhentaozhang/ticketflow.git",
    [string]$Branch = "v5-optimization",
    [string]$WorkDir = "",
    [string]$JasyptPassword = ""
)

$ErrorActionPreference = "Stop"

# ---------------- 0. 路径与参数 ---------------- 
if ([string]::IsNullOrEmpty($WorkDir)) {
    $WorkDir = Join-Path (Get-Location) "ticketflow"
}
$ProjectDir = $WorkDir

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host " TicketFlow 被测机初始化"
Write-Host " 仓库:   $RepoUrl"
Write-Host " 分支:   $Branch"
Write-Host " 目录:   $ProjectDir"
Write-Host "==============================================" -ForegroundColor Cyan

# ---------------- 1. 前置依赖检查 ---------------- 
function Test-Command($Name) { Get-Command $Name -ErrorAction SilentlyContinue }

Write-Host "[1/10] 检查依赖..."
$missing = @()
if (-not (Test-Command docker))  { $missing += "Docker Desktop (需 WSL2 后端, https://www.docker.com/products/docker-desktop/)" }
if (-not (Test-Command java))    { $missing += "JDK 17 (https://adoptium.net/)" }
if (-not (Test-Command git))     { $missing += "Git (https://git-scm.com/)" }
if (-not (Test-Command mvn))     { $missing += "Maven (https://maven.apache.org/)" }
if ($missing.Count -gt 0) {
    Write-Host "以下依赖未安装，请先安装后重跑：" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    exit 1
}
# 验证 JDK 版本 ≥17
$javaVer = (java -version 2>&1 | Select-Object -First 1) -replace '.*version "([^"]+)".*', '$1'
Write-Host "  docker OK | java $javaVer | git OK | mvn OK"

# ---------------- 2. 克隆项目（切优化分支） ----------------
Write-Host "[2/10] 克隆项目 (branch: $Branch)..."
if (-not (Test-Path (Join-Path $ProjectDir ".git"))) {
    New-Item -ItemType Directory -Force -Path $ProjectDir | Out-Null
    git clone --branch $Branch --single-branch $RepoUrl $ProjectDir
    if ($LASTEXITCODE -ne 0) { throw "git clone 失败（确认分支 $Branch 已 push 到远端）" }
} else {
    Write-Host "  目录已存在，拉取最新: $Branch"
    Push-Location $ProjectDir
    git fetch origin
    git checkout $Branch
    git pull origin $Branch
    Pop-Location
}

# ---------------- 3. 获取本机局域网 IP ----------------
Write-Host "[3/10] 获取本机局域网 IP..."
$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" } |
        Select-Object -First 1).IPAddress
if ([string]::IsNullOrEmpty($ip)) {
    # 备选：ipconfig 解析
    $ip = (ipconfig | Select-String "IPv4" | Select-Object -First 1) -replace '.*: ', ''
}
Write-Host "  本机局域网 IP: $ip  （Mac 压测机将用 TARGET_HOST=$ip 访问）" -ForegroundColor Green

# ---------------- 4. 修改 docker-compose 的 Kafka advertised listeners ----------------
Write-Host "[4/10] 修正 Kafka advertised listeners → $ip:9092 ..."
$compose = Join-Path $ProjectDir "docker/docker-compose.yml"
(Get-Content $compose -Raw) -replace 'PLAINTEXT://127\.0\.0\.1:9092', "PLAINTEXT://$ip`:9092" |
    Set-Content $compose -NoNewline
Write-Host "  已替换（Mac 压测机连 Kafka 9092 才能拿到可达地址；Windows 本机连自己 IP 同样通）"

# ---------------- 5. 启动中间件 ----------------
Write-Host "[5/10] 启动 MySQL/Redis/Kafka/Nacos/Prometheus..."
Push-Location (Join-Path $ProjectDir "docker")
# 首次启动前创建 external volume（compose 声明 external: true）
docker volume create docker_mysql-data 2>$null | Out-Null
docker volume create docker_redis-data 2>$null | Out-Null
docker volume create docker_kafka-data 2>$null | Out-Null
docker volume create docker_nacos-data 2>$null | Out-Null
docker volume create docker_prometheus-data 2>$null | Out-Null
docker compose up -d mysql redis kafka nacos prometheus
Pop-Location

# ---------------- 6. 等待 MySQL 健康 ----------------
Write-Host "[6/10] 等待 MySQL 就绪..."
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    $ping = docker exec ticketflow-mysql mysqladmin ping -uroot -proot 2>$null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw "MySQL 60s 内未就绪（docker ps 查看 ticketflow-mysql 状态）" }
Write-Host "  MySQL 就绪"

# ---------------- 7. 导入分库分表 SQL ----------------
Write-Host "[7/10] 导入 SQL（建库 → 建表+12 万座位数据）..."
$sqlDir = Join-Path $ProjectDir "sql/cloud"
$order = @(
    "1_ticketflow_cloud_create_database.sql",
    "ticketflow_base_data.sql",
    "ticketflow_user_0.sql", "ticketflow_user_1.sql",
    "ticketflow_program_0.sql", "ticketflow_program_1.sql",
    "ticketflow_order_0.sql", "ticketflow_order_1.sql",
    "ticketflow_pay_0.sql", "ticketflow_pay_1.sql",
    "ticketflow_customize.sql",
    "2_ticketflow_pay_alter.sql"
)
foreach ($f in $order) {
    $path = Join-Path $sqlDir $f
    if (-not (Test-Path $path)) { Write-Host "  跳过缺失: $f"; continue }
    Write-Host "  导入: $f"
    # 用 cmd 重定向保留原始字节（避免 PowerShell 管道改编码/换行破坏多语句 SQL）
    cmd /c "docker exec -i ticketflow-mysql mysql -uroot -proot --default-character-set=utf8mb4 < `"$path`""
    if ($LASTEXITCODE -ne 0) { throw "SQL 导入失败: $f" }
}
# 验证座位数据
$seatCount = (docker exec ticketflow-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM ticketflow_program_1.d_seat_1 WHERE program_id=9999" 2>$null)
Write-Host "  校验 d_seat_1(program=9999) 行数: $seatCount （压测脚本要求 12 万）" -ForegroundColor Green

# ---------------- 8. 构建服务（全量模块，产出 exec jar） ----------------
Write-Host "[8/10] Maven 构建（首次下载依赖约 5-15 分钟）..."
Push-Location $ProjectDir
mvn install -DskipTests -Dspotless.check.skip=true -q
if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
Pop-Location

# ---------------- 9. 启动 program-service / order-service ----------------
Write-Host "[9/10] 启动被测服务..."
if ([string]::IsNullOrEmpty($JasyptPassword)) {
    Write-Host "  ⚠️ 未提供 -JasyptPassword：服务可能因 jasypt 解密失败无法启动，请补参数重跑本步" -ForegroundColor Yellow
}
$env:JASYPT_ENCRYPTOR_PASSWORD = $JasyptPassword
$env:TZ = "Asia/Shanghai"

$progJar = Get-ChildItem (Join-Path $ProjectDir "ticketflow-server/ticketflow-program-service/target") -Filter "*exec.jar" | Select-Object -First 1
$orderJar = Get-ChildItem (Join-Path $ProjectDir "ticketflow-server/ticketflow-order-service/target") -Filter "*exec.jar" | Select-Object -First 1
if (-not $progJar -or -not $orderJar) { throw "未找到 exec.jar（检查第 8 步构建产物）" }

# 分窗口后台启动，日志落文件
Start-Process java -ArgumentList "-Xmx1g","-jar",$progJar.FullName -RedirectStandardOutput (Join-Path $ProjectDir "logs-program.out") -RedirectStandardError (Join-Path $ProjectDir "logs-program.err") -WindowStyle Hidden
Start-Process java -ArgumentList "-Xmx1g","-jar",$orderJar.FullName -RedirectStandardOutput (Join-Path $ProjectDir "logs-order.out") -RedirectStandardError (Join-Path $ProjectDir "logs-order.err") -WindowStyle Hidden
Write-Host "  服务已后台启动，日志: logs-program.out / logs-order.out"

# 等待服务健康（program 6086）
$progOk = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:6086/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $progOk = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if ($progOk) { Write-Host "  program-service :6086 健康 ✅" -ForegroundColor Green }
else { Write-Host "  ⚠️ program-service 未就绪，查看 logs-program.err" -ForegroundColor Yellow }

# ---------------- 10. topic 扩 48 分区 ----------------
Write-Host "[10/10] create_order topic 扩到 48 分区..."
# 先创建（若不存在，48 分区），已存在则 alter
docker exec ticketflow-kafka /opt/bitnami/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "ticketflow-create_order" --partitions 48 --replication-factor 1 2>$null | Out-Null
docker exec ticketflow-kafka /opt/bitnami/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --alter --topic "ticketflow-create_order" --partitions 48 2>$null | Out-Null
docker exec ticketflow-kafka /opt/bitnami/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --describe --topic "ticketflow-create_order" 2>$null | Select-String "PartitionCount" | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

# ---------------- 防火墙放行（需管理员） ----------------
Write-Host "[可选] 防火墙放行局域网端口（需管理员运行本脚本）..."
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($admin) {
    foreach ($port in 6086,8081,3306,6379,9092,8848,9090) {
        netsh advfirewall firewall add rule name="ticketflow-$port" dir=in action=allow protocol=TCP localport=$port 2>$null | Out-Null
    }
    Write-Host "  已放行 6086/8081/3306/6379/9092/8848/9090（专用+公用网络）" -ForegroundColor Green
} else {
    Write-Host "  非管理员：请手动在 Windows 防火墙放行 6086/8081/3306/6379/9092/8848/9090 入站" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "================ 完成 ================" -ForegroundColor Cyan
Write-Host " 被测机 IP : $ip"
Write-Host " 在 Mac 压测机上执行："
Write-Host "   export TARGET_HOST=$ip"
Write-Host "   bash benchmark/scripts/run-single.sh v5 0 30 openloop 40 1   # 冒烟"
Write-Host " 确认 Mac 能连通（任选）:"
Write-Host "   curl http://$ip`:6086/actuator/health"
Write-Host "   mysql -h$ip -uroot -proot -e 'select 1'"
Write-Host "=====================================" -ForegroundColor Cyan
