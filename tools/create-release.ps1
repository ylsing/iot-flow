# 创建 GitHub Release 并上传 APK（PowerShell 7）
#
# 用法（在仓库根目录）：
#   pwsh -File tools/create-release.ps1
#   或指定参数：pwsh -File tools/create-release.ps1 -Tag v1.0.0 -Apk dist/iot-flow-v1.0.0.apk
#
# 依赖：本机已用 git 存过 github.com 的凭据（git push 能成功即可）——脚本会用
#       `git credential fill` 取出令牌，不会回显，也不会写进任何文件。

param(
  [string]$Repo = "ylsing/iot-flow",
  [string]$Tag  = "v1.0.0",
  [string]$Name = "小鲸查询 v1.0.0（首版）",
  [string]$Apk  = "dist/iot-flow-v1.0.0.apk",
  [string]$NotesFile = "RELEASE_NOTES_$($Tag).md",
  [string]$Proxy = "http://127.0.0.1:7897"   # 直连可用就传 -Proxy ""
)

$ErrorActionPreference = "Stop"

# --- 1. 取令牌 ---
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$user = ($cred | Select-String '^username=(.*)$').Matches.Groups[1].Value
$token = ($cred | Select-String '^password=(.*)$').Matches.Groups[1].Value
if (-not $token) { throw "没取到 GitHub 令牌，先确认 git push 能成功（凭据管理器已存）" }
Write-Host "已获取 $user 的令牌（长度 $($token.Length)）"

$headers = @{
  Authorization          = "Bearer $token"
  Accept                 = "application/vnd.github+json"
  "X-GitHub-Api-Version" = "2022-11-28"
}
$proxyArg = @{}
if ($Proxy) { $proxyArg["Proxy"] = $Proxy }

if (-not (Test-Path $Apk)) { throw "找不到 APK：$Apk（先跑 iot-flow-app/build_apk.sh）" }

# --- 2. 读说明 ---
$body = ""
if (Test-Path $NotesFile) { $body = Get-Content $NotesFile -Raw -Encoding utf8 }

# --- 3. 建发布 ---
$payload = @{ tag_name = $Tag; name = $Name; body = $body; draft = $false; prerelease = $false } |
           ConvertTo-Json -Depth 5
$rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" `
  -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -ContentType "application/json; charset=utf-8" @proxyArg
Write-Host "发布已创建：$($rel.html_url)"

# --- 4. 传附件 ---
$fileName = Split-Path $Apk -Leaf
$uploadUri = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$fileName"
$asset = Invoke-RestMethod -Method Post -Uri $uploadUri -Headers $headers `
  -InFile $Apk -ContentType "application/vnd.android.package-archive" @proxyArg
Write-Host "附件已上传：$($asset.browser_download_url)（$([math]::Round($asset.size/1KB,1)) KB）"
