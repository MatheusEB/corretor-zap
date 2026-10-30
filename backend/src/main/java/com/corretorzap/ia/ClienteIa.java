package com.corretorzap.ia;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pede a uma IA (API de chat no formato da OpenAI) a mensagem revisada. Só a revisão: quem transforma a resposta
 * em correções e decide o que fazer se a IA falhar é o {@link RevisorIa}.
 */
@Component
public class ClienteIa {

	/** Problema ao falar com a IA, já com uma mensagem que dá para mostrar no painel. */
	public static class FalhaIa extends RuntimeException {
		public FalhaIa(String mensagem) {
			super(mensagem);
		}

		public FalhaIa(String mensagem, Throwable causa) {
			super(mensagem, causa);
		}
	}

	static final String INSTRUCOES = """
			Você revisa mensagens de WhatsApp escritas em português do Brasil.
			Corrija somente: ortografia, acentuação, pontuação, letra maiúscula e concordância.
			Não mude o sentido, o tom nem as palavras escolhidas. Mantenha gírias, abreviações (vc, tb, pq, blz), \
			risadas (kkkk, rs, haha), emojis, nomes, links, números e quebras de linha exatamente como estão.
			Não acrescente nem tire frases, não responda à mensagem e não explique nada.
			Devolva apenas a mensagem revisada, sem aspas. Se não houver nada a corrigir, devolva a mensagem igual.""";
	/** Ajuste de tom pedido na prévia: aqui reescrever é o objetivo, então as palavras podem mudar. */
	public enum Tom {
		FORMAL("Reescreva a mensagem num tom mais formal e educado, adequado para trabalho."),
		CURTO("Reescreva a mensagem de forma mais curta e direta, sem perder nenhuma informação."),
		GENTIL("Reescreva a mensagem num tom mais gentil e simpático, sem exagerar.");

		private final String pedido;

		Tom(String pedido) {
			this.pedido = pedido;
		}
	}

	static final String INSTRUCOES_TOM = """
			Você ajusta mensagens de WhatsApp escritas em português do Brasil.
			%s
			Corrija também ortografia, acentuação e pontuação. Mantenha nomes, links, números, emojis e o idioma.
			Não responda à mensagem e não explique nada. Devolva apenas a mensagem reescrita, sem aspas.""";
	private static final int MAXIMO_PALAVRAS_PROTEGIDAS = 300;
	/** Tempo máximo de um pedido; a prévia já está na tela com o corretor local enquanto isso. */
	private static final Duration ESPERA = Duration.ofSeconds(10);
	private static final Duration ESPERA_ANTES_DE_REPETIR = Duration.ofMillis(400);
	/** Depois disso não vale repetir um 503: a pessoa já está há tempo demais com o selo "revisando". */
	private static final Duration LIMITE_PARA_REPETIR = Duration.ofSeconds(3);
	/** O HttpClient fecha a conexão parada; não adianta pré-aquecer mais vezes que isso. */
	private static final long INTERVALO_PREAQUECER_NS = Duration.ofSeconds(20).toNanos();
	private static final String RACIOCINIO_GEMINI = "minimal";

	private final ObjectMapper mapper;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private volatile long ultimoPreaquecimento = System.nanoTime() - INTERVALO_PREAQUECER_NS;

