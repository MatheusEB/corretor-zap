package com.corretorzap.correcao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.languagetool.rules.ITSIssueType;
import org.languagetool.rules.RuleMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;

import jakarta.annotation.PreDestroy;

/**
 * Corrige texto em português do Brasil com o LanguageTool, aplicando sempre a primeira sugestão.
 * Sugestões de estilo e registro (formal/informal) são ignoradas: num chat elas mais atrapalham do que ajudam.
 * <p>
 * JLanguageTool não é thread-safe, então há um pequeno pool de instâncias (criadas sob demanda). Textos longos
 * são divididos em trechos que terminam em fim de frase e verificados em paralelo.
 */
@Service
public class CorretorService {

	private static final Logger log = LoggerFactory.getLogger(CorretorService.class);
	private static final Set<ITSIssueType> TIPOS_IGNORADOS = Set.of(ITSIssueType.Style, ITSIssueType.Register);
	static final int TAMANHO_TRECHO = 2000;
	static final String ERRO_COMUM = "CZ_ERRO_COMUM";
	private static final String MAIUSCULA_LT = "UPPERCASE_SENTENCE_START";

	// erros frequentes em chat que o dicionário do LanguageTool não pega; os ambíguos ("agente", "mais") ficam de fora
	private static final Map<String, String> ERROS_COMUNS = Map.of(
			"oque", "o que",
			"porisso", "por isso",
			"derrepente", "de repente",
			"concerteza", "com certeza",
			"conserteza", "com certeza",
			"apartir", "a partir",
			"seje", "seja",
			"esteje", "esteja",
			"menas", "menos");
	private static final Pattern PALAVRA = Pattern.compile("[\\p{L}\\p{M}]+");

	private final ConfiguracaoService configuracao;
	private final Semaphore vagas;
	private final ConcurrentLinkedQueue<Instancia> livres = new ConcurrentLinkedQueue<>();
	private final ExecutorService trechos = Executors.newVirtualThreadPerTaskExecutor();

	@Autowired
	public CorretorService(ConfiguracaoService configuracao, @Value("${corretor.instancias:0}") int instancias) {
		this.configuracao = configuracao;
		int tamanho = instancias > 0 ? instancias : Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
		this.vagas = new Semaphore(tamanho);
		log.info("Pool do LanguageTool: até {} instância(s)", tamanho);
	}

	public CorretorService(ConfiguracaoService configuracao) {
		this(configuracao, 0);
	}

	/** Carregar o dicionário leva alguns segundos; faz isso logo no início para o primeiro atalho ser rápido. */
	@EventListener(ApplicationReadyEvent.class)
	public void aquecer() {
		Thread.ofPlatform().daemon().name("aquecer-languagetool").start(() -> {
			corrigir("aquecendo o corretor");
			log.info("Corretor pronto");
		});
	}

	@PreDestroy
	void encerrar() {
		trechos.shutdownNow();
	}

	public ResultadoCorrecao corrigir(String texto) {
		Configuracao cfg = configuracao.atual();
		List<Achado> achados = cfg.regrasDesativadas().contains(ERRO_COMUM)
				? verificar(texto, cfg.regrasDesativadas())
				: verificarComErrosComuns(texto, cfg.regrasDesativadas());

		// onde o LanguageTool pediu maiúscula; se outra correção vencer ali, ela herda a maiúscula
		Set<Integer> inicioDeFrase = new HashSet<>();
		for (Achado achado : achados) {
			if (achado.regra().equals(MAIUSCULA_LT)) {
				inicioDeFrase.add(achado.inicio());
			}
		}
		List<Achado> todos = achados;
		achados = todos.stream()
				.filter(a -> !a.regra().equals(MAIUSCULA_LT) || todos.stream().noneMatch(
						outro -> outro != a && !outro.regra().equals(MAIUSCULA_LT) && outro.inicio() == a.inicio()))
				.toList();

		StringBuilder corrigido = new StringBuilder(texto);
		List<Correcao> correcoes = new ArrayList<>();
		int limite = texto.length();
		// de trás pra frente, para as posições do texto original continuarem válidas
		for (int i = achados.size() - 1; i >= 0; i--) {
			Achado achado = achados.get(i);
			if (achado.fim() > limite || !deveAplicar(achado, texto, cfg)) {
				continue;
			}
			String sugestao = achado.sugestoes().getFirst();
			if (inicioDeFrase.contains(achado.inicio()) && !sugestao.isEmpty()) {
				sugestao = Character.toUpperCase(sugestao.charAt(0)) + sugestao.substring(1);
			}
			corrigido.replace(achado.inicio(), achado.fim(), sugestao);
			correcoes.addFirst(new Correcao(
					achado.inicio(),
					achado.fim(),
					texto.substring(achado.inicio(), achado.fim()),
					sugestao,
					achado.mensagem(),
					achado.regra(),
					achado.descricaoRegra()));
			limite = achado.inicio();
		}

		List<Pontuador.Insercao> insercoes = comInstancia(instancia ->
				new Pontuador(cfg.regrasDesativadas(), instancia::etiquetas).pontuar(corrigido.toString()));
		if (insercoes.isEmpty()) {
			return new ResultadoCorrecao(texto, corrigido.toString(), correcoes);
		}
		List<Correcao> todas = incorporarPontuacao(texto, correcoes, insercoes);
		return new ResultadoCorrecao(texto, aplicar(texto, todas), todas);
	}

