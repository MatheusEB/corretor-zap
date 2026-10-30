<div align="center">

<img src=".github/assets/banner.svg" alt="Corretor Zap: correção ortográfica e pontuação para o WhatsApp com um atalho" width="100%">

<br>

![Java](https://img.shields.io/badge/Java-21-1C1A17?style=for-the-badge&logo=openjdk&logoColor=FBFAF6)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-1C1A17?style=for-the-badge&logo=springboot&logoColor=FBFAF6)
![React](https://img.shields.io/badge/React-18-1C1A17?style=for-the-badge&logo=react&logoColor=FBFAF6)
![LanguageTool](https://img.shields.io/badge/LanguageTool-pt--BR-1C1A17?style=for-the-badge&logoColor=FBFAF6)
![IA opcional](https://img.shields.io/badge/IA_opcional-Gemini_·_Ollama-1C1A17?style=for-the-badge&logo=googlegemini&logoColor=FBFAF6)
<br>
![Versão](https://img.shields.io/badge/versão-0.3.0-0F6E64?style=flat-square)
![Testes](https://img.shields.io/badge/testes-100-0F6E64?style=flat-square)
![Licença](https://img.shields.io/badge/licença-MIT-0F6E64?style=flat-square)
![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011-C8412B?style=flat-square&logo=windows)

**Escreveu, apertou, corrigiu.**
Corretor de ortografia e pontuação para o WhatsApp Desktop e Web: escreva a mensagem,
aperte `Ctrl + Shift + Espaço` e confira a prévia antes de enviar. Roda inteiro no seu computador e,
se você quiser, uma IA (Gemini grátis ou Ollama local) revisa junto.

<a href="https://github.com/MatheusEB/corretor-zap/releases/latest"><img src="https://img.shields.io/badge/Baixar_para_Windows-CorretorZap--windows.zip-0F6E64?style=for-the-badge&logo=windows&logoColor=FBFAF6" alt="Baixar para Windows"></a>

</div>

---

<div align="center">
  <img src=".github/assets/demo.gif" alt="Passeio pelo app: Início com sublinhado ao vivo, Atalho, Dicionário e Histórico" width="92%">
  <br>
  <sub>Telas com os <b>dados de demonstração</b> (<code>--corretor.demo=true</code>). Nenhuma conversa de verdade aparece aqui.</sub>
</div>

## Do rascunho à mensagem enviada

<img src=".github/assets/fluxo.svg" alt="Escreva no WhatsApp → aperte Ctrl+Shift+Espaço → confira a prévia e aperte Enter → a mensagem fica corrigida" width="100%">

| | Etapa | O que o programa faz |
|---|---|---|
| ✍️ | **Escrever** | Você digita no WhatsApp normalmente, sem colar o texto em lugar nenhum. |
| ⌨️ | **Atalho** | `Ctrl + Shift + Espaço` copia o texto da caixa em foco. A sua área de transferência é guardada e devolvida no fim. |
| 🔍 | **Prévia** | Um cartão flutua por cima do WhatsApp com cada correção marcada (riscado → novo). Clique numa correção para desfazê-la. Ele aparece na hora com o corretor local; com a [revisão por IA](#-revisão-por-ia-opcional) ligada, a resposta dela atualiza o cartão quando chega, com a etiqueta **IA**. |
| ✅ | **Aplicar** | `Enter` devolve o foco ao WhatsApp e troca o texto pela versão corrigida. `Esc` cancela sem mexer em nada. |

<div align="center">
  <img src=".github/assets/previa.gif" alt="Cartão de prévia com as correções marcadas por cima do WhatsApp" width="60%">
</div>

## Destaques

<table>
<tr>
<td width="50%" valign="top">

### ✏️ Ortografia e acentos
O [LanguageTool](https://languagetool.org) em português do Brasil, ajustado para conversa: `voce` → `você`, `nao` → `não`, `porisso` → `por isso`, sem "corrigir" risada nem gíria. Entre as sugestões, o `Ranqueador` escolhe a mais parecida com o erro (acento esquecido, tecla vizinha, `chatu` → `chato`) e, na dúvida, só sugere: a prévia mostra desmarcada.

### 🔤 Pontuação de chat
Uma camada própria (`Pontuador`) põe vírgula depois de saudação e vocativo (`oi joao tudo bem` → `Oi, João, tudo bem?`), ponto final e interrogação. Cada tipo liga e desliga na página Atalho.

### 📖 Dicionário seu
Gírias, apelidos e nomes que o corretor nunca deve mexer. Também dá para desligar uma regra direto pelo cartão da sugestão. E ele aprende sozinho: a palavra que você desfaz 3 vezes na prévia entra no dicionário.

</td>
<td width="50%" valign="top">

### 🖥️ App de mesa de verdade
Janela própria com o painel em React, ícone na bandeja (Abrir, Pausar, Sair) e **Iniciar com o Windows**. Fechar a janela só esconde; o corretor continua ativo.

### 🌗 Folha de Revisão
O visual imita uma folha corrigida à caneta: sublinhado ondulado ao vivo, marcações numeradas na margem, teclas 3D que afundam com as de verdade. Tema claro e escuro, sem gradiente nem brilho.

### 🔒 Fica no seu computador
Nada vai para a internet, a menos que você ligue a [revisão por IA](#-revisão-por-ia-opcional). O servidor só aceita a própria máquina (`127.0.0.1`), bloqueia pedidos de outros sites e o histórico fica em `~/.corretor-zap`.

</td>
</tr>
</table>

<div align="center">
<table>
<tr>
<td><img src=".github/assets/inicio-claro.png" alt="Início com sublinhado ao vivo no tema claro"></td>
<td><img src=".github/assets/historico-escuro.png" alt="Histórico no tema escuro"></td>
</tr>
<tr>
<td align="center"><sub>Início · tema claro</sub></td>
<td align="center"><sub>Histórico · tema escuro</sub></td>
</tr>
<tr>
<td><img src=".github/assets/atalho-escuro.png" alt="Página Atalho no tema escuro"></td>
<td><img src=".github/assets/dicionario-claro.png" alt="Dicionário no tema claro"></td>
</tr>
<tr>
<td align="center"><sub>Atalho · tema escuro</sub></td>
<td align="center"><sub>Dicionário · tema claro</sub></td>
</tr>
<tr>
<td><img src=".github/assets/ia-claro.png" alt="Página IA no tema claro, com o Google Gemini escolhido"></td>
<td><img src=".github/assets/ia-escuro.png" alt="Teste da IA no tema escuro, com a frase corrigida e o tempo de resposta"></td>
</tr>
<tr>
<td align="center"><sub>IA · tema claro</sub></td>
<td align="center"><sub>Testar a IA · tema escuro</sub></td>
</tr>
</table>
</div>

## ✨ Revisão por IA (opcional)

O corretor local acerta ortografia e acentos, mas uma IA entende a frase inteira: concordância, vírgula no lugar certo, pergunta que precisa de `?`. Na página **IA** você liga uma e cola a chave; daí em diante o atalho manda a mensagem para ela.

<div align="center">
<table>
<tr>
<td><img src=".github/assets/previa-ia-claro.png" alt="Prévia com a etiqueta IA: oi pessoal amanha a gente se encontra na praça as 18h quem vai levar o bolo, corrigida" width="420"></td>
<td><img src=".github/assets/previa-ia-escuro.png" alt="A mesma prévia no tema escuro" width="420"></td>
</tr>
</table>
<sub>Resposta real do Gemini (gemini-3.1-flash-lite) em 0,8 a 2 segundos</sub>
</div>

### Como ligar

1. Crie uma chave de graça em [aistudio.google.com/apikey](https://aistudio.google.com/apikey) (ou instale o [Ollama](https://ollama.com) para rodar no seu PC).
2. No app, abra **IA**, escolha o serviço, cole a chave e clique em **Salvar**.
3. Clique em **Testar**: aparece a frase corrigida e o tempo de resposta.
4. Ligue **Usar IA no atalho**.

| | Custo | Suas mensagens | Velocidade |
|---|---|---|---|
| **Google Gemini** | grátis, com limite de uso | vão para o Google, que pode usá-las para melhorar os produtos dele | ~1 a 2 s |
| **Ollama** | grátis | não saem do seu computador | depende do PC (precisa de boa memória) |
| **Outro** (OpenRouter, Groq, LM Studio…) | depende do serviço | vão para o serviço escolhido | depende do serviço |

### O que impede a IA de estragar a mensagem

- **Só corrige.** Ela é instruída a manter gírias, abreviações (`vc`, `tb`, `blz`), risadas, emojis, nomes e quebras de linha, e a nunca mexer nas palavras do seu dicionário.
- **Você decide cada troca.** A resposta vira correções separadas na prévia, como as do corretor local: clique numa para desfazer.
- **Resposta estranha é descartada.** Se a IA reescrever a mensagem (ou responder a ela) em vez de corrigir, o app ignora e usa o corretor local.
- **A prévia não espera a IA.** O cartão abre na hora com a correção local e a etiqueta **IA revisando**; quando a resposta chega (em geral 1 a 2 s), ele se atualiza sozinho. Se você já mexeu em alguma correção, ele não troca nada por baixo: só avisa que a versão da IA está pronta.
- **O atalho nunca para.** Sem internet, limite gratuito atingido, serviço sobrecarregado ou mais de 10 s de espera: a prévia fica com a correção local e a etiqueta **Sem IA**.
- **A chave fica só no seu PC** (`~/.corretor-zap/ia.json`), nunca volta para a tela e só viaja por `https://` (ou `http://localhost`, no caso do Ollama).

### Mudar o tom

Com a IA ligada, a prévia ganha três botões: **Formal**, **Curto** e **Gentil** (ou `Alt + 1`, `Alt + 2`, `Alt + 3`). Eles reescrevem a mensagem inteira no tom escolhido, e `Enter` aplica.

> `bom dia gente vamos comecar a reuniao as 10 hrs se der certo eu te aviso`
> → **Formal:** Bom dia a todos. Daremos início à reunião às 10h. Assim que possível, confirmarei com vocês.

> [!IMPORTANT]
> A IA vem **desligada**. O editor do Início continua usando só o corretor local, para não gastar o limite a cada tecla.

## Como é feito

```mermaid
graph LR
    atalho["⌨️ atalho<br/>JNativeHook + Robot"] --> ia["✨ ia<br/>RevisorIa"]
    ia -- "desligada ou falhou" --> corr["✏️ correcao<br/>LanguageTool + Ranqueador + Pontuador"]
    ia -. "HTTPS (opcional)" .-> nuvem(["☁️ Gemini, Ollama…"])
    atalho --> previa["🔍 desktop/PreviaFlutuante<br/>janela transparente"]
    previa --> corr
    janela["🖥️ desktop/AppDesktop<br/>JavaFX WebView + bandeja"] --> painel["⚛️ frontend<br/>React + Tailwind + shadcn"]
    painel -- "HTTP 127.0.0.1:8765" --> api["🌐 api<br/>Spring Boot"]
    api --> corr
    api --> ia
    api --> config["⚙️ config<br/>dicionário, regras, histórico"]
```

- **`correcao`** junta o LanguageTool (ortografia e acentos) com o `Ranqueador` (escolhe a sugestão mais parecida com o erro) e o `Pontuador` (vírgulas e fim de frase), e filtra o que não faz sentido em chat.
- **`ia`** (opcional) manda a mensagem do atalho para a IA configurada e transforma a resposta em correções separadas; se a IA falhar, o corretor local assume.
- **`atalho`** escuta o atalho global e lê/escreve a caixa em foco com `Ctrl+A`, `Ctrl+C` e `Ctrl+V` simulados, guardando a área de transferência antes.
- **`desktop`** abre o painel numa janela nativa (JavaFX WebView) e a prévia numa janela transparente por cima do WhatsApp, com JNA para o foco das janelas no Windows.
- **`frontend`** é compilado para dentro do jar (`backend/src/main/resources/static`), então o app é um único executável.

<details>
<summary><b>📁 Estrutura das pastas</b></summary>

```
corretor-zap/
├── backend/                 Spring Boot (Java 21)
│   └── src/main/java/com/corretorzap/
│       ├── api/             endpoints do painel
│       ├── atalho/          atalho global e leitura/escrita da caixa em foco
│       ├── config/          configuração, dicionário e regras
│       ├── correcao/        LanguageTool, Ranqueador, Pontuador e histórico
│       ├── demo/            dados de demonstração
│       ├── desktop/         janela, bandeja, prévia flutuante, Windows (JNA)
│       ├── ia/              revisão opcional por IA (Gemini, Ollama, OpenAI-compatível)
│       ├── seguranca/       filtro que só aceita a própria máquina
│       └── web/             CORS e cabeçalhos de segurança
├── frontend/                React + TypeScript + Vite + Tailwind + shadcn
│   └── src/
│       ├── paginas/         Início, Atalho, Dicionário, Histórico, IA
│       ├── previa/          cartão de prévia flutuante
│       └── components/      editor de revisão, notas da margem, teclas
├── packaging/               empacotar.cmd (jpackage) e ícone
├── testes-carga/            estresse, bateria de pontuação e teste do atalho
└── iniciar.cmd              roda a partir do código
```

</details>

## Instalar

1. Baixe o **`CorretorZap-windows.zip`** na [última versão](https://github.com/MatheusEB/corretor-zap/releases/latest).
2. Extraia a pasta onde quiser e abra **`Corretor Zap.exe`**. O Java já vem dentro.

> [!NOTE]
> O executável não tem assinatura digital, então o Windows pode mostrar "O Windows protegeu o computador". Clique em **Mais informações** e depois em **Executar assim mesmo**.

## Rodar a partir do código

Precisa de **JDK 21+** e **Node 20.19+** (ou 22.12+).

```bat
iniciar.cmd
```

Na primeira vez ele compila o painel e o backend, e depois abre a janela e o ícone na bandeja.

Para gerar o app portátil, com o Java embutido (quem baixar não precisa instalar nada):

```bat
packaging\empacotar.cmd
```

O resultado fica em `dist\Corretor Zap\Corretor Zap.exe` e `dist\CorretorZap-windows.zip`.

> [!NOTE]
> Para ver o app com dados de exemplo, sem tocar na sua configuração:
> `java -jar backend/target/corretor-zap-0.0.1-SNAPSHOT.jar --corretor.demo=true --server.port=8766 --corretor.config-dir=<pasta temporária>`

## Testes

```bash
cd backend
./mvnw test
```

São **129 testes** (JUnit 5): correção, escolha de sugestões, pontuação (33 casos de mensagens reais), revisão por IA (com um servidor falso lento, fora do ar e com 300 pedidos ao mesmo tempo), prévia em duas etapas, tons, dicionário que aprende, API, atalho, concorrência e o filtro de segurança.

Com o app aberto, os testes de `testes-carga/` medem o resto:

```bash
node testes-carga/pontuacao.mjs    # bateria de mensagens de chat → como o corretor devolve
node testes-carga/estresse.mjs     # 200 usuários simultâneos, textos de 20 mil caracteres
python testes-carga/previa-navegador.py   # o cartão de prévia num navegador de verdade (Playwright + Edge)
powershell -ExecutionPolicy Bypass -File testes-carga/atalho-e2e.ps1   # o atalho de ponta a ponta numa janela de teste
```

`LatenciaBenchmark` e `LatenciaGeminiBenchmark` (em `backend/src/test`) medem o corretor local e o Gemini de verdade; ficam fora da suíte porque o segundo gasta a cota da sua chave.

## Privacidade

- A correção roda **no seu computador**. Nenhum texto vai para servidor nenhum, a não ser que você ligue a [revisão por IA](#-revisão-por-ia-opcional): aí o texto de cada mensagem corrigida pelo atalho vai para o serviço escolhido (no Gemini gratuito, o Google pode usá-lo para melhorar os produtos dele). Com o Ollama, continua tudo no seu PC.
- A chave da IA fica só em `~/.corretor-zap/ia.json`: o painel nunca a mostra de volta.
- O painel só responde para `127.0.0.1`: ninguém na sua rede Wi-Fi, nem um site aberto no navegador, consegue ler o histórico.
- O log em `~/.corretor-zap/logs` guarda só quanto tempo cada etapa levou, nunca o texto das mensagens.
- O corretor não lê as suas conversas. Ele só vê o texto da caixa de mensagem quando você aperta o atalho.

## Licença

[MIT](LICENSE) para o código deste repositório. O app usa bibliotecas com licenças próprias, que continuam valendo:

| Biblioteca | Para quê | Licença |
|---|---|---|
| [LanguageTool](https://github.com/languagetool-org/languagetool) 6.8 | ortografia em pt-BR | LGPL 2.1 |
| [JNativeHook](https://github.com/kwhat/jnativehook) 2.2.2 | atalho global | LGPL 3 (ou GPL 3) |
| [OpenJFX](https://openjfx.io) 23 | janela e WebView | GPL 2 com Classpath Exception |
| [JNA](https://github.com/java-native-access/jna) 5.12 | foco das janelas no Windows | Apache 2.0 (ou LGPL 2.1) |
| [Spring Boot](https://spring.io/projects/spring-boot) 4.1 | servidor local | Apache 2.0 |

---

<div align="center">
<sub>Feito para escrever certo sem parar a conversa</sub>
</div>
