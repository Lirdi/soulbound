@echo off
cd /d "%~dp0"
echo Building Soulbound... (the first build downloads Gradle and Paper, so it takes a few minutes)
call gradlew.bat build
echo.
if exist build\libs\Soulbound-0.1.0.jar (
  echo Done! Your plugin is in build\libs\Soulbound-0.1.0.jar
  explorer build\libs
) else (
  echo Something went wrong. Check Java 25 is installed: run "java -version" in a new terminal.
)
pause
