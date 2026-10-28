package com.corretorzap.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Sobe o servidor de verdade e testa a API por HTTP, como o painel faz. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"corretor.atalho.habilitado=false",
		"corretor.janela.habilitada=false",
		"corretor.config-dir=${java.io.tmpdir}/corretor-zap-it-${random.uuid}" })
class CorretorApiTest {

	@LocalServerPort
	int porta;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> enviar(String metodo, String caminho, String json) throws Exception {
		HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
		if (json != null) {
			pedido.header("Content-Type", "application/json");
		}
		pedido.method(metodo, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
		return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void corrigeTexto() throws Exception {
		HttpResponse<String> resposta = enviar("POST", "/api/corrigir", "{\"texto\":\"voce nao sabe\"}");

		assertThat(resposta.statusCode()).isEqualTo(200);
		assertThat(resposta.body()).contains("\"corrigido\":\"Você não sabe.\"");
		assertThat(resposta.headers().firstValue("Cache-Control")).hasValue("no-store");
	}

	@Test
	void recusaTextoVazioOuGrandeDemais() throws Exception {
		assertThat(enviar("POST", "/api/corrigir", "{\"texto\":\"  \"}").statusCode()).isEqualTo(400);
		assertThat(enviar("POST", "/api/corrigir", "{\"texto\":\"" + "a".repeat(20_001) + "\"}").statusCode())
				.isEqualTo(400);
		assertThat(enviar("POST", "/api/corrigir", "isso não é json").statusCode()).isEqualTo(400);
	}

	@Test
	void recusaConfigInvalida() throws Exception {
		String atalhoRuim = "{\"ativo\":true,\"atalho\":\"CTRL+BANANA\",\"selecionarTudo\":true}";
		assertThat(enviar("PUT", "/api/config", atalhoRuim).statusCode()).isEqualTo(400);

		String palavraEnorme = "{\"ativo\":true,\"atalho\":\"CTRL+SHIFT+SPACE\",\"selecionarTudo\":true,"
				+ "\"dicionario\":[\"" + "a".repeat(101) + "\"]}";
		assertThat(enviar("PUT", "/api/config", palavraEnorme).statusCode()).isEqualTo(400);

		String palavrasDemais = IntStream.rangeClosed(0, 5000).mapToObj(i -> "\"p" + i + "\"")
				.collect(Collectors.joining(",", "{\"ativo\":true,\"atalho\":\"CTRL+SHIFT+SPACE\",\"selecionarTudo\":true,\"dicionario\":[", "]}"));
		assertThat(enviar("PUT", "/api/config", palavrasDemais).statusCode()).isEqualTo(400);
	}

	@Test
	void dicionarioAceitaSoUmaPalavra() throws Exception {
		assertThat(enviar("POST", "/api/dicionario", "{\"palavra\":\"blz, vlw\"}").statusCode()).isEqualTo(400);
		assertThat(enviar("POST", "/api/dicionario", "{\"palavra\":\"blz\"}").body()).contains("\"blz\"");
	}

	@Test
	void naoAceitaCorpoDeFormularioDeOutroSite() throws Exception {
		HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/dicionario"))
				.header("Content-Type", "text/plain")
				.POST(HttpRequest.BodyPublishers.ofString("{\"palavra\":\"x\"}"))
				.build();

		assertThat(http.send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
	}

	@Test
	void erroNaoVazaDetalhesInternos() throws Exception {
		HttpResponse<String> resposta = enviar("POST", "/api/corrigir", "{\"texto\":");

		assertThat(resposta.body()).doesNotContain("Exception", "at com.", "at org.");
	}

}
