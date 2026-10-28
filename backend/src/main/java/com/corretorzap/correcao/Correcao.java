package com.corretorzap.correcao;

/**
 * Uma troca aplicada no texto. {@code inicio}/{@code fim} são posições no texto original.
 */
public record Correcao(
		int inicio,
		int fim,
		String trecho,
		String sugestao,
		String mensagem,
		String regra,
		String descricaoRegra) {
}
