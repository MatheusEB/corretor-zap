package com.corretorzap.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.corretorzap.ia.ClienteIa.FalhaIa;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Um servidor HTTP local faz o papel do provedor de IA. */
class ClienteIaTest {

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final ClienteIa cliente = new ClienteIa(mapper);
	private final AtomicReference<String> caminho = new AtomicReference<>();
	private final List<String> pedidos = new CopyOnWriteArrayList<>();
	private volatile long atrasoMs;
	private final AtomicReference<String> autorizacao = new AtomicReference<>();
	private final AtomicReference<String> corpo = new AtomicReference<>();
	private volatile int status = 200;
	private volatile String resposta;
	/** Respostas em sequência (status, corpo); vazia = usa status/resposta. */
	private final ConcurrentLinkedDeque<Object[]> roteiro = new ConcurrentLinkedDeque<>();
	private final List<String> corpos = new CopyOnWriteArrayList<>();
	private HttpServer servidor;

	@BeforeEach
	void iniciar() throws IOException {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/", troca -> {
			caminho.set(troca.getRequestURI().getPath());
			pedidos.add(troca.getRequestMethod() + " " + troca.getRequestURI().getPath());
			if (atrasoMs > 0) {
				try {
					Thread.sleep(atrasoMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			autorizacao.set(troca.getRequestHeaders().getFirst("Authorization"));
			corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			corpos.add(corpo.get());
			Object[] proxima = roteiro.poll();
			byte[] bytes = (proxima == null ? resposta : (String) proxima[1]).getBytes(StandardCharsets.UTF_8);
			troca.sendResponseHeaders(proxima == null ? status : (int) proxima[0], bytes.length);
			troca.getResponseBody().write(bytes);
			troca.close();
		});
		servidor.start();
	}

	@AfterEach
	void parar() {
		servidor.stop(0);
	}

	private ConfiguracaoIa cfg(String chave) {
		return cfg("outro", chave);
	}

	private ConfiguracaoIa cfg(String provedor, String chave) {
		return new ConfiguracaoIa(true, provedor, "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1", "modelo-x", chave);
	}

	private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"Oi.\"}}]}";

	@Test
	void geminiPedeRaciocinioMinimoOsOutrosNao() {
		resposta = OK;

		cliente.revisar(cfg("gemini", "x"), "oi", Set.of());
		cliente.revisar(cfg("outro", "x"), "oi", Set.of());

		assertThat(mapper.readTree(corpos.get(0)).get("reasoning_effort").asString()).isEqualTo("minimal");
		assertThat(mapper.readTree(corpos.get(1)).has("reasoning_effort")).isFalse();
	}

	@Test
	void modeloQueNaoAceitaRaciocinioMinimoTentaDeNovoSem() {
		// o que o gemini-3.8-flash responde de verdade
		roteiro.add(new Object[] { 400, "[{\"error\":{\"code\":400,\"message\":\"Thinking level MINIMAL is not supported for this model.\"}}]" });
		roteiro.add(new Object[] { 200, OK });

		assertThat(cliente.revisar(cfg("gemini", "x"), "oi", Set.of())).isEqualTo("Oi.");
		assertThat(corpos).hasSize(2);
		assertThat(mapper.readTree(corpos.get(1)).has("reasoning_effort")).isFalse();
	}

	@Test
	void sobrecargaTentaDeNovoUmaVez() {
		String sobrecarga = "[{\"error\":{\"code\":503,\"message\":\"This model is currently experiencing high demand.\"}}]";
		roteiro.add(new Object[] { 503, sobrecarga });
		roteiro.add(new Object[] { 200, OK });
		assertThat(cliente.revisar(cfg("x"), "oi", Set.of())).isEqualTo("Oi.");

		roteiro.add(new Object[] { 503, sobrecarga });
		roteiro.add(new Object[] { 503, sobrecarga });
		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).isInstanceOf(FalhaIa.class)
				.hasMessageContaining("sobrecarregada");
	}

	@Test
	void mandaNoFormatoDaOpenAiELeAResposta() {
		resposta = """
				{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"Você não vem?"},"finish_reason":"stop"}],
				 "usage":{"total_tokens":12}}""";

		String revisado = cliente.revisar(cfg("segredo"), "voce nao vem", Set.of("zezinho"));

		assertThat(revisado).isEqualTo("Você não vem?");
		assertThat(caminho.get()).isEqualTo("/v1/chat/completions");
		assertThat(autorizacao.get()).isEqualTo("Bearer segredo");
		JsonNode pedido = mapper.readTree(corpo.get());
		assertThat(pedido.get("model").asString()).isEqualTo("modelo-x");
		assertThat(pedido.get("messages").get(1).get("content").asString()).isEqualTo("voce nao vem");
		assertThat(pedido.get("messages").get(0).get("content").asString()).contains("zezinho");
	}

	@Test
	void semChaveNaoMandaAutorizacao() {
		resposta = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

		cliente.revisar(cfg(""), "ok", Set.of());

		assertThat(autorizacao.get()).isNull();
	}

	@Test
	void errosViramMensagensCompreensiveis() {
		resposta = "{\"error\":{\"message\":\"quota\"}}";
		status = 429;
		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).isInstanceOf(FalhaIa.class)
				.hasMessageContaining("Limite de uso");

