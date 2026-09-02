@echo off
rem Relaunch executor 8081 from argfile backup (Phase 9).
"D:\Program\wwjdk21\bin\java.exe" -XX:TieredStopAtLevel=1 -Xmx512m -Xms128m -cp @D:\javacode\ww-job\tools\logs\executor.argfile.backup com.wwjob.executor.samples.WwJobExecutorSamplesApplication >> D:\javacode\ww-job\tools\logs\executor_restart.log 2>> D:\javacode\ww-job\tools\logs\executor_restart.err.log
