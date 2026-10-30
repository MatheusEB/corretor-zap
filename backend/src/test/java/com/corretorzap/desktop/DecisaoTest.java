package com.corretorzap.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.corretorzap.correcao.Correcao;

import tools.jackson.databind.json.JsonMapper;

/** A prévia (React) manda a decisão como JSON pela ponte; o Java precisa ler exatamente o que ela manda. */
class DecisaoTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void leODecisaoQueAPreviaManda() {
		// JSON.stringify do decisaoRef.current, com as correções como chegam do servidor
		String json = """
				{"texto":"Você vem amanhã?","aceitas":[{"inicio":0,"fim":4,"trecho":"voce","sugestao":"Você",
				"mensagem":"Erro de ortografia","regra":"MORFOLOGIK_RULE_PT_BR","descricaoRegra":"Ortografia","certeza":true}],
				"recusadas":[{"inicio":9,"fim":15,"trecho":"amanha","sugestao":"amanhã","mensagem":"",
				"regra":"CZ_IA","descricaoRegra":"Revisão da IA","certeza":false}]}""";

		Decisao decisao = mapper.readValue(json, Decisao.class);

		assertThat(decisao.texto()).isEqualTo("Você vem amanhã?");
		assertThat(decisao.aceitas()).extracting(Correcao::trecho).containsExactly("voce");
		assertThat(decisao.recusadas()).singleElement().satisfies(c -> {
			assertThat(c.certeza()).isFalse();
			assertThat(c.inicio()).isEqualTo(9);
		});
	}

	@Test
	void listasAusentesViramVazias() {
		Decisao decisao = mapper.readValue("{\"texto\":\"oi\"}", Decisao.class);

		assertThat(decisao.aceitas()).isEmpty();
		assertThat(decisao.recusadas()).isEmpty();
	}

	@Test
	void idaEVoltaPeloJsonNaoPerdeNada() {
		Correcao c = new Correcao(0, 2, "vc", "você", "", "CZ_IA", "Revisão da IA", true);
		Decisao original = new Decisao("você", java.util.List.of(c), null);

		assertThat(mapper.readValue(mapper.writeValueAsString(original), Decisao.class)).isEqualTo(original);
	}

}