		status = 401;
		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).hasMessageContaining("Chave da API inválida");

		// o Gemini responde assim para chave errada (de verdade, com 400 e numa lista)
		resposta = "[{\"error\":{\"code\":400,\"message\":\"Please pass a valid API key\",\"status\":\"INVALID_ARGUMENT\"}}]";
		status = 400;
		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).hasMessageContaining("Chave da API inválida");

		resposta = "{\"error\":{\"message\":\"model 'qwen9' not found\"}}";
		status = 404;
		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).hasMessage("Endereço ou modelo não encontrado (404): model 'qwen9' not found");
	}

	@Test
	void respostaVaziaEhFalha() {
		resposta = "{\"choices\":[]}";

		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).isInstanceOf(FalhaIa.class);
	}

	@Test
	void chaveSoViajaPorHttps() {
		ConfiguracaoIa.validarUrl("https://generativelanguage.googleapis.com/v1beta/openai/");
		ConfiguracaoIa.validarUrl("http://localhost:11434/v1/");
		assertThatThrownBy(() -> ConfiguracaoIa.validarUrl("http://exemplo.com/v1/")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ConfiguracaoIa.validarUrl("ftp://localhost/")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sobrecargaDepoisDoLimiteNaoRepete() {
		// a resposta 503 já veio tarde: repetir deixaria a pessoa esperando o dobro
		atrasoMs = 3200;
		roteiro.add(new Object[] { 503, "{\"error\":{\"message\":\"high demand\"}}" });
		roteiro.add(new Object[] { 200, OK });

		assertThatThrownBy(() -> cliente.revisar(cfg("x"), "oi", Set.of())).hasMessageContaining("sobrecarregada");
		assertThat(corpos).hasSize(1);
	}

	@Test
	void preaquecerAbreAConexaoUmaVezSoEComAChave() throws Exception {
		resposta = "{\"data\":[]}";

		cliente.preaquecer(cfg("segredo"));
		cliente.preaquecer(cfg("segredo"));
		cliente.preaquecer(cfg("segredo"));

		esperarAte(() -> !pedidos.isEmpty());
		Thread.sleep(300);
		assertThat(pedidos).containsExactly("GET /v1/models");
		assertThat(autorizacao.get()).isEqualTo("Bearer segredo");
	}

	@Test
	void preaquecerNaoFazNadaComAIaDesligadaNemQuebraComServidorFora() throws Exception {
		ConfiguracaoIa desligada = new ConfiguracaoIa(false, "gemini", "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1",
				"modelo-x", "x");
		cliente.preaquecer(desligada);

		ClienteIa outro = new ClienteIa(mapper);
		outro.preaquecer(new ConfiguracaoIa(true, "x", "http://127.0.0.1:1/v1", "m", ""));

		Thread.sleep(300);
		assertThat(pedidos).isEmpty();
	}

	@Test
	void preaquecerNaoAtrasaQuemChama() {
		atrasoMs = 2000;
		resposta = "{}";

		long inicio = System.nanoTime();
		cliente.preaquecer(cfg("x"));

		assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(500);
	}

	@Test
	void reescreverPedeOTomEProtegeODicionario() {
		resposta = OK;

		cliente.reescrever(cfg("x"), "vc vem amanha?", ClienteIa.Tom.FORMAL, Set.of("zezinho"));

		JsonNode pedido = mapper.readTree(corpo.get());
		String instrucoes = pedido.get("messages").get(0).get("content").asString();
		assertThat(instrucoes).contains("formal").contains("zezinho");
		assertThat(pedido.get("messages").get(1).get("content").asString()).isEqualTo("vc vem amanha?");
	}

	@Test
	void cadaTomTemUmPedidoDiferente() {
		resposta = OK;

		for (ClienteIa.Tom tom : ClienteIa.Tom.values()) {
			cliente.reescrever(cfg("x"), "oi", tom, Set.of());
		}

		assertThat(corpos.stream().map(c -> mapper.readTree(c).get("messages").get(0).get("content").asString()).distinct())
				.hasSize(ClienteIa.Tom.values().length);
	}

	@Test
	void muitosPedidosEmParaleloNaoSeMisturam() {
		servidor.removeContext("/");
		servidor.createContext("/", troca -> {
			JsonNode pedido = mapper.readTree(troca.getRequestBody().readAllBytes());
			String eco = pedido.get("messages").get(1).get("content").asString();
			byte[] bytes = ("{\"choices\":[{\"message\":{\"content\":\"" + eco.toUpperCase() + "\"}}]}")
					.getBytes(StandardCharsets.UTF_8);
			troca.sendResponseHeaders(200, bytes.length);
			troca.getResponseBody().write(bytes);
			troca.close();
		});

		java.util.stream.IntStream.range(0, 300).parallel().forEach(i -> {
			String texto = "mensagem " + i;
			assertThat(cliente.revisar(cfg("x"), texto, Set.of())).isEqualTo(texto.toUpperCase());
		});
	}

	private static void esperarAte(java.util.function.BooleanSupplier condicao) throws InterruptedException {
		long limite = System.nanoTime() + 5_000_000_000L;
		while (!condicao.getAsBoolean()) {
			if (System.nanoTime() > limite) {
				throw new AssertionError("a condição não aconteceu em 5 s");
			}
			Thread.sleep(20);
		}
	}

}
