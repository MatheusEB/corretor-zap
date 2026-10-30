package com.corretorzap.atualizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corretorzap.atualizacao.AtualizacaoService.Estado;
import com.corretorzap.atualizacao.AtualizacaoService.Fase;
import com.corretorzap.atualizacao.AtualizacaoService.VersaoDisponivel;
import com.corretorzap.config.ConfiguracaoService;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** Um servidor HTTP local faz o papel do GitHub: a API de releases e o download do ZIP. */
class AtualizacaoServiceTest {

	@TempDir
	Path pasta;

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final List<Object> eventos = new CopyOnWriteArrayList<>();
	private final List<List<String>> instalacoes = new CopyOnWriteArrayList<>();
	private final AtomicInteger pedidosDeVersao = new AtomicInteger();
	private HttpServer servidor;
	private volatile int statusDaApi = 200;
	private volatile String release;
	private volatile byte[] zip;
	private Path dados;
	private Path exe;

	@BeforeEach
	void setUp() throws IOException {
		dados = pasta.resolve("dados");
		exe = pasta.resolve("programas").resolve("Corretor Zap").resolve("Corretor Zap.exe");
		Files.createDirectories(exe.getParent());
		Files.writeString(exe, "versão antiga");

		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/repos/x/releases/latest", troca -> {
			pedidosDeVersao.incrementAndGet();
			byte[] corpo = release.getBytes(StandardCharsets.UTF_8);
			troca.sendResponseHeaders(statusDaApi, corpo.length);
			troca.getResponseBody().write(corpo);
			troca.close();
		});
		servidor.createContext("/download/", troca -> {
			troca.sendResponseHeaders(200, zip.length);
			troca.getResponseBody().write(zip);
			troca.close();
		});
		servidor.start();
		zip = zipDoApp(Map.of("Corretor Zap/Corretor Zap.exe", "versão nova", "Corretor Zap/app/corretor-zap.jar", "jar"));
		release = release("v0.4.0", zip);
	}

	@AfterEach
	void tearDown() {
		servidor.stop(0);
	}

	private String base() {
		return "http://127.0.0.1:" + servidor.getAddress().getPort();
	}

	private AtualizacaoService servico(String versao) {
		return new AtualizacaoService(base() + "/repos/x/releases/latest", base() + "/download/", versao,
				exe.toString(), dados, new ConfiguracaoService(dados, mapper), mapper, eventos::add,
				List.of("--minimizado", "--server.port=9999"), (script, argumentos) -> instalacoes.add(argumentos));
	}

	private String release(String tag, byte[] arquivo) {
		return release(tag, AtualizacaoService.NOME_DO_ZIP, "sha256:" + sha256(arquivo), base() + "/download/" + tag,
				arquivo.length);
	}

	private String release(String tag, String nome, String digest, String url, long tamanho) {
		Map<String, Object> asset = new LinkedHashMap<>();
		asset.put("name", nome);
		asset.put("size", tamanho);
		asset.put("digest", digest);
		asset.put("browser_download_url", url);
		return mapper.writeValueAsString(Map.of("tag_name", tag, "body", "## Novidades\n- prévia mais rápida",
				"html_url", "https://github.com/x/releases/tag/" + tag, "assets", List.of(asset)));
	}

	private static byte[] zipDoApp(Map<String, String> arquivos) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream saida = new ZipOutputStream(bytes)) {
			saida.putNextEntry(new ZipEntry("Corretor Zap/"));
			for (Map.Entry<String, String> arquivo : arquivos.entrySet()) {
				saida.putNextEntry(new ZipEntry(arquivo.getKey()));
				saida.write(arquivo.getValue().getBytes(StandardCharsets.UTF_8));
			}
		}
		return bytes.toByteArray();
	}

	private static String sha256(byte[] dados) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Espera o download terminar: ou chamou o script (no app de verdade ele fecha aqui) ou deu erro. */
	private Estado esperarTerminar(AtualizacaoService servico, int instalacoesAntes) throws InterruptedException {
		long limite = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < limite) {
			Estado e = servico.estado();
			if (e.fase() == Fase.ERRO || instalacoes.size() > instalacoesAntes) {
				return e;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("a atualização não terminou: " + servico.estado());
	}

	private Path pastaNova() {
		return exe.getParent().resolveSibling("Corretor Zap.nova");
	}

	// ---- versões

	@Test
	void comparaVersoesComoNumerosENaoComoTexto() {
		assertThat(Versao.ler("v0.10.0").get().maiorQue(Versao.ler("0.9.9").get())).isTrue();
		assertThat(Versao.ler("1.0").get()).isEqualTo(Versao.ler("v1.0.0").get());
		assertThat(Versao.ler("1.0").get().hashCode()).isEqualTo(Versao.ler("1.0.0").get().hashCode());
		assertThat(Versao.ler("0.3.0").get().maiorQue(Versao.ler("0.3.0").get())).isFalse();
		assertThat(Versao.ler("v0.4.0-beta")).isEmpty();
		assertThat(Versao.ler("latest")).isEmpty();
		assertThat(Versao.ler(null)).isEmpty();
	}

	// ---- procurar versão nova

	@Test
	void rodandoDoCodigoFicaDesligadoSemPerguntarAoGithub() {
		AtualizacaoService servico = new AtualizacaoService(base() + "/repos/x/releases/latest", base() + "/download/",
				"", "", dados, new ConfiguracaoService(dados, mapper), mapper, eventos::add, List.of(),
				(s, a) -> instalacoes.add(a));

		assertThat(servico.verificar().fase()).isEqualTo(Fase.INDISPONIVEL);
		assertThat(pedidosDeVersao).hasValue(0);
		assertThatThrownBy(servico::instalar).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void versaoMaiorNoGithubFicaDisponivelEAvisaABandejaUmaVezSo() {
		AtualizacaoService servico = servico("0.3.0");

		Estado estado = servico.verificar();
		servico.verificar();

		assertThat(estado.fase()).isEqualTo(Fase.DISPONIVEL);
		assertThat(estado.atual()).isEqualTo("0.3.0");
		assertThat(estado.nova()).isEqualTo("0.4.0");
		assertThat(estado.novidades()).contains("prévia mais rápida");
		assertThat(eventos).containsExactly(new VersaoDisponivel("0.4.0"));
	}

	@Test
	void mesmaVersaoOuMaisAntigaNoGithubContaComoAtualizado() {
		assertThat(servico("0.4.0").verificar().fase()).isEqualTo(Fase.ATUALIZADO);
		assertThat(servico("0.5.1").verificar().fase()).isEqualTo(Fase.ATUALIZADO);
		assertThat(eventos).isEmpty();
	}

	@Test
	void githubForaDoArViraErroSemDerrubarNada() {
		statusDaApi = 503;
		AtualizacaoService servico = servico("0.3.0");

		Estado estado = servico.verificar();

		assertThat(estado.fase()).isEqualTo(Fase.ERRO);
		assertThat(estado.erro()).contains("503");
		statusDaApi = 200;
		assertThat(servico.verificar().fase()).as("na próxima tentativa volta ao normal").isEqualTo(Fase.DISPONIVEL);
	}

	@Test
	void semInternetDizIssoEmVezDeNull() {
		AtualizacaoService servico = servico("0.3.0");
		servidor.stop(0);

		Estado estado = servico.verificar();

		assertThat(estado.fase()).isEqualTo(Fase.ERRO);
		assertThat(estado.erro()).endsWith("sem conexão com a internet").doesNotContain("null");
	}

	@Test
	void respostaQueNaoEJsonViraErro() {
		release = "<html>rate limit</html>";

		assertThat(servico("0.3.0").verificar().fase()).isEqualTo(Fase.ERRO);
	}

	@Test
	void naoOfereceVersaoSemOZipSemSha256OuComDownloadDeOutroLugar() {
		String digest = "sha256:" + sha256(zip);
		for (String r : List.of(
				release("v0.4.0", "outro.zip", digest, base() + "/download/v0.4.0", zip.length),
				release("v0.4.0", AtualizacaoService.NOME_DO_ZIP, "", base() + "/download/v0.4.0", zip.length),
				release("v0.4.0", AtualizacaoService.NOME_DO_ZIP, "md5:abc", base() + "/download/v0.4.0", zip.length),
				release("v0.4.0", AtualizacaoService.NOME_DO_ZIP, digest, "https://exemplo.com/download/v0.4.0",
						zip.length))) {
			release = r;
			AtualizacaoService servico = servico("0.3.0");

			Estado estado = servico.verificar();

			assertThat(estado.fase()).as(r).isEqualTo(Fase.ERRO);
			assertThatThrownBy(servico::instalar).isInstanceOf(IllegalStateException.class);
		}
		assertThat(eventos).isEmpty();
	}

	// ---- baixar e instalar

	@Test
	void instalarBaixaConfereExtraiAoLadoEChamaOScript() throws Exception {
		AtualizacaoService servico = servico("0.3.0");
		servico.verificar();

		servico.instalar();
		Estado estado = esperarTerminar(servico, 0);

		assertThat(estado.erro()).isNull();
		assertThat(instalacoes).hasSize(1);
		assertThat(Files.readString(pastaNova().resolve("Corretor Zap.exe"))).isEqualTo("versão nova");
		assertThat(Files.readString(pastaNova().resolve("app").resolve("corretor-zap.jar"))).isEqualTo("jar");
		assertThat(Files.readString(exe)).as("a pasta atual só é trocada pelo script").isEqualTo("versão antiga");

		List<String> argumentos = instalacoes.get(0);
		assertThat(valor(argumentos, "-Processo")).isEqualTo(String.valueOf(ProcessHandle.current().pid()));
		assertThat(valor(argumentos, "-Atual")).isEqualTo(exe.getParent().toString());
		assertThat(valor(argumentos, "-Nova")).isEqualTo(pastaNova().toString());
		assertThat(valor(argumentos, "-Exe")).isEqualTo("Corretor Zap.exe");

		Path trabalho = dados.resolve("atualizacao");
		byte[] script = Files.readAllBytes(trabalho.resolve("atualizar.ps1"));
		assertThat(script).startsWith(0xEF, 0xBB, 0xBF);
		assertThat(new String(script, StandardCharsets.UTF_8)).contains("Wait-Process");
		assertThat(Files.readAllLines(trabalho.resolve("argumentos.txt")))
				.as("volta com a janela aberta, mantendo o resto").containsExactly("--server.port=9999");
		assertThat(Files.readString(trabalho.resolve("pendente.txt"))).isEqualTo("0.4.0");
		assertThat(trabalho.resolve(AtualizacaoService.NOME_DO_ZIP)).doesNotExist();
	}

	@Test
	void zipQueNaoConfereComOSha256NaoInstalaENaoDeixaLixo() throws Exception {
		AtualizacaoService servico = servico("0.3.0");
		servico.verificar();
		byte[] adulterado = zipDoApp(Map.of("Corretor Zap/Corretor Zap.exe", "outra coisa!"));
		zip = adulterado;
		// mesmo tamanho anunciado, conteúdo diferente
		release = release("v0.4.0", AtualizacaoService.NOME_DO_ZIP, "sha256:" + "0".repeat(64),
				base() + "/download/v0.4.0", adulterado.length);
		servico.verificar();

		servico.instalar();
		Estado estado = esperarTerminar(servico, 0);

		assertThat(estado.fase()).isEqualTo(Fase.ERRO);
		assertThat(estado.erro()).contains("não confere");
		assertThat(instalacoes).isEmpty();
		assertThat(pastaNova()).doesNotExist();
		assertThat(dados.resolve("atualizacao").resolve(AtualizacaoService.NOME_DO_ZIP)).doesNotExist();
		assertThat(Files.readString(exe)).isEqualTo("versão antiga");
	}

	@Test
	void downloadIncompletoNaoInstala() throws Exception {
		release = release("v0.4.0", AtualizacaoService.NOME_DO_ZIP, "sha256:" + sha256(zip), base() + "/download/v0.4.0",
				zip.length + 10);
		AtualizacaoService servico = servico("0.3.0");
		servico.verificar();

		servico.instalar();
		Estado estado = esperarTerminar(servico, 0);

		assertThat(estado.erro()).contains("incompleto");
		assertThat(instalacoes).isEmpty();
	}

	@Test
	void depoisDeUmErroDaParaTentarDeNovo() throws Exception {
		byte[] bom = zip;
		zip = new byte[bom.length];
		AtualizacaoService servico = servico("0.3.0");
		servico.verificar();
		servico.instalar();
		assertThat(esperarTerminar(servico, 0).fase()).isEqualTo(Fase.ERRO);

		zip = bom;
		servico.instalar();

		assertThat(esperarTerminar(servico, 0).erro()).isNull();
		assertThat(instalacoes).hasSize(1);
	}

	@Test
	void instalarDuasVezesAoMesmoTempoSoBaixaUma() throws Exception {
		AtualizacaoService servico = servico("0.3.0");
		servico.verificar();
		List<Throwable> recusas = new CopyOnWriteArrayList<>();
		List<Thread> cliques = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			cliques.add(Thread.ofVirtual().start(() -> {
				try {
					servico.instalar();
				} catch (IllegalStateException e) {
					recusas.add(e);
				}
			}));
		}
		for (Thread t : cliques) {
			t.join();
		}

		esperarTerminar(servico, 0);
		Thread.sleep(200);

		assertThat(recusas).hasSize(19);
		assertThat(instalacoes).hasSize(1);
	}

	@Test
	void zipComCaminhoParaForaDaPastaERecusado() throws Exception {
		Path arquivo = pasta.resolve("malicioso.zip");
		Files.write(arquivo, zipDoApp(Map.of("Corretor Zap/Corretor Zap.exe", "x", "Corretor Zap/../../fora.txt", "y")));
		Path destino = pasta.resolve("destino");

		assertThatThrownBy(() -> AtualizacaoService.extrair(arquivo, destino, "Corretor Zap.exe"))
				.hasMessageContaining("caminho inválido");
		assertThat(pasta.resolve("fora.txt")).doesNotExist();
		assertThat(destino).doesNotExist();
	}

	@Test
	void zipDoCompressArchiveComBarraInvertidaExtraiAsPastas() throws Exception {
		// o ZIP publicado é feito pelo Compress-Archive do Windows, que grava os caminhos assim
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream saida = new ZipOutputStream(bytes)) {
			for (String pasta : List.of("Corretor Zap\\", "Corretor Zap\\runtime\\", "Corretor Zap\\runtime\\legal\\")) {
				saida.putNextEntry(new ZipEntry(pasta));
			}
			for (String arquivo : List.of("Corretor Zap\\Corretor Zap.exe", "Corretor Zap\\runtime\\legal\\java.base\\LICENSE")) {
				saida.putNextEntry(new ZipEntry(arquivo));
				saida.write(arquivo.getBytes(StandardCharsets.UTF_8));
			}
		}
		Path arquivo = pasta.resolve("compress-archive.zip");
		Files.write(arquivo, bytes.toByteArray());
		Path destino = pasta.resolve("destino");

		AtualizacaoService.extrair(arquivo, destino, "Corretor Zap.exe");

		assertThat(destino.resolve("runtime").resolve("legal")).isDirectory();
		assertThat(destino.resolve("runtime").resolve("legal").resolve("java.base").resolve("LICENSE")).isRegularFile();
		assertThat(destino.resolve("Corretor Zap.exe")).isRegularFile();
	}

	@Test
	void zipDeVerdadeDoEmpacotamentoExtraiInteiro() throws Exception {
		Path publicado = Path.of("..", "dist", AtualizacaoService.NOME_DO_ZIP);
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(publicado), "rode packaging\\empacotar.cmd antes");
		Path destino = pasta.resolve("destino");

		AtualizacaoService.extrair(publicado, destino, "Corretor Zap.exe");

		assertThat(destino.resolve("app").resolve("corretor-zap.jar")).isRegularFile();
		assertThat(destino.resolve("runtime").resolve("bin")).isDirectory();
	}

	@Test
	void zipSemOExecutavelERecusado() throws Exception {
		Path arquivo = pasta.resolve("sem-exe.zip");
		Files.write(arquivo, zipDoApp(Map.of("Corretor Zap/app/corretor-zap.jar", "jar")));
		Path destino = pasta.resolve("destino");

		assertThatThrownBy(() -> AtualizacaoService.extrair(arquivo, destino, "Corretor Zap.exe"))
				.hasMessageContaining("Corretor Zap.exe");
		assertThat(destino).doesNotExist();
	}

	@Test
	void arquivoQueNaoEZipERecusado() throws Exception {
		Path arquivo = pasta.resolve("lixo.zip");
		Files.writeString(arquivo, "isso não é um zip");

		assertThatThrownBy(() -> AtualizacaoService.extrair(arquivo, pasta.resolve("destino"), "Corretor Zap.exe"))
				.isInstanceOf(IOException.class);
	}

	// ---- abrir depois de atualizar

	@Test
	void abrirNaVersaoBaixadaAvisaQueAtualizou() throws Exception {
		Files.createDirectories(dados.resolve("atualizacao"));
		Files.writeString(dados.resolve("atualizacao").resolve("pendente.txt"), "0.4.0");

		AtualizacaoService servico = servico("0.4.0");

		assertThat(servico.estado().acabouDeAtualizar()).isTrue();
		assertThat(dados.resolve("atualizacao").resolve("pendente.txt")).doesNotExist();
		servico.avisoVisto();
		assertThat(servico.estado().acabouDeAtualizar()).isFalse();
	}

	@Test
	void abrirAindaNaVersaoAntigaContaQueAAtualizacaoFalhou() throws Exception {
		Files.createDirectories(dados.resolve("atualizacao"));
		Files.writeString(dados.resolve("atualizacao").resolve("pendente.txt"), "0.4.0");

		Estado estado = servico("0.3.0").estado();

		assertThat(estado.fase()).isEqualTo(Fase.ERRO);
		assertThat(estado.erro()).contains("0.4.0").contains("atualizacao.log");
		assertThat(estado.acabouDeAtualizar()).isFalse();
	}

	private static String valor(List<String> argumentos, String nome) {
		return argumentos.get(argumentos.indexOf(nome) + 1);
	}

}