	private record Troca(int inicio, int fim, String certo) {
	}

	/**
	 * Erros comuns ("oque", "porisso") são trocados antes de o LanguageTool analisar: com a palavra desconhecida
	 * no meio, a análise gramatical dele se confunde ("porisso eu nao fui" → vírgula em "não, fui"). Depois, as
	 * posições voltam para o texto original.
	 */
	private List<Achado> verificarComErrosComuns(String texto, Set<String> regrasDesativadas) {
		List<Troca> trocas = new ArrayList<>();
		Matcher m = PALAVRA.matcher(texto);
		while (m.find()) {
			String certo = ERROS_COMUNS.get(m.group().toLowerCase());
			if (certo != null) {
				boolean maiuscula = Character.isUpperCase(m.group().charAt(0));
				trocas.add(new Troca(m.start(), m.end(), maiuscula ? Character.toUpperCase(certo.charAt(0)) + certo.substring(1) : certo));
			}
		}
		if (trocas.isEmpty()) {
			return verificar(texto, regrasDesativadas);
		}

		StringBuilder base = new StringBuilder();
		// para cada troca: onde ela fica no texto-base e quanto o tamanho mudou até ali
		int[] inicioNaBase = new int[trocas.size()];
		int[] fimNaBase = new int[trocas.size()];
		int cursor = 0;
		for (int i = 0; i < trocas.size(); i++) {
			Troca t = trocas.get(i);
			base.append(texto, cursor, t.inicio());
			inicioNaBase[i] = base.length();
			base.append(t.certo());
			fimNaBase[i] = base.length();
			cursor = t.fim();
		}
		base.append(texto.substring(cursor));

		List<Achado> resultado = new ArrayList<>();
		for (Achado a : verificar(base.toString(), regrasDesativadas)) {
			int deslocamento = 0;
			boolean sobreTroca = false;
			for (int i = 0; i < trocas.size(); i++) {
				if (a.inicio() < fimNaBase[i] && inicioNaBase[i] < a.fim()) {
					sobreTroca = true;
					break;
				}
				if (fimNaBase[i] <= a.inicio()) {
					Troca t = trocas.get(i);
					deslocamento += (fimNaBase[i] - inicioNaBase[i]) - (t.fim() - t.inicio());
				}
			}
			if (!sobreTroca) {
				resultado.add(new Achado(a.inicio() - deslocamento, a.fim() - deslocamento, a.sugestoes(), a.mensagem(),
						a.regra(), a.descricaoRegra(), a.tipo()));
			}
		}
		for (Troca t : trocas) {
			resultado.add(new Achado(t.inicio(), t.fim(), List.of(t.certo()), "Escreve-se “" + t.certo().toLowerCase() + "”.",
					ERRO_COMUM, "Erro comum de digitação", ITSIssueType.Misspelling));
		}
		resultado.sort(Comparator.comparingInt(Achado::inicio));
		return resultado;
	}

