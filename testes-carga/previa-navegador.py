"""Testa o cartão da prévia (frontend/src/previa) num navegador de verdade, com uma ponte falsa no lugar do Java.

Uso: com o painel rodando (cd frontend && npm run dev), rode
    python testes-carga/previa-navegador.py [http://localhost:5173/?previa=1]
Precisa do Playwright para Python (pip install playwright); usa o Edge que já vem no Windows.
"""
import json
import random
import sys
import time

from playwright.sync_api import sync_playwright, expect

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5173/?previa=1"

PONTE_FALSA = """
window.chamadas = [];
window.ponte = {
  tamanho: (l, a) => window.chamadas.push(['tamanho', l, a, performance.now()]),
  aplicar: (j) => window.chamadas.push(['aplicar', j]),
  cancelar: () => window.chamadas.push(['cancelar']),
  tom: (n) => window.chamadas.push(['tom', n]),
};
"""


def corr(original, trecho, sugestao, certeza=True, regra="MORFOLOGIK_RULE_PT_BR", de=0):
    # posições em UTF-16, como o Java e o JavaScript contam (um emoji ocupa 2)
    u16 = lambda t: len(t.encode("utf-16-le")) // 2
    inicio = u16(original[:original.index(trecho, de)])
    return dict(inicio=inicio, fim=inicio + u16(trecho), trecho=trecho, sugestao=sugestao, mensagem="",
                regra=regra, descricaoRegra="Ortografia", certeza=certeza)


def resultado(original, correcoes):
    texto = original.encode("utf-16-le")
    for c in sorted(correcoes, key=lambda c: -c["inicio"]):
        if c["certeza"]:
            texto = texto[:2 * c["inicio"]] + c["sugestao"].encode("utf-16-le") + texto[2 * c["fim"]:]
    return dict(original=original, corrigido=texto.decode("utf-16-le"), correcoes=correcoes)


ORIGINAL = "voce nao vai acreditar oque aconteceu"
LOCAL = resultado(ORIGINAL, [corr(ORIGINAL, "voce", "Você"), corr(ORIGINAL, "nao", "não")])
IA = resultado(ORIGINAL, [corr(ORIGINAL, "voce", "Você", regra="CZ_IA"), corr(ORIGINAL, "nao", "não", regra="CZ_IA"),
                          corr(ORIGINAL, "oque", "o que", regra="CZ_IA"), corr(ORIGINAL, "aconteceu", "aconteceu!", regra="CZ_IA")])

falhas = []
resultados = []


def caso(nome):
    def decorador(f):
        def rodar(page):
            page.evaluate("window.chamadas = []")
            try:
                f(page)
                resultados.append(("ok", nome))
            except Exception as e:  # noqa: BLE001
                falhas.append(nome)
                resultados.append(("FALHOU", f"{nome}: {e}"))
        rodar.nome = nome
        return rodar
    return decorador


def mostrar(page, res=LOCAL, ia="revisando", tons=True):
    page.evaluate("d => window.mostrarPrevia(d)", dict(resultado=res, escuro=False, ia=ia, tons=tons))


def chamadas(page, tipo):
    return [c for c in page.evaluate("window.chamadas") if c[0] == tipo]


def texto_previa(page):
    return page.get_by_test_id("texto-previa").inner_text()


@caso("abre na hora com o corretor local e o selo 'IA revisando'")
def t1(page):
    mostrar(page)
    expect(page.get_by_test_id("selo-ia")).to_contain_text("IA revisando")
    expect(page.get_by_text("2 de 2 correções")).to_be_visible()
    assert chamadas(page, "tamanho"), "a janela nativa não recebeu o tamanho"


@caso("a resposta da IA troca o conteúdo quando a pessoa não mexeu")
def t2(page):
    mostrar(page)
    page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=IA, ia="pronta"))
    expect(page.get_by_text("4 de 4 correções")).to_be_visible()
    expect(page.get_by_test_id("selo-ia")).to_have_text("IA")


@caso("se a pessoa já mexeu, a IA espera num aviso e só troca com um clique")
def t3(page):
    mostrar(page)
    page.locator("[aria-pressed]").filter(has_text="nao").first.click()
    expect(page.get_by_text("1 de 2 correções")).to_be_visible()
    page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=IA, ia="pronta"))
    expect(page.get_by_text("1 de 2 correções")).to_be_visible()
    aviso = page.get_by_text("A IA revisou a mensagem")
    expect(aviso).to_be_visible()
    aviso.click()
    expect(page.get_by_text("4 de 4 correções")).to_be_visible()
    expect(aviso).to_have_count(0)


