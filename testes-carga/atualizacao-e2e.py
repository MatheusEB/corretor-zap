r"""Atualização de ponta a ponta: app 0.4.0 instalado numa pasta de teste, GitHub falso servindo a 0.4.1.

Não mexe no Corretor Zap que você usa: roda outra cópia na porta 8767, com dados numa pasta própria.
Prepare a pasta (Git Bash, na raiz do projeto, depois de "mvnw package"):

    P=/c/temp/e2e-update; mkdir -p $P/entrada $P/instalado $P/github/download/v0.4.1 $P/dados
    cp backend/target/corretor-zap-0.0.1-SNAPSHOT.jar $P/entrada/corretor-zap.jar
    for v in 0.4.0 0.4.1; do jpackage --type app-image --name "Corretor Zap" --app-version $v \
        --input $P/entrada --main-jar corretor-zap.jar --dest $P/v${v//./}; done
    cp -r "$P/v040/Corretor Zap" $P/instalado/
    powershell -Command "Compress-Archive 'C:/temp/e2e-update/v041/Corretor Zap' C:/temp/e2e-update/github/download/v0.4.1/CorretorZap-windows.zip"

Uso: python testes-carga/atualizacao-e2e.py C:/temp/e2e-update   (Playwright + Microsoft Edge)
"""
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright

S = Path(sys.argv[1])
APP = S / "instalado" / "Corretor Zap"
ZIP = S / "github" / "download" / "v0.4.1" / "CorretorZap-windows.zip"
DADOS = S / "dados"
PORTA = 8767
BASE = f"http://localhost:{PORTA}"
SHOTS = S / "telas"
SHOTS.mkdir(exist_ok=True)

conteudo_zip = ZIP.read_bytes()
digest = hashlib.sha256(conteudo_zip).hexdigest()
downloads = []


