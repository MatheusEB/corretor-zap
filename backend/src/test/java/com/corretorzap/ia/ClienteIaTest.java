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

}
