@echo off
rem Inicia o Corretor Zap a partir do código (abre a janela do app e o ícone na bandeja).
rem Para a versão com .exe e Java embutido, use packaging\empacotar.cmd.
rem -Xmx512m: o limite que passou no teste de estresse com folga (256 MB dá OutOfMemory com textos grandes)
cd /d "%~dp0"
if not exist backend\src\main\resources\static\index.html (
  echo Gerando o painel...
  pushd frontend
  call npm ci || exit /b 1
  call npm run build || exit /b 1
  popd
)
cd backend
if not exist target\corretor-zap-0.0.1-SNAPSHOT.jar (
  echo Compilando pela primeira vez...
  call .\mvnw.cmd -q package -DskipTests || exit /b 1
)
start "" javaw -Xmx512m -XX:+UseStringDeduplication -jar target\corretor-zap-0.0.1-SNAPSHOT.jar