class GitHubFalso(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        porta = self.server.server_address[1]
        if self.path == "/repos/x/releases/latest":
            corpo = json.dumps({
                "tag_name": "v0.4.1",
                "html_url": "https://github.com/x/releases/tag/v0.4.1",
                "body": "## Novidades\n\n### ⚡ Teste de atualização\nVersão **0.4.1** gerada pelo teste de ponta a ponta.",
                "assets": [{
                    "name": "CorretorZap-windows.zip", "size": len(conteudo_zip), "digest": "sha256:" + digest,
                    "browser_download_url": f"http://127.0.0.1:{porta}/download/v0.4.1/CorretorZap-windows.zip",
                }],
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(corpo)))
            self.end_headers()
            self.wfile.write(corpo)
        elif self.path == "/download/v0.4.1/CorretorZap-windows.zip":
            downloads.append(time.time())
            self.send_response(200)
            self.send_header("Content-Length", str(len(conteudo_zip)))
            self.end_headers()
            # em pedaços, para a barra de progresso ter o que mostrar
            for i in range(0, len(conteudo_zip), 4 * 1024 * 1024):
                self.wfile.write(conteudo_zip[i:i + 4 * 1024 * 1024])
                time.sleep(0.05)
        else:
            self.send_response(404)
            self.end_headers()


github = ThreadingHTTPServer(("127.0.0.1", 0), GitHubFalso)
threading.Thread(target=github.serve_forever, daemon=True).start()
gh = f"http://127.0.0.1:{github.server_address[1]}"


def estado():
    try:
        with urllib.request.urlopen(BASE + "/api/atualizacao", timeout=2) as r:
            return json.load(r)
    except Exception:
        return None


def esperar(condicao, segundos, oque):
    limite = time.time() + segundos
    while time.time() < limite:
        valor = condicao()
        if valor:
            return valor
        time.sleep(0.3)
    raise AssertionError("não aconteceu: " + oque)


argumentos = [
    "--corretor.demo=true", f"--server.port={PORTA}", f"--corretor.config-dir={DADOS}",
    "--corretor.atalho.habilitado=false", "--corretor.janela.habilitada=false",
    f"--corretor.origens-permitidas={BASE},http://127.0.0.1:{PORTA}",
    f"--corretor.atualizacao.api={gh}/repos/x/releases/latest",
    f"--corretor.atualizacao.downloads={gh}/download/",
]
subprocess.Popen([str(APP / "Corretor Zap.exe"), *argumentos], creationflags=subprocess.DETACHED_PROCESS)
antes = esperar(estado, 60, "o app 0.4.0 abrir")
pid_antigo = subprocess.run(["powershell", "-NoProfile", "-Command",
                             f"(Get-NetTCPConnection -LocalPort {PORTA} -State Listen).OwningProcess"],
                            capture_output=True, text=True).stdout.strip()
print("aberto:", antes["atual"], antes["fase"], "pid", pid_antigo)
assert antes["atual"] == "0.4.0"

ok = True
with sync_playwright() as p:
    nav = p.chromium.launch(channel="msedge")
    pagina = nav.new_page(viewport={"width": 1200, "height": 800})
    pagina.goto(BASE)
    pagina.get_by_test_id("versao-app").wait_for()
    print("barra lateral:", pagina.get_by_test_id("versao-app").inner_text())

    pagina.get_by_role("button", name="Atalho").click()
    pagina.get_by_role("button", name="Procurar agora").click()
    aviso = pagina.get_by_test_id("aviso-atualizacao")
    aviso.wait_for(timeout=10000)
    print("aviso:", aviso.inner_text().splitlines()[0])
    print("página Atalho:", pagina.get_by_test_id("situacao-atualizacao").inner_text())
    aviso.get_by_role("button", name="Novidades").click()
    novidades = aviso.inner_text()
    assert "Teste de atualização" in novidades and "##" not in novidades and "**" not in novidades, novidades
    pagina.screenshot(path=str(SHOTS / "1-disponivel.png"))

    inicio = time.time()
    aviso.get_by_role("button", name="Atualizar", exact=True).click()
    viu_progresso = False
    try:
        pagina.get_by_role("progressbar").wait_for(timeout=5000)
        viu_progresso = True
        pagina.screenshot(path=str(SHOTS / "2-baixando.png"))
    except Exception:
        pass
    print("barra de progresso apareceu:", viu_progresso)

    # o app fecha, o script troca a pasta e abre de novo com os mesmos argumentos
    esperar(lambda: estado() is None, 60, "o app 0.4.0 fechar")
    print(f"app fechou {time.time() - inicio:.1f} s depois do clique")
    depois = esperar(lambda: (e := estado()) and e["atual"] == "0.4.1" and e, 120, "o app voltar na 0.4.1")
    print(f"voltou na {depois['atual']} {time.time() - inicio:.1f} s depois do clique; fase {depois['fase']}")

    # a aba que já estava aberta percebe sozinha que o app voltou na versão nova
    pagina.get_by_text("Atualizado para a versão 0.4.1").wait_for(timeout=10000)
    print("toast: Atualizado para a versão 0.4.1")
    print("barra lateral:", pagina.get_by_test_id("versao-app").inner_text())
    pagina.screenshot(path=str(SHOTS / "3-atualizado.png"))
    pagina.goto(BASE)
    pagina.get_by_test_id("versao-app").wait_for()
    time.sleep(1)
    assert pagina.get_by_text("Atualizado para a versão").count() == 0, "o aviso apareceu de novo"
    print("aviso de atualizado não se repete: ok")
    nav.close()

cfg = (APP / "app" / "Corretor Zap.cfg").read_text()
print("cfg instalado:", [l for l in cfg.splitlines() if "app-version" in l][0])
sobras = [n for n in os.listdir(APP.parent) if n != "Corretor Zap"]
print("sobras ao lado da pasta:", sobras)
assert not sobras
print("downloads feitos:", len(downloads))
print("tempos:", [l.split(" : ")[-1] for l in (DADOS / "logs" / "corretor-zap.log").read_text(encoding="utf-8").splitlines() if "Atualização: download" in l])
print("--- atualizacao.log")
print((DADOS / "logs" / "atualizacao.log").read_text(encoding="utf-8-sig"))
pid_novo = subprocess.run(["powershell", "-NoProfile", "-Command",
                           f"(Get-NetTCPConnection -LocalPort {PORTA} -State Listen).OwningProcess"],
                          capture_output=True, text=True).stdout.strip()
print("pid antigo", pid_antigo, "pid novo", pid_novo)
subprocess.run(["taskkill", "/PID", pid_novo, "/F"], capture_output=True)
github.shutdown()
print("TUDO OK")
