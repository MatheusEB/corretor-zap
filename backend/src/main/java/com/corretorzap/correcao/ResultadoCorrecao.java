package com.corretorzap.correcao;

import java.util.List;

public record ResultadoCorrecao(String original, String corrigido, List<Correcao> correcoes) {

	/** Monta o resultado aplicando as correções com certeza (em ordem, sem sobreposição) ao texto original. */
	public static ResultadoCorrecao aplicando(String original, List<Correcao> correcoes) {
		StringBuilder resultado = new StringBuilder(original);
		// de trás pra frente, para as posições do texto original continuarem válidas
		for (int i = correcoes.size() - 1; i >= 0; i--) {
			Correcao c = correcoes.get(i);
			if (c.certeza()) {
				resultado.replace(c.inicio(), c.fim(), c.sugestao());
			}
		}
		return new ResultadoCorrecao(original, resultado.toString(), correcoes);
	}

	public boolean mudou() {
		return !original.equals(corrigido);
	}

}
