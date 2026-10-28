package com.corretorzap.correcao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vírgulas e pontuação final, que o LanguageTool quase não sugere em português.
 * Roda sobre o texto já corrigido na ortografia e devolve onde inserir cada sinal.
 * As regras são conservadoras: na dúvida, não mexe (uma vírgula errada incomoda mais do que uma faltando).
 */
final class Pontuador {

	static final String VOCATIVO = "CZ_VIRGULA_VOCATIVO";
	static final String ADVERSATIVA = "CZ_VIRGULA_ADVERSATIVA";
	static final String ORACAO = "CZ_VIRGULA_ORACAO";
	static final String ENUMERACAO = "CZ_VIRGULA_ENUMERACAO";
	static final String PONTO_FINAL = "CZ_PONTO_FINAL";
	static final String INTERROGACAO = "CZ_INTERROGACAO";
	static final String MAIUSCULA = "CZ_MAIUSCULA";

	/**
	 * Ajuste na palavra que termina em {@code posicao}: acrescentar {@code sinal} depois dela e/ou
	 * trocar a primeira letra por maiúscula.
	 */
	record Insercao(int posicao, String sinal, boolean maiuscula, String regra, String descricao, String mensagem) {
	}

	private record Palavra(int inicio, int fim, String texto) {
		String minuscula() {
			return texto.toLowerCase();
		}
	}

