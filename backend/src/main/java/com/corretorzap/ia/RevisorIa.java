package com.corretorzap.ia;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.Correcao;
import com.corretorzap.correcao.CorretorService;
import com.corretorzap.correcao.ResultadoCorrecao;
import com.corretorzap.ia.ClienteIa.FalhaIa;
import com.corretorzap.ia.ClienteIa.Tom;

import jakarta.annotation.PreDestroy;

/**
 * Correção do atalho: com a IA ligada, ela revisa a mensagem; se a IA falhar (sem internet, limite do plano
 * gratuito, resposta estranha), o corretor local assume e o atalho continua funcionando.
 */
@Service
public class RevisorIa {

	private static final Logger log = LoggerFactory.getLogger(RevisorIa.class);
	/** Mensagens maiores que isso ficam com o corretor local (gasto de cota e diferença lenta). */
	static final int MAXIMO_CARACTERES = 4000;
	/** Fração das palavras que precisa continuar igual (fora acento e maiúscula) para ser correção, não reescrita. */
	private static final double PALAVRAS_MANTIDAS = 0.5;
	private static final int MINIMO_DE_PALAVRAS = 4;
	private static final Pattern PALAVRA = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
	private static final Pattern BLOCO_DE_CODIGO = Pattern.compile("^```[\\w-]*\\R(.*?)\\R?```$", Pattern.DOTALL);

	private static final int TAMANHO_CACHE = 64;

