package com.corretorzap.correcao;

import java.util.List;

public record ResultadoCorrecao(String original, String corrigido, List<Correcao> correcoes) {

	public boolean mudou() {
		return !original.equals(corrigido);
	}

}
