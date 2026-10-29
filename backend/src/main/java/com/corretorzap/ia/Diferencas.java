package com.corretorzap.ia;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.corretorzap.correcao.Correcao;

/**
 * Transforma "texto original" + "texto revisado pela IA" em correções separadas, cada uma com a posição no
 * original, para a prévia marcar e deixar desfazer uma a uma, igual às do LanguageTool.
 * <p>
 * Compara palavra por palavra (maior subsequência comum). Um sinal acrescentado vai junto da palavra de antes,
 * como faz a pontuação local: "festa mas" → "festa, mas" vira a troca "festa" → "festa,".
 */
final class Diferencas {

	static final String REGRA = "CZ_IA";
	private static final String DESCRICAO = "Revisão da IA";
	// palavra (com hífen ou apóstrofo no meio), espaço em sequência, ou um sinal qualquer
	private static final Pattern PECA = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+(?:[-'][\\p{L}\\p{M}\\p{N}]+)*|\\s+|.",
			Pattern.DOTALL);

	private Diferencas() {
	}

	private record Peca(int inicio, int fim, String texto) {
		boolean espaco() {
			return texto.isBlank();
		}
	}

	static List<Correcao> entre(String original, String revisado) {
		List<Peca> a = pecas(original);
		List<Peca> b = pecas(revisado);

		// o começo e o fim iguais ficam de fora da tabela (quase sempre é quase tudo)
		int comeco = 0;
		while (comeco < a.size() && comeco < b.size() && a.get(comeco).texto().equals(b.get(comeco).texto())) {
			comeco++;
		}
		int fimA = a.size();
		int fimB = b.size();
		while (fimA > comeco && fimB > comeco && a.get(fimA - 1).texto().equals(b.get(fimB - 1).texto())) {
			fimA--;
			fimB--;
		}

		int n = fimA - comeco;
		int m = fimB - comeco;
		int[][] lcs = new int[n + 1][m + 1];
		for (int i = n - 1; i >= 0; i--) {
			for (int j = m - 1; j >= 0; j--) {
				lcs[i][j] = a.get(comeco + i).texto().equals(b.get(comeco + j).texto())
						? lcs[i + 1][j + 1] + 1
						: Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}

		List<Correcao> correcoes = new ArrayList<>();
		int i = 0;
		int j = 0;
		int fimDaAnterior = 0;
		while (i < n || j < m) {
			if (i < n && j < m && a.get(comeco + i).texto().equals(b.get(comeco + j).texto())) {
				i++;
				j++;
				continue;
			}
			// um trecho diferente: anda até as duas sequências voltarem a coincidir
			int i0 = i;
			int j0 = j;
			while (i < n || j < m) {
				if (i < n && j < m && a.get(comeco + i).texto().equals(b.get(comeco + j).texto())) {
					break;
				}
				if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
					j++;
				} else {
					i++;
				}
			}
			correcoes.add(troca(original, revisado, a, b, comeco + i0, comeco + i, comeco + j0, comeco + j, fimDaAnterior));
			fimDaAnterior = comeco + i;
		}
		return correcoes;
	}

	/**
	 * Peças a[deA, ateA) do original viram b[deB, ateB) da revisão.
	 *
	 * @param limite primeira peça do original que não pertence à troca anterior
	 */
	private static Correcao troca(String original, String revisado, List<Peca> a, List<Peca> b, int deA, int ateA,
			int deB, int ateB, int limite) {
		int inicio = deA < a.size() ? a.get(deA).inicio() : original.length();
		int fim = ateA > deA ? a.get(ateA - 1).fim() : inicio;
		int inicioNovo = deB < b.size() ? b.get(deB).inicio() : revisado.length();
		int fimNovo = ateB > deB ? b.get(ateB - 1).fim() : inicioNovo;

		// só acrescentou (vírgula, ponto, acento solto): a troca leva junto a palavra de antes, ou a de depois no começo
		if (inicio == fim) {
			int antes = deA - 1;
			while (antes >= limite && a.get(antes).espaco()) {
				antes--;
			}
			if (antes >= limite) {
				inicio = a.get(antes).inicio();
				inicioNovo -= fim - inicio;
			} else if (deA < a.size()) {
				fim = a.get(deA).fim();
				fimNovo += fim - inicio;
			}
		}
		String trecho = original.substring(inicio, fim);
		String sugestao = revisado.substring(inicioNovo, fimNovo);
		return new Correcao(inicio, fim, trecho, sugestao, "Sugestão da IA.", REGRA, DESCRICAO);
	}

	private static List<Peca> pecas(String texto) {
		List<Peca> pecas = new ArrayList<>();
		Matcher m = PECA.matcher(texto);
		while (m.find()) {
			pecas.add(new Peca(m.start(), m.end(), m.group()));
		}
		return pecas;
	}

}
