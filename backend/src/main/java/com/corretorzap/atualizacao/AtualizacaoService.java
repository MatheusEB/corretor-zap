package com.corretorzap.atualizacao;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.corretorzap.config.ConfiguracaoService;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Atualiza o app sozinho a partir das releases do GitHub: procura versão nova, baixa o ZIP, confere o SHA-256 que o
 * GitHub publica, extrai ao lado da pasta do app e deixa um script do PowerShell (atualizar.ps1) trocar as pastas
 * depois que o app fecha. Atalho, dicionário e histórico ficam em ~/.corretor-zap e não são tocados.
 * <p>
 * Só funciona no app empacotado (jpackage), que é quem informa a versão e a pasta; rodando do código fica desligado.
 */
@Service
public class AtualizacaoService {

	public enum Fase {
		/** rodando do código-fonte: não há pasta de app para trocar */
		INDISPONIVEL, ATUALIZADO, DISPONIVEL, BAIXANDO, INSTALANDO, ERRO
	}

	/**
	 * @param acabouDeAtualizar o app abriu agora já na versão nova (a tela mostra um aviso uma vez)
	 */
	public record Estado(String atual, Fase fase, String nova, String novidades, String pagina, int progresso,
			String erro, boolean acabouDeAtualizar) {
	}

	/** Publicado quando aparece uma versão nova, para a bandeja avisar. */
	public record VersaoDisponivel(String versao) {
	}

	/** Quem troca as pastas e fecha o app; nos testes é trocado por um falso. */
	interface Instalador {
		void instalar(Path script, List<String> argumentos) throws IOException;
	}

	static final String NOME_DO_ZIP = "CorretorZap-windows.zip";
	private static final Logger log = LoggerFactory.getLogger(AtualizacaoService.class);
	private static final long TAMANHO_MAXIMO = 600L * 1024 * 1024;
	private static final Duration PRIMEIRA_VERIFICACAO = Duration.ofSeconds(20);
	private static final Duration INTERVALO = Duration.ofHours(6);

	private final String urlUltimaVersao;
	private final String prefixoDownload;
	private final Optional<Versao> versaoAtual;
	private final Path exe;
	private final Path pastaTrabalho;
	private final ConfiguracaoService configuracao;
	private final ObjectMapper mapper;
	private final ApplicationEventPublisher eventos;
	private final List<String> argumentosDoApp;
	private final HttpClient http;
	private Instalador instalador;
	private ScheduledExecutorService agenda;

	private Fase fase;
	private Versao nova;
	private String novidades;
	private String pagina;
	private String urlZip;
	private String sha256;
	private long tamanho;
	private volatile int progresso;
	private String erro;
	private boolean acabouDeAtualizar;
	private String avisada;

	@Autowired
	public AtualizacaoService(@Value("${corretor.atualizacao.api}") String urlUltimaVersao,
			@Value("${corretor.atualizacao.downloads}") String prefixoDownload,
			@Value("${jpackage.app-version:}") String versaoAtual, @Value("${jpackage.app-path:}") String exe,
			@Value("${corretor.config-dir}") Path pastaDados, ConfiguracaoService configuracao, ObjectMapper mapper,
			ApplicationEventPublisher eventos, ApplicationArguments argumentos) {
		this(urlUltimaVersao, prefixoDownload, versaoAtual, exe, pastaDados, configuracao, mapper, eventos,
				List.of(argumentos.getSourceArgs()), null);
		this.instalador = this::abrirScriptEFechar;
	}

	AtualizacaoService(String urlUltimaVersao, String prefixoDownload, String versaoAtual, String exe,
			Path pastaDados, ConfiguracaoService configuracao, ObjectMapper mapper, ApplicationEventPublisher eventos,
			List<String> argumentosDoApp, Instalador instalador) {
		this.urlUltimaVersao = urlUltimaVersao;
		this.prefixoDownload = prefixoDownload;
		this.versaoAtual = Versao.ler(versaoAtual);
		this.exe = exe == null || exe.isBlank() ? null : Path.of(exe).toAbsolutePath();
		this.pastaTrabalho = pastaDados.resolve("atualizacao");
		this.configuracao = configuracao;
		this.mapper = mapper;
		this.eventos = eventos;
		// depois de atualizar o app volta com a janela aberta, para a pessoa ver que deu certo
		this.argumentosDoApp = argumentosDoApp.stream().filter(a -> !a.equals("--minimizado")).toList();
		this.instalador = instalador;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		this.fase = disponivelNesteApp() ? Fase.ATUALIZADO : Fase.INDISPONIVEL;
		conferirAtualizacaoAnterior();
	}