	private final CorretorService corretor;
	private final ConfiguracaoService configuracao;
	private final ConfiguracaoIaService configuracaoIa;
	private final ClienteIa cliente;
	private final ExecutorService pedidos = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<String, ResultadoCorrecao> cache = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ResultadoCorrecao> maisAntigo) {
			return size() > TAMANHO_CACHE;
		}
	};

	public RevisorIa(CorretorService corretor, ConfiguracaoService configuracao, ConfiguracaoIaService configuracaoIa,
			ClienteIa cliente) {
		this.corretor = corretor;
		this.configuracao = configuracao;
		this.configuracaoIa = configuracaoIa;
		this.cliente = cliente;
	}

	/**
	 * A correção em duas etapas da prévia: a local sai na hora e a da IA chega depois.
	 *
	 * @param local o resultado do corretor local (dezenas de ms)
	 * @param ia    a revisão da IA, ou null se a IA está desligada ou a mensagem é longa demais; termina com
	 *              {@link FalhaIa} se a IA falhar
	 */
	public record Etapas(ResultadoCorrecao local, CompletableFuture<ResultadoCorrecao> ia) {
	}

	public ResultadoCorrecao corrigir(String texto) {
		ConfiguracaoIa ia = configuracaoIa.atual();
		if (!usaIa(ia, texto)) {
			return corretor.corrigir(texto);
		}
		try {
			return comIaEmCache(ia, texto);
		} catch (FalhaIa e) {
			log.warn("IA indisponível ({}); usando o corretor local", e.getMessage());
			return corretor.corrigir(texto);
		}
	}

	public Etapas corrigirEmEtapas(String texto) {
		ConfiguracaoIa ia = configuracaoIa.atual();
		if (!usaIa(ia, texto)) {
			return new Etapas(corretor.corrigir(texto), null);
		}
		// a IA sai primeiro: o corretor local roda enquanto o pedido viaja
		CompletableFuture<ResultadoCorrecao> revisao = CompletableFuture.supplyAsync(() -> comIaEmCache(ia, texto), pedidos);
		return new Etapas(corretor.corrigir(texto), revisao);
	}

	/** Reescreve no tom pedido (botões da prévia). Aqui trocar palavras é o esperado, então não há trava. */
	public ResultadoCorrecao reescrever(String texto, Tom tom) {
		ConfiguracaoIa ia = configuracaoIa.atual();
		if (!usaIa(ia, texto)) {
			throw new FalhaIa("Ligue a IA no painel para ajustar o tom");
		}
		Configuracao cfg = configuracao.atual();
		String reescrito = limpar(texto, cliente.reescrever(ia, texto, tom, cfg.dicionario()));
		return ResultadoCorrecao.aplicando(texto, Diferencas.entre(texto, reescrito));
	}

	public CompletableFuture<ResultadoCorrecao> reescreverEmSegundoPlano(String texto, Tom tom) {
		return CompletableFuture.supplyAsync(() -> reescrever(texto, tom), pedidos);
	}

	/** Abre a conexão com a IA enquanto o atalho ainda está sendo apertado. */
	public void preaquecer() {
		cliente.preaquecer(configuracaoIa.atual());
	}

	public boolean iaLigada() {
		return configuracaoIa.atual().pronta();
	}

	@PreDestroy
	void encerrar() {
		pedidos.shutdownNow();
	}

	private static boolean usaIa(ConfiguracaoIa ia, String texto) {
		return ia.pronta() && texto.length() <= MAXIMO_CARACTERES;
	}

	/** A mesma mensagem de novo (cancelou e apertou o atalho outra vez) não gasta cota nem espera. */
	private ResultadoCorrecao comIaEmCache(ConfiguracaoIa ia, String texto) {
		Configuracao cfg = configuracao.atual();
		String chave = ia.url() + '\u0000' + ia.modelo() + '\u0000' + cfg.dicionario().hashCode() + '\u0000' + texto;
		synchronized (cache) {
			ResultadoCorrecao guardado = cache.get(chave);
			if (guardado != null) {
				return guardado;
			}
		}
		ResultadoCorrecao resultado = comIa(ia, texto);
		synchronized (cache) {
			cache.put(chave, resultado);
		}
		return resultado;
	}

	/** Só a IA, sem plano B: o botão "Testar" do painel precisa ver o erro. */
	public ResultadoCorrecao comIa(ConfiguracaoIa ia, String texto) {
		Configuracao cfg = configuracao.atual();
		String revisado = limpar(texto, cliente.revisar(ia, texto, cfg.dicionario()));
		if (reescreveu(texto, revisado)) {
			throw new FalhaIa("A IA reescreveu a mensagem em vez de só corrigir");
		}
		List<Correcao> correcoes = Diferencas.entre(texto, revisado).stream()
				.filter(c -> !temPalavraDoDicionario(c.trecho(), cfg))
				.toList();
		return ResultadoCorrecao.aplicando(texto, correcoes);
	}

	/**
	 * Tira o que a IA às vezes põe em volta (bloco de código, aspas) e devolve os espaços das pontas e o tipo de
	 * quebra de linha do original: o WhatsApp no Windows copia com \r\n e a IA responde com \n.
	 */
	static String limpar(String original, String resposta) {
		String texto = resposta.strip();
		Matcher bloco = BLOCO_DE_CODIGO.matcher(texto);
		if (bloco.matches()) {
			texto = bloco.group(1).strip();
		}
		String semPontas = original.strip();
		boolean aspasNovas = texto.length() >= 2 && texto.startsWith("\"") && texto.endsWith("\"")
				&& !(semPontas.startsWith("\"") && semPontas.endsWith("\""));
		if (aspasNovas) {
			texto = texto.substring(1, texto.length() - 1).strip();
		}
		texto = texto.replace("\r\n", "\n");
		if (original.contains("\r\n")) {
			texto = texto.replace("\n", "\r\n");
		}
		int inicio = 0;
		while (inicio < original.length() && Character.isWhitespace(original.charAt(inicio))) {
			inicio++;
		}
		int fim = original.length();
		while (fim > inicio && Character.isWhitespace(original.charAt(fim - 1))) {
			fim--;
		}
		return original.substring(0, inicio) + texto + original.substring(fim);
	}

	/**
	 * Corrigir acento, maiúscula e pontuação não conta; o que conta é quantas palavras do original sobraram.
	 * Se menos da metade sobrou, a IA escreveu outra mensagem (ou respondeu a ela).
	 */
	static boolean reescreveu(String original, String revisado) {
		List<String> antes = palavrasSimples(original);
		List<String> depois = palavrasSimples(revisado);
		if (antes.size() < MINIMO_DE_PALAVRAS) {
			return depois.size() > antes.size() + MINIMO_DE_PALAVRAS;
		}
		int[][] lcs = new int[antes.size() + 1][depois.size() + 1];
		for (int i = antes.size() - 1; i >= 0; i--) {
			for (int j = depois.size() - 1; j >= 0; j--) {
				lcs[i][j] = antes.get(i).equals(depois.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}
		int mantidas = lcs[0][0];
		return mantidas < antes.size() * PALAVRAS_MANTIDAS || mantidas < depois.size() * PALAVRAS_MANTIDAS;
	}

	private static List<String> palavrasSimples(String texto) {
		String simples = Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return PALAVRA.matcher(simples).results().map(MatchResult::group).toList();
	}

	private static boolean temPalavraDoDicionario(String trecho, Configuracao cfg) {
		Matcher m = PALAVRA.matcher(trecho);
		while (m.find()) {
			if (cfg.estaNoDicionario(m.group())) {
				return true;
			}
		}
		return false;
	}

}
