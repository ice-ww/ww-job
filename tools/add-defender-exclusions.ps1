# -*- coding: utf-8 -*-
# 压测前加 Windows Defender 实时扫描目录排除（§2.3 第 1 条）
# 需管理员运行；结果写入同目录 defender-exclusions.log
$ErrorActionPreference = 'Stop'
$log = Join-Path $PSScriptRoot 'defender-exclusions.log'
$paths = @('D:\Program\wwjdk21', 'D:\javacode\ww-job', "$env:USERPROFILE\.m2")

try {
    $existing = @(Get-MpPreference | Select-Object -ExpandProperty ExclusionPath)
    $added = @()
    foreach ($p in $paths) {
        if ($existing -contains $p) {
            "skip (already excluded): $p" | Out-File $log -Append -Encoding utf8
        } else {
            Add-MpPreference -ExclusionPath $p
            $added += $p
            "added: $p" | Out-File $log -Append -Encoding utf8
        }
    }
    "--- final exclusion list ---" | Out-File $log -Append -Encoding utf8
    Get-MpPreference | Select-Object -ExpandProperty ExclusionPath | Out-File $log -Append -Encoding utf8
    "OK: done" | Out-File $log -Append -Encoding utf8
} catch {
    "ERROR: $($_.Exception.Message)" | Out-File $log -Append -Encoding utf8
    exit 1
}