	/**
	 * A pontuação é calculada sobre o texto já corrigido; aqui cada sinal vira uma correção da palavra anterior
	 * no texto original ("festa" → "festa,"). Se essa palavra já tinha correção de ortografia, as duas se juntam.
	 */
	static List<Correcao> incorporarPontuacao(String original, List<Correcao> ortografia, List<Pontuador.Insercao> insercoes) {
		List<Correcao> resultado = new ArrayList<>(ortografia);
		List<Correcao> novas = new ArrayList<>();
		for (Pontuador.Insercao insercao : insercoes) {
			int deslocamento = 0;
			int mesclar = -1;
			int dentro = -1;
			boolean dentroDeOutra = false;
			for (int i = 0; i < ortografia.size(); i++) {
				Correcao c = ortografia.get(i);
				int inicio = c.inicio() + deslocamento;
				int fim = inicio + c.sugestao().length();
				if (fim == insercao.posicao()) {
					mesclar = i;
					break;
				}
				if (inicio < insercao.posicao() && insercao.posicao() < fim) {
					dentroDeOutra = true;
					dentro = inicio == insercao.posicao() - primeiraPalavra(c.sugestao()) ? i : -1;
					break;
				}
				if (fim > insercao.posicao()) {
					break;
				}
				deslocamento += c.sugestao().length() - (c.fim() - c.inicio());
			}
			if (dentroDeOutra) {
				// "porisso" → "por isso" no começo da frase: a maiúscula vai para a própria correção
				if (insercao.maiuscula() && insercao.sinal().isEmpty() && dentro >= 0) {
					Correcao c = resultado.get(dentro);
					resultado.set(dentro, new Correcao(c.inicio(), c.fim(), c.trecho(),
							Character.toUpperCase(c.sugestao().charAt(0)) + c.sugestao().substring(1),
							c.mensagem(), c.regra(), c.descricaoRegra()));
				}
				continue;
			}
			if (mesclar >= 0) {
				Correcao c = resultado.get(mesclar);
				resultado.set(mesclar, new Correcao(c.inicio(), c.fim(), c.trecho(), ajustar(c.sugestao(), insercao),
						c.mensagem() + " " + insercao.mensagem(), c.regra(), c.descricaoRegra()));
				continue;
			}
			int fim = insercao.posicao() - deslocamento;
			int inicio = fim;
			while (inicio > 0 && Character.isLetterOrDigit(original.charAt(inicio - 1))) {
				inicio--;
			}
			if (inicio == fim) {
				continue;
			}
			String palavra = original.substring(inicio, fim);
			novas.add(new Correcao(inicio, fim, palavra, ajustar(palavra, insercao), insercao.mensagem(), insercao.regra(),
					insercao.descricao()));
		}
		resultado.addAll(novas);
		resultado.sort(Comparator.comparingInt(Correcao::inicio));
		return resultado;
	}

	private static int primeiraPalavra(String texto) {
		int fim = 0;
		while (fim < texto.length() && Character.isLetterOrDigit(texto.charAt(fim))) {
			fim++;
		}
		return fim;
	}

	private static String ajustar(String palavra, Pontuador.Insercao insercao) {
		String base = insercao.maiuscula() ? Character.toUpperCase(palavra.charAt(0)) + palavra.substring(1) : palavra;
		return base + insercao.sinal();
	}

	private static String aplicar(String texto, List<Correcao> correcoes) {
		StringBuilder resultado = new StringBuilder(texto);
		for (int i = correcoes.size() - 1; i >= 0; i--) {
			Correcao c = correcoes.get(i);
			resultado.replace(c.inicio(), c.fim(), c.sugestao());
		}
		return resultado.toString();
	}

