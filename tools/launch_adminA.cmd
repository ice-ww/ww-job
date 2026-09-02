@echo off
rem Restart admin A (8080, loadtest) exactly as the killed process was launched.
"D:\Program\wwjdk21\bin\java.exe" -XX:TieredStopAtLevel=1 -Xmx512m -Xms128m -cp @D:\javacode\ww-job\tools\logs\adminA.argfile.backup com.wwjob.admin.WwJobAdminApplication --spring.profiles.active=loadtest >> D:\javacode\ww-job\tools\logs\adminA_restart.log 2>> D:\javacode\ww-job\tools\logs\adminA_restart.err.log
