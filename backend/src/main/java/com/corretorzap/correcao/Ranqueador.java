package com.corretorzap.correcao;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Escolhe, entre as sugestões do LanguageTool para uma palavra com erro de ortografia, a que a pessoa mais
 * provavelmente quis digitar, e diz se dá para trocar sem perguntar.
 * <p>
 * O LanguageTool ordena as sugestões pela frequência da palavra, sem olhar para o erro em si. Aqui cada sugestão
 * ganha um custo de "quanto esse erro de digitação é plausível" (modelo de erro do canal ruidoso): esquecer um
 * acento quase não custa, uma tecla vizinha no teclado ABNT2 ou uma troca fonética ("s" por "ç") custa pouco,
 * uma letra qualquer custa caro. A posição original na lista entra como desempate.
 * <p>
 * A regra é a mesma da pontuação: na dúvida, não troca. Uma correção errada aplicada sozinha incomoda mais
 * do que uma que ficou só como sugestão.
 */
final class Ranqueador {

	/** A sugestão escolhida e se ela é segura o bastante para ser aplicada sem a pessoa confirmar. */
	record Escolha(String sugestao, boolean certeza) {
	}

	private static final double ACENTO = 0.1;
	private static final double VOGAL_FINAL = 0.3;
	private static final double LETRA_DOBRADA = 0.4;
	private static final double ESPACO = 0.4;
	private static final double FONETICA = 0.5;
	private static final double LETRA_MUDA = 0.5;
	private static final double FALTA_NO_FIM = 0.6;
	private static final double SOBRA_NO_FIM = 0.8;
	private static final double VIZINHA = 0.6;
	private static final double TROCADAS = 0.6;
	private static final double QUALQUER = 1.0;
	private static final double PESO_DA_POSICAO = 0.15;

	/** Até aqui é esquecer acento, dobrar letra ou escrever como se fala: troca mesmo em nome próprio. */
	private static final double SEMPRE_CERTO = 0.45;
	/** Acima disso a palavra digitada está longe demais de qualquer sugestão. */
	private static final double LONGE_DEMAIS = 1.6;
	/** Duas sugestões tão perto uma da outra que escolher entre elas é chute. */
	private static final double EMPATE = 0.25;
	/** O LanguageTool põe as palavras raras no fim da lista; escolher uma delas já é arriscado. */
	private static final int FUNDO_DA_LISTA = 4;

	private static final String[] TECLADO = { "qwertyuiop", "asdfghjklç", "zxcvbnm" };
	private static final double[] RECUO_DA_FILEIRA = { 0, 0.25, 0.75 };
	// pares que soam igual (ou quase) em português: "presiso", "exelente", "jente", "xingar"
	private static final List<String> FONETICOS = List.of("sç", "sc", "sz", "çc", "zx", "sx", "gj");
	// no fim da palavra se escreve como se fala: "chatu", "cansadu", "nomi", "mau" por "mal"
	private static final List<String> VOGAIS_FINAIS = List.of("ou", "ie");

	private Ranqueador() {
	}

	/**
	 * @param digitado     a palavra como está no texto
	 * @param sugestoes    sugestões do LanguageTool, na ordem dele (não vazia)
	 * @param inicioDeFrase true se a palavra abre uma frase (aí maiúscula não indica nome próprio)
	 */
	static Escolha escolher(String digitado, List<String> sugestoes, boolean inicioDeFrase) {
		String alvo = digitado.toLowerCase(Locale.ROOT);
		int melhor = -1;
		double melhorPontos = Double.MAX_VALUE;
		double melhorCusto = Double.MAX_VALUE;
		double segundoPontos = Double.MAX_VALUE;
		for (int i = 0; i < sugestoes.size(); i++) {
			String sugestao = sugestoes.get(i);
			double custo = custo(alvo, sugestao.toLowerCase(Locale.ROOT));
			double pontos = custo + PESO_DA_POSICAO * i;
			if (pontos < melhorPontos) {
				if (melhor >= 0 && !mesmaPalavra(sugestoes.get(melhor), sugestao)) {
					segundoPontos = melhorPontos;
				}
				melhor = i;
				melhorPontos = pontos;
				melhorCusto = custo;
			} else if (pontos < segundoPontos && !mesmaPalavra(sugestoes.get(melhor), sugestao)) {
				segundoPontos = pontos;
			}
		}

		String escolhida = manterCaixa(digitado, sugestoes.get(melhor));
		boolean pareceNome = !inicioDeFrase && digitado.length() > 1 && Character.isUpperCase(digitado.charAt(0))
				&& !digitado.equals(digitado.toUpperCase(Locale.ROOT));
		boolean certeza = melhorCusto <= SEMPRE_CERTO || (!pareceNome && melhorCusto <= LONGE_DEMAIS
				&& melhor < FUNDO_DA_LISTA && segundoPontos - melhorPontos >= EMPATE);
		return new Escolha(escolhida, certeza);
	}