	private List<Achado> verificar(String texto, Set<String> regrasDesativadas) {
		List<int[]> partes = dividir(texto);
		if (partes.size() == 1) {
			return verificarTrecho(texto, 0, regrasDesativadas);
		}
		List<Future<List<Achado>>> futuros = new ArrayList<>();
		for (int[] parte : partes) {
			String trecho = texto.substring(parte[0], parte[1]);
			futuros.add(trechos.submit(() -> verificarTrecho(trecho, parte[0], regrasDesativadas)));
		}
		List<Achado> todos = new ArrayList<>();
		try {
			for (Future<List<Achado>> futuro : futuros) {
				todos.addAll(futuro.get());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			futuros.forEach(f -> f.cancel(true));
			throw new IllegalStateException("Correção interrompida", e);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
		}
		todos.sort(Comparator.comparingInt(Achado::inicio));
		return todos;
	}

	private List<Achado> verificarTrecho(String trecho, int deslocamento, Set<String> regrasDesativadas) {
		return comInstancia(instancia -> instancia.verificar(trecho, deslocamento, regrasDesativadas));
	}

	private <T> T comInstancia(Function<Instancia, T> uso) {
		vagas.acquireUninterruptibly();
		Instancia instancia = livres.poll();
		try {
			if (instancia == null) {
				instancia = new Instancia();
			}
			return uso.apply(instancia);
		} finally {
			if (instancia != null) {
				livres.offer(instancia);
			}
			vagas.release();
		}
	}

	/**
	 * Divide em trechos de até {@link #TAMANHO_TRECHO} caracteres, cortando de preferência em fim de frase
	 * ou de linha, para nenhuma regra de gramática ficar "pela metade".
	 */
	static List<int[]> dividir(String texto) {
		List<int[]> partes = new ArrayList<>();
		int inicio = 0;
		while (texto.length() - inicio > TAMANHO_TRECHO) {
			int fim = pontoDeCorte(texto, inicio);
			partes.add(new int[] { inicio, fim });
			inicio = fim;
		}
		partes.add(new int[] { inicio, texto.length() });
		return partes;
	}

	private static int pontoDeCorte(String texto, int inicio) {
		int maximo = inicio + TAMANHO_TRECHO;
		int minimo = inicio + TAMANHO_TRECHO / 2;
		int espaco = -1;
		for (int i = maximo; i > minimo; i--) {
			char anterior = texto.charAt(i - 1);
			if (anterior == '\n' || (Character.isWhitespace(texto.charAt(i)) && ".!?".indexOf(anterior) >= 0)) {
				return i;
			}
			if (espaco < 0 && Character.isWhitespace(anterior)) {
				espaco = i;
			}
		}
		return espaco > 0 ? espaco : maximo;
	}

	private boolean deveAplicar(Achado achado, String texto, Configuracao cfg) {
		if (achado.sugestoes().isEmpty() || TIPOS_IGNORADOS.contains(achado.tipo())) {
			return false;
		}
		// o LanguageTool não vê a quebra de linha como fim de frase e às vezes põe vírgula no fim da linha
		boolean virgulaNoFimDaLinha = achado.sugestoes().getFirst().endsWith(",")
				&& (achado.fim() == texto.length() || texto.charAt(achado.fim()) == '\n');
		if (virgulaNoFimDaLinha) {
			return false;
		}
		return !cfg.estaNoDicionario(texto.substring(achado.inicio(), achado.fim()));
	}

	private static String limparMensagem(String mensagem) {
		return mensagem.replace("<suggestion>", "“").replace("</suggestion>", "”");
	}

	/** Um problema encontrado pelo LanguageTool, com posições já relativas ao texto inteiro. */
	private record Achado(int inicio, int fim, List<String> sugestoes, String mensagem, String regra,
			String descricaoRegra, ITSIssueType tipo) {
	}

	/** Uma instância do LanguageTool e as regras que estão desligadas nela. Usada por uma thread de cada vez. */
	private static final class Instancia {

		private final JLanguageTool languageTool = new JLanguageTool(Languages.getLanguageForShortCode("pt-BR"));
		private final Set<String> regrasDesligadas = new HashSet<>();

		/** Etiquetas gramaticais (POS) de cada palavra, pela posição em que ela começa no texto. */
		Map<Integer, List<String>> etiquetas(String texto) {
			Map<Integer, List<String>> etiquetas = new HashMap<>();
			try {
				int inicioDaFrase = 0;
				for (AnalyzedSentence frase : languageTool.analyzeText(texto)) {
					for (AnalyzedTokenReadings token : frase.getTokensWithoutWhitespace()) {
						// etiqueta (NCMS000, VMIP1S0…) e lema ("lema:ter") de cada leitura possível da palavra
						List<String> tags = token.getReadings().stream()
								.flatMap(r -> Stream.of(r.getPOSTag(), r.getLemma() == null ? null : "lema:" + r.getLemma()))
								.filter(Objects::nonNull)
								.toList();
						etiquetas.put(inicioDaFrase + token.getStartPos(), tags);
					}
					inicioDaFrase += frase.getText().length();
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return etiquetas;
		}

		List<Achado> verificar(String trecho, int deslocamento, Set<String> regrasDesativadas) {
			if (!regrasDesligadas.equals(regrasDesativadas)) {
				regrasDesligadas.forEach(languageTool::enableRule);
				regrasDesativadas.forEach(languageTool::disableRule);
				regrasDesligadas.clear();
				regrasDesligadas.addAll(regrasDesativadas);
			}
			List<RuleMatch> matches;
			try {
				matches = languageTool.check(trecho);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return matches.stream()
					.map(m -> new Achado(
							m.getFromPos() + deslocamento,
							m.getToPos() + deslocamento,
							m.getSuggestedReplacements(),
							limparMensagem(m.getMessage()),
							m.getRule().getId(),
							m.getRule().getDescription(),
							m.getRule().getLocQualityIssueType()))
					.toList();
		}

	}

}
