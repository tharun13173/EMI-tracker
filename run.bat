@echo off
cd /d "%~dp0"
where javac >nul 2>nul || (echo Please install a JDK 17 or newer first. & exit /b 1)
if not exist out mkdir out
javac -d out src\branch\*.java || exit /b 1
java -cp "out;lib\sqlite-jdbc.jar" branch.Main