	private boolean disponivelNesteApp() {
		return versaoAtual.isPresent() && exe != null;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void agendar() {
		if (!disponivelNesteApp()) {
			return;
		}
		agenda = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("atualizacao").factory());
		agenda.scheduleWithFixedDelay(() -> {
			if (configuracao.atual().atualizacoes()) {
				verificar();
			}
		}, PRIMEIRA_VERIFICACAO.toSeconds(), INTERVALO.toSeconds(), TimeUnit.SECONDS);
	}

	@PreDestroy
	void parar() {
		if (agenda != null) {
			agenda.shutdownNow();
		}
	}

	public synchronized Estado estado() {
		return new Estado(versaoAtual.map(Versao::toString).orElse(null), fase, nova == null ? null : nova.toString(),
				novidades, pagina, progresso, erro, acabouDeAtualizar);
	}

	/** A tela já mostrou o aviso de "atualizado". */
	public synchronized void avisoVisto() {
		acabouDeAtualizar = false;
	}

	/** Pergunta ao GitHub qual é a última versão. Falhas (sem internet, limite da API) só viram estado de erro. */
	public Estado verificar() {
		synchronized (this) {
			if (!disponivelNesteApp() || fase == Fase.BAIXANDO || fase == Fase.INSTALANDO) {
				return estado();
			}
		}
		try {
			HttpRequest pedido = HttpRequest.newBuilder(URI.create(urlUltimaVersao))
					.timeout(Duration.ofSeconds(15))
					.header("Accept", "application/vnd.github+json")
					.GET()
					.build();
			HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
			if (resposta.statusCode() != 200) {
				throw new IOException("o GitHub respondeu " + resposta.statusCode());
			}
			lerRelease(mapper.readTree(resposta.body()));
		} catch (IOException | RuntimeException e) {
			synchronized (this) {
				fase = Fase.ERRO;
				erro = "Não deu para procurar atualizações: " + motivo(e);
			}
			log.info("Não deu para procurar atualizações: {}", motivo(e));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return estado();
	}

	private void lerRelease(JsonNode release) throws IOException {
		Versao ultima = Versao.ler(release.path("tag_name").asString(""))
				.orElseThrow(() -> new IOException("versão sem número"));
		JsonNode zip = null;
		for (JsonNode asset : release.path("assets")) {
			if (NOME_DO_ZIP.equals(asset.path("name").asString(""))) {
				zip = asset;
			}
		}
		String digest = zip == null ? "" : zip.path("digest").asString("");
		String url = zip == null ? "" : zip.path("browser_download_url").asString("");
		String publicar = null;
		synchronized (this) {
			erro = null;
			if (!ultima.maiorQue(versaoAtual.orElseThrow())) {
				fase = Fase.ATUALIZADO;
				nova = null;
				return;
			}
			if (zip == null || !digest.matches("sha256:[0-9a-f]{64}") || !url.startsWith(prefixoDownload)) {
				// sem o arquivo certo ou sem como conferir o download, não instala
				throw new IOException("a versão " + ultima + " não tem um " + NOME_DO_ZIP + " que dê para conferir");
			}
			fase = Fase.DISPONIVEL;
			nova = ultima;
			novidades = release.path("body").asString("");
			pagina = release.path("html_url").asString("");
			urlZip = url;
			sha256 = digest.substring("sha256:".length());
			tamanho = zip.path("size").asLong(0);
			if (!ultima.toString().equals(avisada)) {
				avisada = ultima.toString();
				publicar = avisada;
			}
		}
		log.info("Versão nova disponível: {}", ultima);
		if (publicar != null && eventos != null) {
			eventos.publishEvent(new VersaoDisponivel(publicar));
		}
	}

	/** Começa a baixar e instalar em segundo plano; a tela acompanha pelo {@link #estado()}. */
	public synchronized Estado instalar() {
		if (fase != Fase.DISPONIVEL && !(fase == Fase.ERRO && nova != null)) {
			throw new IllegalStateException("Não há versão nova para instalar");
		}
		fase = Fase.BAIXANDO;
		progresso = 0;
		erro = null;
		Thread.ofVirtual().name("atualizacao-instalar").start(this::baixarEInstalar);
		return estado();
	}

	private void baixarEInstalar() {
		String versao;
		String url;
		String esperado;
		long tamanhoEsperado;
		synchronized (this) {
			versao = nova.toString();
			url = urlZip;
			esperado = sha256;
			tamanhoEsperado = tamanho;
		}
		Path pastaApp = exe.getParent();
		Path pastaNova = pastaApp.resolveSibling(pastaApp.getFileName() + ".nova");
		Path zip = pastaTrabalho.resolve(NOME_DO_ZIP);
		try {
			Files.createDirectories(pastaTrabalho);
			long inicio = System.nanoTime();
			baixar(url, zip, esperado, tamanhoEsperado);
			long baixado = System.nanoTime();
			synchronized (this) {
				fase = Fase.INSTALANDO;
			}
			extrair(zip, pastaNova, exe.getFileName().toString());
			log.info("Atualização: download {} ms, extração {} ms", (baixado - inicio) / 1_000_000,
					(System.nanoTime() - baixado) / 1_000_000);
			Files.deleteIfExists(zip);
			Path script = prepararScript();
			Files.writeString(pastaTrabalho.resolve("pendente.txt"), versao);
			log.info("Versão {} baixada e conferida; fechando para instalar", versao);
			instalador.instalar(script, List.of("-Processo", String.valueOf(ProcessHandle.current().pid()), "-Atual",
					pastaApp.toString(), "-Nova", pastaNova.toString(), "-Exe", exe.getFileName().toString(), "-Log",
					pastaDados().resolve("logs").resolve("atualizacao.log").toString(), "-Argumentos",
					pastaTrabalho.resolve("argumentos.txt").toString()));
		} catch (IOException | RuntimeException e) {
			apagar(pastaNova);
			try {
				Files.deleteIfExists(zip);
			} catch (IOException ignorado) {
				// fica para a próxima tentativa sobrescrever
			}
			synchronized (this) {
				fase = Fase.ERRO;
				erro = "A atualização falhou: " + motivo(e);
			}
			log.warn("A atualização falhou: {}", motivo(e));
		}
	}

	private Path pastaDados() {
		return pastaTrabalho.getParent();
	}

	private void baixar(String url, Path destino, String esperado, long tamanhoEsperado) throws IOException {
		if (!url.startsWith(prefixoDownload)) {
			throw new IOException("endereço de download fora do GitHub do projeto");
		}
		HttpRequest pedido = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
		HttpResponse<InputStream> resposta;
		try {
			resposta = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("download interrompido");
		}
		if (resposta.statusCode() != 200) {
			resposta.body().close();
			throw new IOException("o download respondeu " + resposta.statusCode());
		}
		MessageDigest sha;
		try {
			sha = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		long total = 0;
		try (InputStream entrada = new DigestInputStream(resposta.body(), sha);
				OutputStream saida = Files.newOutputStream(destino)) {
			byte[] bloco = new byte[64 * 1024];
			for (int lidos; (lidos = entrada.read(bloco)) != -1;) {
				total += lidos;
				if (total > TAMANHO_MAXIMO) {
					throw new IOException("arquivo grande demais");
				}
				saida.write(bloco, 0, lidos);
				if (tamanhoEsperado > 0) {
					progresso = (int) Math.min(100, total * 100 / tamanhoEsperado);
				}
			}
		}
		if (tamanhoEsperado > 0 && total != tamanhoEsperado) {
			throw new IOException("o download veio incompleto");
		}
		if (!HexFormat.of().formatHex(sha.digest()).equals(esperado)) {
			throw new IOException("o arquivo baixado não confere com o publicado no GitHub");
		}
		progresso = 100;
	}

	/**
	 * Extrai o ZIP (que tem tudo dentro de uma pasta "Corretor Zap/") direto em {@code destino}, recusando qualquer
	 * caminho que saia dela.
	 */
	static void extrair(Path zip, Path destino, String nomeDoExe) throws IOException {
		apagar(destino);
		Files.createDirectories(destino);
		Path raiz = destino.toAbsolutePath().normalize();
		try (ZipInputStream entrada = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip), 256 * 1024))) {
			for (ZipEntry item; (item = entrada.getNextEntry()) != null;) {
				// o Compress-Archive do Windows grava "Corretor Zap\app\" com barra invertida; aí o ZipEntry não
				// reconhece a pasta (isDirectory() só olha a "/" do fim) e ela viraria um arquivo vazio
				String nome = item.getName().replace('\\', '/');
				boolean pasta = nome.endsWith("/");
				int barra = nome.indexOf('/');
				String relativo = barra < 0 ? "" : nome.substring(barra + 1);
				if (relativo.isEmpty()) {
					continue;
				}
				Path alvo = raiz.resolve(relativo).normalize();
				if (!alvo.startsWith(raiz) || alvo.equals(raiz)) {
					throw new IOException("o arquivo tem um caminho inválido: " + nome);
				}
				if (pasta) {
					Files.createDirectories(alvo);
				} else {
					Files.createDirectories(alvo.getParent());
					Files.copy(entrada, alvo);
				}
			}
		} catch (IOException | RuntimeException e) {
			apagar(destino);
			throw e instanceof IOException io ? io : new IOException("o arquivo baixado está corrompido", e);
		}
		if (!Files.isRegularFile(raiz.resolve(nomeDoExe))) {
			apagar(destino);
			throw new IOException("o arquivo baixado não tem o " + nomeDoExe);
		}
	}

	private Path prepararScript() throws IOException {
		Path script = pastaTrabalho.resolve("atualizar.ps1");
		try (InputStream modelo = AtualizacaoService.class.getResourceAsStream("/atualizacao/atualizar.ps1")) {
			if (modelo == null) {
				throw new IOException("o script de atualização sumiu do app");
			}
			// o PowerShell 5 só lê o script como UTF-8 se ele começar com o BOM
			byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
			try (OutputStream saida = Files.newOutputStream(script)) {
				saida.write(bom);
				modelo.transferTo(saida);
			}
		}
		Files.write(pastaTrabalho.resolve("argumentos.txt"), argumentosDoApp, StandardCharsets.UTF_8);
		Files.createDirectories(pastaDados().resolve("logs"));
		return script;
	}

	private void abrirScriptEFechar(Path script, List<String> argumentos) throws IOException {
		List<String> comando = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
				"-WindowStyle", "Hidden", "-File", script.toString()));
		comando.addAll(argumentos);
		new ProcessBuilder(comando).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		// o script espera este processo terminar para mexer na pasta
		Thread.ofPlatform().daemon(false).start(() -> System.exit(0));
	}

	/** Abriu depois de uma atualização? Confere se a versão é a que foi baixada. */
	private void conferirAtualizacaoAnterior() {
		Path pendente = pastaTrabalho.resolve("pendente.txt");
		if (!disponivelNesteApp() || !Files.exists(pendente)) {
			return;
		}
		try {
			Optional<Versao> esperada = Versao.ler(Files.readString(pendente));
			Files.deleteIfExists(pendente);
			if (esperada.equals(versaoAtual)) {
				acabouDeAtualizar = true;
				log.info("Atualizado para a versão {}", versaoAtual.get());
			} else {
				fase = Fase.ERRO;
				erro = "A atualização para a versão " + esperada.map(Versao::toString).orElse("nova")
						+ " não foi concluída. Detalhes em ~/.corretor-zap/logs/atualizacao.log";
			}
		} catch (IOException e) {
			log.warn("Não deu para ler {}: {}", pendente, e.getMessage());
		}
	}

	/** Algumas exceções do HttpClient não têm mensagem (a de conexão recusada vem com null). */
	static String motivo(Exception e) {
		if (e instanceof ConnectException) {
			return "sem conexão com a internet";
		}
		if (e instanceof HttpTimeoutException) {
			return "o GitHub demorou demais para responder";
		}
		return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
	}

	private static void apagar(Path pasta) {
		if (!Files.exists(pasta)) {
			return;
		}
		try (Stream<Path> tudo = Files.walk(pasta)) {
			tudo.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log.warn("Não deu para apagar {}: {}", pasta, e.getMessage());
		}
	}

}