	/** "Voce" → "Você", "NAO" → "NÃO"; nos outros casos fica como o LanguageTool sugeriu ("joao" → "João"). */
	static String manterCaixa(String digitado, String sugestao) {
		if (sugestao.isEmpty()) {
			return sugestao;
		}
		if (digitado.length() > 1 && digitado.equals(digitado.toUpperCase(Locale.ROOT))
				&& !digitado.equals(digitado.toLowerCase(Locale.ROOT))) {
			return sugestao.toUpperCase(Locale.ROOT);
		}
		if (Character.isUpperCase(digitado.charAt(0)) && Character.isLowerCase(sugestao.charAt(0))) {
			return Character.toUpperCase(sugestao.charAt(0)) + sugestao.substring(1);
		}
		return sugestao;
	}

	/**
	 * Distância de edição ponderada (Damerau-Levenshtein com trocas adjacentes) entre o que foi digitado e a
	 * sugestão, com custos menores para os erros que mais acontecem ao digitar no celular e no teclado.
	 */
	static double custo(String digitado, String sugestao) {
		int n = digitado.length();
		int m = sugestao.length();
		double[][] d = new double[n + 1][m + 1];
		for (int i = 1; i <= n; i++) {
			d[i][0] = d[i - 1][0] + sobra(digitado, i - 1);
		}
		for (int j = 1; j <= m; j++) {
			d[0][j] = d[0][j - 1] + falta(sugestao, j - 1);
		}
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= m; j++) {
				double valor = Math.min(
						d[i - 1][j] + sobra(digitado, i - 1),
						Math.min(d[i][j - 1] + falta(sugestao, j - 1),
								d[i - 1][j - 1] + troca(digitado.charAt(i - 1), sugestao.charAt(j - 1), i == n && j == m)));
				if (i > 1 && j > 1 && digitado.charAt(i - 1) == sugestao.charAt(j - 2)
						&& digitado.charAt(i - 2) == sugestao.charAt(j - 1)) {
					valor = Math.min(valor, d[i - 2][j - 2] + TROCADAS);
				}
				d[i][j] = valor;
			}
		}
		return d[n][m];
	}

	/** Letra que está no texto mas não na sugestão ("aqquii", "hontem"). */
	private static double sobra(String palavra, int i) {
		if (repetida(palavra, i)) {
			return LETRA_DOBRADA;
		}
		if (muda(palavra, i)) {
			return LETRA_MUDA;
		}
		return i == palavra.length() - 1 ? SOBRA_NO_FIM : QUALQUER;
	}

	/** Letra que a sugestão tem e o texto não ("porisso" → "por isso", "vamo" → "vamos", "exelente", "oje"). */
	private static double falta(String palavra, int j) {
		char c = palavra.charAt(j);
		if (c == ' ' || c == '-') {
			return ESPACO;
		}
		if (repetida(palavra, j)) {
			return LETRA_DOBRADA;
		}
		if (muda(palavra, j)) {
			return LETRA_MUDA;
		}
		boolean depoisDeSibilante = c == 'c' && j > 0 && "xs".indexOf(palavra.charAt(j - 1)) >= 0;
		if (depoisDeSibilante) {
			return FONETICA;
		}
		return j == palavra.length() - 1 ? FALTA_NO_FIM : QUALQUER;
	}

	/** Só o "h" do começo não tem som ("hoje"); o de "nh", "lh", "ch" faz parte do som. */
	private static boolean muda(String palavra, int i) {
		return i == 0 && palavra.charAt(i) == 'h';
	}

	private static double troca(char digitada, char certa, boolean ultima) {
		if (digitada == certa) {
			return 0;
		}
		char a = semAcento(digitada);
		char b = semAcento(certa);
		if (a == b) {
			return ACENTO;
		}
		if (ultima && par(VOGAIS_FINAIS, a, b)) {
			return VOGAL_FINAL;
		}
		if (par(FONETICOS, a, b)) {
			return FONETICA;
		}
		return vizinhas(digitada, certa) ? VIZINHA : QUALQUER;
	}

	private static boolean repetida(String palavra, int i) {
		char c = palavra.charAt(i);
		return (i > 0 && palavra.charAt(i - 1) == c) || (i + 1 < palavra.length() && palavra.charAt(i + 1) == c);
	}

	private static boolean par(List<String> pares, char a, char b) {
		for (String par : pares) {
			char x = par.charAt(0);
			char y = par.charAt(1);
			if ((a == x && b == y) || (a == y && b == x)) {
				return true;
			}
		}
		return false;
	}

	private static boolean vizinhas(char a, char b) {
		double[] pa = posicao(a);
		double[] pb = posicao(b);
		return pa != null && pb != null && Math.abs(pa[0] - pb[0]) <= 1 && Math.abs(pa[1] - pb[1]) <= 1;
	}

	/** Fileira e coluna (com o recuo de cada fileira) da tecla no teclado ABNT2. */
	private static double[] posicao(char c) {
		for (int fileira = 0; fileira < TECLADO.length; fileira++) {
			int coluna = TECLADO[fileira].indexOf(c);
			if (coluna >= 0) {
				return new double[] { fileira, coluna + RECUO_DA_FILEIRA[fileira] };
			}
		}
		return null;
	}

	static char semAcento(char c) {
		if (c == 'ç') {
			return 'c';
		}
		String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return base.isEmpty() ? c : base.charAt(0);
	}

	private static boolean mesmaPalavra(String a, String b) {
		return a.equalsIgnoreCase(b);
	}

}
