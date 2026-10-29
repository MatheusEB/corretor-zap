package com.corretorzap.correcao;

/**
 * Uma troca no texto. {@code inicio}/{@code fim} são posições no texto original.
 *
 * @param certeza false = sugestão duvidosa (nenhuma opção do corretor combina bem com o que foi digitado, ou
 *                parece um nome próprio): fica de fora do texto corrigido e a prévia mostra desmarcada
 */
public record Correcao(
		int inicio,
		int fim,
		String trecho,
		String sugestao,
		String mensagem,
		String regra,
		String descricaoRegra,
		boolean certeza) {

	public Correcao(int inicio, int fim, String trecho, String sugestao, String mensagem, String regra,
			String descricaoRegra) {
		this(inicio, fim, trecho, sugestao, mensagem, regra, descricaoRegra, true);
	}

}
