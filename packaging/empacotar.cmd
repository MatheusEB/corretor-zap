@echo off
rem Gera o app portátil do Windows: dist\Corretor Zap\Corretor Zap.exe (com Java embutido) e dist\CorretorZap-windows.zip
rem Uso: packaging\empacotar.cmd   (a partir da raiz do projeto; precisa de JDK 21+ e Node)
setlocal
cd /d "%~dp0.."

echo [1/4] Painel React
pushd frontend
call npm ci || exit /b 1
call npm run build || exit /b 1
popd

echo [2/4] Backend Spring Boot (com testes)
pushd backend
call .\mvnw.cmd -q clean package || exit /b 1
popd

echo [3/4] jpackage
if exist dist rmdir /s /q dist
mkdir dist\entrada
copy /y backend\target\corretor-zap-0.0.1-SNAPSHOT.jar dist\entrada\corretor-zap.jar >nul
jpackage --type app-image ^
  --name "Corretor Zap" ^
  --app-version 0.4.0 ^
  --vendor "Corretor Zap" ^
  --description "Correcao ortografica com um atalho, direto no WhatsApp" ^
  --icon packaging\icone.ico ^
  --input dist\entrada ^
  --main-jar corretor-zap.jar ^
  --java-options "-Xmx512m -XX:+UseStringDeduplication" ^
  --dest dist || exit /b 1
rmdir /s /q dist\entrada

echo [4/4] Zip
powershell -NoProfile -Command "Compress-Archive -Path 'dist\Corretor Zap' -DestinationPath 'dist\CorretorZap-windows.zip' -Force" || exit /b 1
echo Pronto: dist\Corretor Zap\Corretor Zap.exe