	public ClienteIa(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Resposta(List<Escolha> choices) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Escolha(Mensagem message) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Mensagem(String content) {
	}

	/**
	 * @param protegidas palavras do dicionário pessoal, que a IA não deve trocar
	 * @return a mensagem revisada, como a IA devolveu (sem limpeza)
	 */
	public String revisar(ConfiguracaoIa cfg, String texto, Set<String> protegidas) {
		return pedir(cfg, comProtegidas(INSTRUCOES, protegidas), texto);
	}

	/** @return a mensagem reescrita no tom pedido, como a IA devolveu (sem limpeza) */
	public String reescrever(ConfiguracaoIa cfg, String texto, Tom tom, Set<String> protegidas) {
		return pedir(cfg, comProtegidas(INSTRUCOES_TOM.formatted(tom.pedido), protegidas), texto);
	}

	/**
	 * Abre a conexão com o provedor sem esperar (DNS + TCP + TLS custam 150–400 ms), para o pedido de verdade, que
	 * sai logo depois do Ctrl+C, já encontrar a conexão pronta. Chamado quando o atalho é apertado.
	 */
	public void preaquecer(ConfiguracaoIa cfg) {
		long agora = System.nanoTime();
		if (!cfg.pronta() || agora - ultimoPreaquecimento < INTERVALO_PREAQUECER_NS) {
			return;
		}
		ultimoPreaquecimento = agora;
		HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(cfg.url() + "models")).timeout(ESPERA).GET();
		if (!cfg.chave().isEmpty()) {
			pedido.header("Authorization", "Bearer " + cfg.chave());
		}
		try {
			// a resposta não importa (nem o status): só a conexão aberta, que o HttpClient reaproveita
			http.sendAsync(pedido.build(), HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
		} catch (RuntimeException e) {
			// endereço inválido: o pedido de verdade vai mostrar o erro
		}
	}

	private static String comProtegidas(String instrucoes, Set<String> protegidas) {
		if (protegidas.isEmpty()) {
			return instrucoes;
		}
		return instrucoes + "\nNunca altere estas palavras: "
				+ protegidas.stream().limit(MAXIMO_PALAVRAS_PROTEGIDAS).collect(Collectors.joining(", ")) + ".";
	}

	private String pedir(ConfiguracaoIa cfg, String instrucoes, String texto) {
		long inicio = System.nanoTime();
		Map<String, Object> corpo = new LinkedHashMap<>();
		corpo.put("model", cfg.modelo());
		corpo.put("temperature", 0);
		corpo.put("messages", List.of(
				Map.of("role", "system", "content", instrucoes),
				Map.of("role", "user", "content", texto)));
		// os Gemini novos "pensam" antes de responder: 13 s em vez de 1 s, sem corrigir melhor
		if (cfg.provedor().equals("gemini")) {
			corpo.put("reasoning_effort", RACIOCINIO_GEMINI);
		}

		HttpResponse<String> resposta = enviar(cfg, corpo);
		if (resposta.statusCode() == 400 && corpo.containsKey("reasoning_effort")
				&& resposta.body().toLowerCase().contains("thinking")) {
			// modelo que não aceita "minimal" (gemini-3.8-flash): vai sem
			corpo.remove("reasoning_effort");
			resposta = enviar(cfg, corpo);
		}
		if (resposta.statusCode() == 503 && System.nanoTime() - inicio < LIMITE_PARA_REPETIR.toNanos()) {
			// "high demand" no plano gratuito costuma passar na hora
			esperar(ESPERA_ANTES_DE_REPETIR);
			resposta = enviar(cfg, corpo);
		}

		if (resposta.statusCode() / 100 != 2) {
			throw new FalhaIa(explicar(resposta.statusCode(), mensagemDoProvedor(resposta.body())));
		}
		try {
			Resposta r = mapper.readValue(resposta.body(), Resposta.class);
			String conteudo = r.choices() == null || r.choices().isEmpty() || r.choices().getFirst().message() == null
					? null
					: r.choices().getFirst().message().content();
			if (conteudo == null || conteudo.isBlank()) {
				throw new FalhaIa("A IA respondeu vazio");
			}
			return conteudo;
		} catch (JacksonException e) {
			throw new FalhaIa("A resposta da IA veio num formato inesperado", e);
		}
	}

	private HttpResponse<String> enviar(ConfiguracaoIa cfg, Map<String, Object> corpo) {
		HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(cfg.url() + "chat/completions"))
				.timeout(ESPERA)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo)));
		if (!cfg.chave().isEmpty()) {
			pedido.header("Authorization", "Bearer " + cfg.chave());
		}
		try {
			return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
		} catch (java.net.http.HttpTimeoutException e) {
			throw new FalhaIa("A IA demorou demais para responder", e);
		} catch (IOException e) {
			throw new FalhaIa("Não foi possível conectar à IA (" + cfg.url() + ")", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FalhaIa("Pedido à IA interrompido", e);
		}
	}

	private static void esperar(Duration tempo) {
		try {
			Thread.sleep(tempo);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FalhaIa("Pedido à IA interrompido", e);
		}
	}

	static String explicar(int status, String doProvedor) {
		// o Gemini responde 400 (e não 401) para chave inválida
		boolean chaveInvalida = doProvedor != null && doProvedor.toLowerCase().contains("api key");
		if (status == 401 || status == 403 || chaveInvalida) {
			return "Chave da API inválida ou sem permissão (" + status + ")";
		}
		String detalhe = doProvedor == null ? "" : ": " + doProvedor;
		return switch (status) {
			case 400 -> "A IA recusou o pedido (400)" + (doProvedor == null ? ": confira o nome do modelo" : detalhe);
			case 404 -> "Endereço ou modelo não encontrado (404)" + detalhe;
			case 429 -> "Limite de uso da IA atingido (429): no plano gratuito, espere um pouco";
			case 503 -> "A IA está sobrecarregada agora (503): tente de novo em instantes";
			default -> "A IA respondeu com erro " + status + detalhe;
		};
	}

	/** "error.message" do corpo; o Gemini manda dentro de uma lista: [{"error": {...}}]. */
	private String mensagemDoProvedor(String corpo) {
		try {
			JsonNode raiz = mapper.readTree(corpo);
			JsonNode erro = (raiz.isArray() ? raiz.path(0) : raiz).path("error");
			String mensagem = erro.isString() ? erro.asString() : erro.path("message").asString("");
			return mensagem.isBlank() ? null : mensagem.length() > 200 ? mensagem.substring(0, 200) + "…" : mensagem;
		} catch (JacksonException e) {
			return null;
		}
	}

}