@caso("Enter aplica e manda texto, aceitas e recusadas para o Java")
def t4(page):
    mostrar(page, ia="desligada")
    page.locator("[aria-pressed]").filter(has_text="nao").first.click()
    page.keyboard.press("Enter")
    aplicadas = chamadas(page, "aplicar")
    assert len(aplicadas) == 1, aplicadas
    decisao = json.loads(aplicadas[0][1])
    assert decisao["texto"] == "Você nao vai acreditar oque aconteceu", decisao["texto"]
    assert [c["trecho"] for c in decisao["aceitas"]] == ["voce"], decisao
    assert [c["trecho"] for c in decisao["recusadas"]] == ["nao"], decisao


@caso("decisaoDaPrevia (usada pelo Enter do lado do Java) devolve o mesmo JSON")
def t5(page):
    mostrar(page, ia="desligada")
    decisao = json.loads(page.evaluate("window.decisaoDaPrevia()"))
    assert decisao["texto"] == LOCAL["corrigido"], decisao
    assert len(decisao["aceitas"]) == 2 and decisao["recusadas"] == []


@caso("Esc cancela")
def t6(page):
    mostrar(page)
    page.keyboard.press("Escape")
    assert len(chamadas(page, "cancelar")) == 1


@caso("IA sem nada a corrigir: 'Tudo certo' e a prévia fecha sozinha")
def t7(page):
    sem = resultado(ORIGINAL, [])
    mostrar(page, res=sem)
    expect(page.get_by_test_id("selo-ia")).to_contain_text("IA revisando")
    page.wait_for_timeout(1500)
    assert not chamadas(page, "cancelar"), "fechou enquanto a IA ainda revisava"
    page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=sem, ia="pronta"))
    expect(page.get_by_text("Tudo certo")).to_be_visible()
    page.wait_for_timeout(1400)
    assert len(chamadas(page, "cancelar")) == 1


@caso("IA falhou: fica a correção local com o selo 'Sem IA'")
def t8(page):
    mostrar(page)
    page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=LOCAL, ia="falhou"))
    expect(page.get_by_test_id("selo-ia")).to_have_text("Sem IA")
    expect(page.get_by_text("2 de 2 correções")).to_be_visible()


@caso("botão de tom pede ao Java, trava enquanto carrega e mostra a reescrita")
def t9(page):
    mostrar(page)
    page.get_by_role("button", name="Formal").click()
    assert [c[1] for c in chamadas(page, "tom")] == ["FORMAL"]
    expect(page.get_by_role("button", name="Curto")).to_be_disabled()
    formal_txt = "Você não vai acreditar no que aconteceu."
    formal = resultado(ORIGINAL, [dict(inicio=0, fim=len(ORIGINAL), trecho=ORIGINAL, sugestao=formal_txt, mensagem="",
                                       regra="CZ_IA", descricaoRegra="Revisão da IA", certeza=True)])
    page.evaluate("d => window.mostrarTom(d)", dict(tom="FORMAL", resultado=formal))
    expect(page.get_by_text("Tom Formal")).to_be_visible()
    expect(page.get_by_role("button", name="Curto")).to_be_enabled()
    # a revisão da IA que chega depois não desfaz o tom escolhido
    page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=IA, ia="pronta"))
    expect(page.get_by_text("Tom Formal")).to_be_visible()
    expect(page.get_by_text("1 de 1 correção")).to_be_visible()


@caso("erro no tom aparece no cartão e libera os botões")
def t10(page):
    mostrar(page)
    page.get_by_role("button", name="Gentil").click()
    page.evaluate("d => window.mostrarTom(d)", dict(tom="GENTIL", erro="Limite de uso da IA atingido (429)"))
    expect(page.get_by_text("Limite de uso da IA atingido")).to_be_visible()
    expect(page.get_by_role("button", name="Gentil")).to_be_enabled()


@caso("Alt+2 pede o tom curto pelo teclado")
def t11(page):
    mostrar(page)
    page.keyboard.press("Alt+2")
    assert [c[1] for c in chamadas(page, "tom")] == ["CURTO"]


@caso("sem IA configurada não aparecem botões de tom")
def t12(page):
    mostrar(page, ia="desligada", tons=False)
    expect(page.get_by_role("button", name="Formal")).to_have_count(0)
    expect(page.get_by_test_id("selo-ia")).to_have_count(0)


@caso("uma prévia nova zera o tom e o aviso da anterior")
def t13(page):
    mostrar(page)
    page.locator("[aria-pressed]").filter(has_text="nao").first.click()
    page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=IA, ia="pronta"))
    mostrar(page)
    expect(page.get_by_text("A IA revisou a mensagem")).to_have_count(0)
    expect(page.get_by_text("2 de 2 correções")).to_be_visible()


