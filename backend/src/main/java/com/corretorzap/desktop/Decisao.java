package com.corretorzap.desktop;

import java.util.List;

import com.corretorzap.correcao.Correcao;

/**
 * O que a pessoa escolheu na prévia.
 *
 * @param texto     o texto a colar
 * @param aceitas   as correções que ficaram (vão para o histórico)
 * @param recusadas as que ela desfez (ensinam o dicionário)
 */
public record Decisao(String texto, List<Correcao> aceitas, List<Correcao> recusadas) {

	public Decisao {
		aceitas = aceitas == null ? List.of() : List.copyOf(aceitas);
		recusadas = recusadas == null ? List.of() : List.copyOf(recusadas);
	}

}