	private static final Pattern PALAVRA = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+(?:[-'][\\p{L}\\p{M}\\p{N}]+)*");
	private static final Pattern RISADA = Pattern.compile("(k{2,}|(ha){2,}h?|(he){2,}|(hi){2,}|rs+|ks(ks)+k?|a?ha(ha)+)");

	private static final Set<String> SAUDACOES = Set.of("oi", "oie", "oii", "olá", "ola", "opa", "fala", "salve", "eai", "eaí");
	private static final Set<String> SAUDACOES_DUPLAS = Set.of("bom dia", "boa tarde", "boa noite", "boa madrugada", "e aí", "e ai");
	private static final Set<String> TUDO_BEM = Set.of("tudo bem", "tudo bom", "td bem", "td bom");
	private static final Set<String> TUDO_BEM_SIMPLES = Set.of("beleza", "blz", "né");
	// "bom dia GENTE, vamos começar": quem é chamado logo depois da saudação
	private static final Set<String> VOCATIVOS = Set.of("gente", "pessoal", "galera", "turma", "família", "amigo", "amiga",
			"amigos", "amigas", "mãe", "pai", "mano", "mana", "cara", "amor", "professor", "professora", "chefe", "vó", "vô",
			"tia", "tio", "querido", "querida", "meninos", "meninas");
	// despedidas no fim da mensagem: "vou dormir, boa noite"
	private static final Set<String> DESPEDIDAS = Set.of("tchau", "beijos", "beijo", "bjs", "abraços", "abraço");
	private static final Set<String> ADVERSATIVAS = Set.of("mas", "porém", "contudo", "entretanto", "todavia");
	private static final Set<String> SUBORDINATIVAS = Set.of("se", "quando", "caso", "enquanto", "embora");
	private static final Set<String> SUBORDINATIVAS_DUPLAS = Set.of("assim que", "depois que", "logo que", "sempre que",
			"antes que", "desde que");
	// palavras que costumam abrir a oração principal: "se der certo, EU te aviso", "quando chegar em casa, TE ligo"
	private static final Set<String> INICIO_ORACAO_PRINCIPAL = Set.of("eu", "te", "me", "você", "vc", "nós", "ele", "ela",
			"vou", "vamos", "já");
	private static final Set<String> INTERROGATIVAS = Set.of("quem", "quando", "onde", "aonde", "como", "qual", "quais",
			"quanto", "quanta", "quantos", "quantas", "cadê", "será", "pq");
	private static final Set<String> QUE_PERGUNTA = Set.of("horas", "hora", "dia", "foi", "houve", "tal");
	private static final Set<String> FIM_DE_PERGUNTA = Set.of("tudo bem", "tudo bom", "td bem", "td bom", "beleza", "blz", "né");
	// "tudo certo" só é pergunta depois de uma saudação ("opa, tudo certo?"); sozinho costuma ser resposta
	private static final Set<String> FIM_DE_PERGUNTA_APOS_SAUDACAO = Set.of("tudo certo", "td certo");
	private static final Set<String> LEMAS_AUXILIARES = Set.of("lema:ter", "lema:haver", "lema:estar", "lema:ser", "lema:ir");
	private static final int PALAVRAS_PARA_PONTO_FINAL = 3;

	private final Set<String> desativadas;
	private final Function<String, Map<Integer, List<String>>> etiquetar;

	/**
	 * @param etiquetar devolve, para um trecho de texto, as etiquetas gramaticais (POS) de cada palavra pela
	 *                  posição em que ela começa
	 */
	Pontuador(Set<String> desativadas, Function<String, Map<Integer, List<String>>> etiquetar) {
		this.desativadas = desativadas;
		this.etiquetar = etiquetar;
	}

	List<Insercao> pontuar(String texto) {
		Map<Integer, Insercao> insercoes = new LinkedHashMap<>();
		int inicioLinha = 0;
		// o começo do texto também começa uma frase ("voce viu" → "Você viu")
		boolean linhaAnteriorFechouFrase = true;
		for (String linha : texto.split("\n", -1)) {
			linhaAnteriorFechouFrase = pontuarLinha(linha, inicioLinha, linhaAnteriorFechouFrase, insercoes);
			inicioLinha += linha.length() + 1;
		}
		return insercoes.values().stream().sorted((a, b) -> Integer.compare(a.posicao(), b.posicao())).toList();
	}

	/** @return true se a linha termina uma frase (já terminava ou ganhou ponto/interrogação) */
	private boolean pontuarLinha(String linha, int deslocamento, boolean comecaFrase, Map<Integer, Insercao> saida) {
		List<Palavra> palavras = new ArrayList<>();
		Matcher m = PALAVRA.matcher(linha);
		while (m.find()) {
			palavras.add(new Palavra(m.start(), m.end(), m.group()));
		}
		if (palavras.isEmpty()) {
			return comecaFrase && linha.isBlank();
		}
		Map<Integer, Insercao> daLinha = new LinkedHashMap<>();
		// as etiquetas custam uma análise do LanguageTool; só calcula se alguma regra precisar
		var cache = new Object() {
			Map<Integer, List<String>> valor;
		};
		Supplier<Map<Integer, List<String>>> etiquetas = () -> {
			if (cache.valor == null) {
				cache.valor = etiquetar.apply(linha);
			}
			return cache.valor;
		};

		int conteudo = vocativo(linha, palavras, daLinha);
		tudoBem(linha, palavras, daLinha);
		despedidas(linha, palavras, daLinha);
		adversativas(linha, palavras, daLinha);
		boolean temOracaoInicial = oracaoInicial(linha, palavras, conteudo, etiquetas, daLinha);
		enumeracoes(linha, palavras, etiquetas, daLinha);
		boolean fechou = pontoFinal(linha, palavras, conteudo, temOracaoInicial, daLinha);

		// "oi tudo bem\nme liga": depois do "?" a linha seguinte começa outra frase
		Palavra primeira = palavras.getFirst();
		// respostas de uma palavra ("ok", "né") ficam como a pessoa escreveu
		boolean podeCapitalizar = palavras.size() >= 2 && !RISADA.matcher(primeira.minuscula()).matches()
				&& !linha.stripLeading().startsWith("http");
		if (comecaFrase && podeCapitalizar && Character.isLowerCase(primeira.texto().charAt(0)) && !desativadas.contains(MAIUSCULA)) {
			Insercao existente = daLinha.get(primeira.fim());
			daLinha.put(primeira.fim(), existente != null
					? new Insercao(existente.posicao(), existente.sinal(), true, existente.regra(), existente.descricao(),
							existente.mensagem())
					: new Insercao(primeira.fim(), "", true, MAIUSCULA, "Maiúscula no início da frase",
							"Comece a frase com letra maiúscula."));
		}

		daLinha.forEach((posicao, insercao) -> saida.putIfAbsent(deslocamento + posicao,
				new Insercao(deslocamento + posicao, insercao.sinal(), insercao.maiuscula(), insercao.regra(),
						insercao.descricao(), insercao.mensagem())));
		String fim = linha.strip();
		boolean soSaudacao = conteudo > 0 && conteudo == palavras.size();
		return fechou || soSaudacao || fim.endsWith(".") || fim.endsWith("?") || fim.endsWith("!");
	}

	/** "oi joao" → "oi, joao". Devolve o índice da primeira palavra depois da saudação. */
	private int vocativo(String linha, List<Palavra> palavras, Map<Integer, Insercao> saida) {
		int tamanho = 0;
		if (palavras.size() >= 2 && SAUDACOES_DUPLAS.contains(par(palavras, 0))) {
			tamanho = 2;
		} else if (SAUDACOES.contains(palavras.getFirst().minuscula())) {
			tamanho = 1;
		}
		if (tamanho == 0 || palavras.size() <= tamanho) {
			return tamanho;
		}
		if (semPontuacaoEntre(linha, palavras, tamanho - 1)) {
			adicionar(saida, palavras.get(tamanho - 1).fim(), ",", VOCATIVO, "Vírgula depois da saudação",
					"Separe a saudação do resto da mensagem com vírgula.");
		}
		// "bom dia gente vamos começar" → "bom dia, gente, vamos começar"
		Palavra chamado = palavras.get(tamanho);
		boolean ehVocativo = VOCATIVOS.contains(chamado.minuscula()) || Character.isUpperCase(chamado.texto().charAt(0));
		if (ehVocativo && palavras.size() > tamanho + 1 && semPontuacaoEntre(linha, palavras, tamanho)) {
			adicionar(saida, chamado.fim(), ",", VOCATIVO, "Vírgula depois do vocativo",
					"Separe com vírgula o nome de quem você está chamando.");
		}
		return tamanho;
	}

	/** "vou dormir boa noite" → "vou dormir, boa noite". */
	private void despedidas(String linha, List<Palavra> palavras, Map<Integer, Insercao> saida) {
		for (int i = 1; i < palavras.size(); i++) {
			boolean dupla = i + 1 < palavras.size() && SAUDACOES_DUPLAS.contains(par(palavras, i)) && i + 2 == palavras.size();
			boolean simples = DESPEDIDAS.contains(palavras.get(i).minuscula()) && i + 1 == palavras.size();
			if ((dupla || simples) && semPontuacaoEntre(linha, palavras, i - 1)) {
				adicionar(saida, palavras.get(i - 1).fim(), ",", VOCATIVO, "Vírgula antes da despedida",
						"Separe a despedida do resto da mensagem com vírgula.");
			}
		}
	}

	/** "oi joão tudo bem" → "oi joão, tudo bem". */
	private void tudoBem(String linha, List<Palavra> palavras, Map<Integer, Insercao> saida) {
		for (int i = 1; i < palavras.size(); i++) {
			boolean dupla = i + 1 < palavras.size() && TUDO_BEM.contains(par(palavras, i));
			boolean simples = TUDO_BEM_SIMPLES.contains(palavras.get(i).minuscula());
			if ((dupla || simples) && semPontuacaoEntre(linha, palavras, i - 1)) {
				adicionar(saida, palavras.get(i - 1).fim(), ",", VOCATIVO, "Vírgula depois da saudação",
						"Separe o “tudo bem” do resto da mensagem com vírgula.");
			}
		}
	}

	/** "fui mas ele não foi" → "fui, mas ele não foi". */
	private void adversativas(String linha, List<Palavra> palavras, Map<Integer, Insercao> saida) {
		for (int i = 1; i < palavras.size(); i++) {
			String palavra = palavras.get(i).minuscula();
			boolean noEntanto = palavra.equals("no") && i + 1 < palavras.size() && palavras.get(i + 1).minuscula().equals("entanto");
			if ((ADVERSATIVAS.contains(palavra) || noEntanto) && semPontuacaoEntre(linha, palavras, i - 1)) {
				adicionar(saida, palavras.get(i - 1).fim(), ",", ADVERSATIVA, "Vírgula antes de “" + palavra + "”",
						"Conjunções como “mas” e “porém” pedem vírgula antes.");
			}
		}
	}

	/** "se der certo eu te aviso" → "se der certo, eu te aviso". */
	private boolean oracaoInicial(String linha, List<Palavra> palavras, int conteudo,
			Supplier<Map<Integer, List<String>>> etiquetasDaLinha, Map<Integer, Insercao> saida) {
		if (palavras.size() <= conteudo) {
			return false;
		}
		int tamanho;
		if (conteudo + 1 < palavras.size() && SUBORDINATIVAS_DUPLAS.contains(par(palavras, conteudo))) {
			tamanho = 2;
		} else if (SUBORDINATIVAS.contains(palavras.get(conteudo).minuscula())) {
			tamanho = 1;
		} else {
			return false;
		}
		for (int j = conteudo + tamanho + 1; j < palavras.size(); j++) {
			Palavra candidata = palavras.get(j);
			boolean aGente = candidata.minuscula().equals("a") && j + 1 < palavras.size()
					&& palavras.get(j + 1).minuscula().equals("gente");
			if (!INICIO_ORACAO_PRINCIPAL.contains(candidata.minuscula()) && !aGente) {
				continue;
			}
			// "se eu TE ligar": pronomes seguidos formam um bloco; a oração principal não começa no meio dele
			if (INICIO_ORACAO_PRINCIPAL.contains(palavras.get(j - 1).minuscula())) {
				continue;
			}
			if (!semPontuacaoEntre(linha, palavras, j - 1)) {
				return false;
			}
			// "se eu tivesse te visto": depois de verbo auxiliar ainda é a mesma oração
			if (etiquetasDaLinha.get().getOrDefault(palavras.get(j - 1).inicio(), List.of()).stream()
					.anyMatch(t -> t.startsWith("VA") || LEMAS_AUXILIARES.contains(t))) {
				continue;
			}
			adicionar(saida, palavras.get(j - 1).fim(), ",", ORACAO, "Vírgula depois da oração inicial",
					"Quando a frase começa com “se”, “quando”, “caso”…, separe essa parte com vírgula.");
			return true;
		}
		return false;
	}

	/** "comprei pão leite e ovos" → "comprei pão, leite e ovos". */
	private void enumeracoes(String linha, List<Palavra> palavras, Supplier<Map<Integer, List<String>>> etiquetasDaLinha,
			Map<Integer, Insercao> saida) {
		boolean temE = palavras.stream().anyMatch(p -> p.minuscula().equals("e"));
		if (!temE) {
			return;
		}
		Map<Integer, List<String>> etiquetas = etiquetasDaLinha.get();
		for (int k = 2; k < palavras.size(); k++) {
			if (!palavras.get(k).minuscula().equals("e") || !ehSubstantivo(etiquetas, palavras.get(k - 1))) {
				continue;
			}
			// volta pela lista: itens são substantivos (com artigo ou "de" no meio: "o caderno", "pão de queijo")
			List<Integer> fronteiras = new ArrayList<>();
			for (int t = k - 2; t >= 0; t--) {
				Palavra atual = palavras.get(t);
				Palavra seguinte = palavras.get(t + 1);
				if (!semPontuacaoEntre(linha, palavras, t)) {
					break;
				}
				if (ehSubstantivo(etiquetas, atual)) {
					if (ehSubstantivo(etiquetas, seguinte) || ehArtigo(etiquetas, seguinte)) {
						fronteiras.add(atual.fim());
					}
				} else if (!ehArtigo(etiquetas, atual) && !ehPreposicaoDe(atual)) {
					break;
				}
			}
			for (int posicao : fronteiras) {
				adicionar(saida, posicao, ",", ENUMERACAO, "Vírgula em enumeração", "Separe os itens da lista com vírgula.");
			}
		}
	}

	/** @return true se acrescentou ponto ou interrogação */
	private boolean pontoFinal(String linha, List<Palavra> palavras, int conteudo, boolean temOracaoInicial,
			Map<Integer, Insercao> saida) {
		Palavra ultima = palavras.getLast();
		if (!linha.substring(ultima.fim()).isBlank() || pareceLink(linha) || RISADA.matcher(ultima.minuscula()).matches()) {
			return false;
		}
		if (pergunta(palavras, conteudo, temOracaoInicial)) {
			return adicionar(saida, ultima.fim(), "?", INTERROGACAO, "Ponto de interrogação",
					"Parece uma pergunta: termine com “?”.");
		}
		if (palavras.size() - conteudo >= PALAVRAS_PARA_PONTO_FINAL) {
			return adicionar(saida, ultima.fim(), ".", PONTO_FINAL, "Ponto final", "Termine a frase com ponto final.");
		}
		return false;
	}

	private static boolean pergunta(List<Palavra> palavras, int conteudo, boolean temOracaoInicial) {
		int n = palavras.size();
		String ultimas = n >= 2 ? par(palavras, n - 2) : "";
		// sozinhos ("beleza", "tudo bem") são resposta, não pergunta
		boolean fimDuplo = FIM_DE_PERGUNTA.contains(ultimas) && n > 2;
		boolean fimSimples = FIM_DE_PERGUNTA.contains(palavras.getLast().minuscula()) && n > 1;
		boolean fimAposSaudacao = conteudo > 0 && FIM_DE_PERGUNTA_APOS_SAUDACAO.contains(ultimas) && n == conteudo + 2;
		if (fimDuplo || fimSimples || fimAposSaudacao) {
			return true;
		}
		if (temOracaoInicial || conteudo >= n) {
			return false;
		}
		String primeira = palavras.get(conteudo).minuscula();
		if (INTERROGATIVAS.contains(primeira)) {
			return true;
		}
		boolean porQue = primeira.equals("por") && conteudo + 1 < n && palavras.get(conteudo + 1).minuscula().matches("que|quê");
		boolean oQue = primeira.equals("o") && conteudo + 1 < n && palavras.get(conteudo + 1).minuscula().matches("que|quê");
		boolean quePergunta = primeira.equals("que") && conteudo + 1 < n && QUE_PERGUNTA.contains(palavras.get(conteudo + 1).minuscula());
		return porQue || oQue || quePergunta;
	}

	private boolean adicionar(Map<Integer, Insercao> saida, int posicao, String sinal, String regra, String descricao,
			String mensagem) {
		if (desativadas.contains(regra)) {
			return false;
		}
		saida.putIfAbsent(posicao, new Insercao(posicao, sinal, false, regra, descricao, mensagem));
		return true;
	}

	private static String par(List<Palavra> palavras, int i) {
		return palavras.get(i).minuscula() + " " + palavras.get(i + 1).minuscula();
	}

	/** Entre a palavra i e a seguinte só há espaço (nenhuma vírgula, ponto, emoji…). */
	private static boolean semPontuacaoEntre(String linha, List<Palavra> palavras, int i) {
		return i + 1 < palavras.size() && linha.substring(palavras.get(i).fim(), palavras.get(i + 1).inicio()).isBlank();
	}

	private static boolean ehSubstantivo(Map<Integer, List<String>> etiquetas, Palavra p) {
		List<String> tags = etiquetas.getOrDefault(p.inicio(), List.of());
		// "amarela" também pode ser substantivo, mas como adjetivo não é item de lista
		return tags.stream().anyMatch(t -> t.startsWith("NC")) && tags.stream().noneMatch(t -> t.startsWith("AQ"));
	}

	private static boolean ehArtigo(Map<Integer, List<String>> etiquetas, Palavra p) {
		return etiquetas.getOrDefault(p.inicio(), List.of()).stream().anyMatch(t -> t.startsWith("DA") || t.startsWith("DI"));
	}

	private static boolean ehPreposicaoDe(Palavra p) {
		return p.minuscula().matches("de|do|da|dos|das");
	}

	private static boolean pareceLink(String linha) {
		return linha.contains("://") || linha.contains("www.");
	}

}