@caso("duvidosas chegam desmarcadas e um clique aplica")
def t14(page):
    orig = "falei com o zezinho"
    res = resultado(orig, [corr(orig, "zezinho", "Zezinho", certeza=False)])
    mostrar(page, res=res, ia="desligada")
    expect(page.get_by_text("0 de 1 correção")).to_be_visible()
    page.locator("[aria-pressed]").first.click()
    page.keyboard.press("Enter")
    assert json.loads(chamadas(page, "aplicar")[0][1])["texto"] == "falei com o Zezinho"


@caso("texto com quebras de linha, emojis e acentos chega intacto")
def t15(page):
    orig = "oi mae 😊\r\nvoce vem?\r\nbjs ❤️"
    res = resultado(orig, [corr(orig, "mae", "mãe"), corr(orig, "voce", "você")])
    mostrar(page, res=res, ia="desligada")
    page.keyboard.press("Enter")
    assert json.loads(chamadas(page, "aplicar")[0][1])["texto"] == "oi mãe 😊\r\nvocê vem?\r\nbjs ❤️"


@caso("estresse: 300 prévias e respostas da IA seguidas, sem erro e terminando no estado certo")
def t16(page):
    rnd = random.Random(42)
    for i in range(300):
        orig = f"mensagem {i} voce nao vem"
        local = resultado(orig, [corr(orig, "voce", "você")])
        ia = resultado(orig, [corr(orig, "voce", "você", regra="CZ_IA"), corr(orig, "nao", "não", regra="CZ_IA")])
        mostrar(page, res=local, ia=rnd.choice(["revisando", "desligada"]))
        if rnd.random() < 0.7:
            page.evaluate("d => window.atualizarPrevia(d)", dict(resultado=ia, ia=rnd.choice(["pronta", "falhou"])))
    orig = "final voce nao vem"
    mostrar(page, res=resultado(orig, [corr(orig, "voce", "você")]))
    page.evaluate("d => window.atualizarPrevia(d)", dict(
        resultado=resultado(orig, [corr(orig, "voce", "você", regra="CZ_IA"), corr(orig, "nao", "não", regra="CZ_IA")]),
        ia="pronta"))
    expect(page.get_by_text("2 de 2 correções")).to_be_visible()
    page.keyboard.press("Enter")
    assert json.loads(chamadas(page, "aplicar")[-1][1])["texto"] == "final você não vem"


@caso("velocidade: da chamada do Java até o cartão medido (média de 50 aberturas)")
def t17(page):
    tempos = []
    for i in range(50):
        orig = f"teste {i} voce nao vai acreditar oque aconteceu hoje de manha"
        res = resultado(orig, [corr(orig, "voce", "Você"), corr(orig, "nao", "não"), corr(orig, "oque", "o que"),
                               corr(orig, "manha", "manhã")])
        page.evaluate("window.chamadas = []")
        inicio = page.evaluate("performance.now()")
        mostrar(page, res=res)
        page.wait_for_function("window.chamadas.some(c => c[0] === 'tamanho')")
        fim = page.evaluate("window.chamadas.find(c => c[0] === 'tamanho')[3]")
        tempos.append(fim - inicio)
    tempos.sort()
    media = sum(tempos) / len(tempos)
    resultados.append(("info", f"tempo até o cartão: média {media:.1f} ms, mediana {tempos[25]:.1f} ms, pior {tempos[-1]:.1f} ms"))
    assert tempos[25] < 50, f"mediana {tempos[25]:.1f} ms"


TESTES = [t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17]

with sync_playwright() as p:
    navegador = p.chromium.launch(channel="msedge")
    pagina = navegador.new_page()
    erros_console = []
    pagina.on("console", lambda m: erros_console.append(m.text) if m.type == "error" else None)
    pagina.on("pageerror", lambda e: erros_console.append(str(e)))
    pagina.add_init_script(PONTE_FALSA)
    pagina.goto(URL)
    pagina.wait_for_function("typeof window.mostrarPrevia === 'function'")
    for teste in TESTES:
        teste(pagina)
    navegador.close()

for estado, nome in resultados:
    print(f"[{estado}] {nome}")
if erros_console:
    print("ERROS NO CONSOLE:", *erros_console[:10], sep="\n  ")
print(f"\n{len(TESTES) - len(falhas)} de {len(TESTES)} passaram")
sys.exit(1 if falhas or erros_console else 0)
