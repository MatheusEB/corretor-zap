package com.corretorzap.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.corretorzap.correcao.Correcao;
import com.corretorzap.correcao.ResultadoCorrecao;

class DiferencasTest {

	@Test
	void textoIgualNaoTemCorrecao() {
		assertThat(Diferencas.entre("Tudo certo.", "Tudo certo.")).isEmpty();
	}

	@Test
	void cadaPalavraViraUmaCorrecao() {
		List<Correcao> correcoes = Diferencas.entre("voce nao vem", "você não vem");

		assertThat(correcoes).extracting(Correcao::trecho, Correcao::sugestao)
				.containsExactly(tuple("voce", "você"), tuple("nao", "não"));
		assertThat(correcoes).allMatch(c -> c.regra().equals(Diferencas.REGRA) && c.certeza());
	}

	@Test
	void sinalAcrescentadoVaiJuntoDaPalavraDeAntes() {
		List<Correcao> correcoes = Diferencas.entre("oi tudo bem", "Oi, tudo bem?");

		assertThat(correcoes).extracting(Correcao::trecho, Correcao::sugestao)
				.containsExactly(tuple("oi", "Oi,"), tuple("bem", "bem?"));
	}

	@Test
	void palavraSeparadaEmDuas() {
		assertThat(Diferencas.entre("porisso eu fui", "por isso eu fui")).extracting(Correcao::trecho, Correcao::sugestao)
				.containsExactly(tuple("porisso", "por isso"));
	}

	@Test
	void acrescimoNoComecoVaiJuntoDaPalavraSeguinte() {
		assertThat(Diferencas.entre("vem logo", "¡vem logo")).extracting(Correcao::trecho, Correcao::sugestao)
				.containsExactly(tuple("vem", "¡vem"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"voce nao vai acreditar oque aconteceu hoje to rindo ate agora|Você não vai acreditar no que aconteceu hoje, tô rindo até agora.",
			"fui na festa mas ele nao foi|Fui na festa, mas ele não foi.",
			"se der certo eu te aviso|Se der certo, eu te aviso.",
			"kkkk ta bom|kkkk tá bom",
			"ela chegou|Ela chegou.",
			"a b c|a, b e c" })
	void aplicarTodasDevolveOTextoDaIa(String original, String revisado) {
		ResultadoCorrecao resultado = ResultadoCorrecao.aplicando(original, Diferencas.entre(original, revisado));

		assertThat(resultado.corrigido()).isEqualTo(revisado);
		assertThat(resultado.correcoes()).allMatch(c -> original.substring(c.inicio(), c.fim()).equals(c.trecho()));
	}

	@Test
	void quebrasDeLinhaContinuamNoLugar() {
		String original = "oi\r\ntudo bem";
		String revisado = "Oi\r\ntudo bem?";

		assertThat(ResultadoCorrecao.aplicando(original, Diferencas.entre(original, revisado)).corrigido()).isEqualTo(revisado);
	}

}
