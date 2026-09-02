# 重启 admin A（8080，loadtest profile），原样复刻被 kill 前的启动命令。
# classpath 用备份的 spring-boot argfile（java @file 语义会自动剥引号）。
$cpFile = 'D:\javacode\ww-job\tools\logs\adminA.argfile.backup'
$out = 'D:\javacode\ww-job\tools\logs\adminA_restart.log'
$err = 'D:\javacode\ww-job\tools\logs\adminA_restart.err.log'
$java = 'D:\Program\wwjdk21\bin\java.exe'
$argList = @(
  '-XX:TieredStopAtLevel=1', '-Xmx512m', '-Xms128m',
  '-cp', "@$cpFile",
  'com.wwjob.admin.WwJobAdminApplication', '--spring.profiles.active=loadtest'
)
Start-Process -FilePath $java -ArgumentList $argList -WindowStyle Hidden `
  -RedirectStandardOutput $out -RedirectStandardError $err
Write-Output "launched admin A, see $out / $err"
