package com.corretorzap.correcao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

// as listas são as que o LanguageTool (pt-BR) devolve de verdade para a palavra no meio da frase
class RanqueadorTest {

	private static Ranqueador.Escolha escolher(String digitado, String... sugestoes) {
		return Ranqueador.escolher(digitado, List.of(sugestoes), false);
	}

	@Test
	void acentoEsquecido() {
		assertThat(escolher("voces", "vocês", "bosses", "boces", "bouces", "você", "vozes"))
				.isEqualTo(new Ranqueador.Escolha("vocês", true));
		assertThat(escolher("ve", "vê", "bê", "de", "e", "se", "bem")).isEqualTo(new Ranqueador.Escolha("vê", true));
	}

	@Test
	void acentoEsquecidoGanhaDaPalavraMaisFrequente() {
		// mesma lista com "vocês" lá no meio: o erro de acento ainda é o mais provável
		assertThat(escolher("voces", "bosses", "boces", "vocês", "bouces")).isEqualTo(new Ranqueador.Escolha("vocês", true));
	}

	@Test
	void trocaFonetica() {
		assertThat(escolher("presiso", "preciso", "precisou", "presso", "presido", "presigo"))
				.isEqualTo(new Ranqueador.Escolha("preciso", true));
		assertThat(escolher("exelente", "excelente", "exequente", "expelente", "emelem-te"))
				.isEqualTo(new Ranqueador.Escolha("excelente", true));
	}

	@Test
	void vogalFinalEscritaComoSeFala() {
		// "chat" (sem o "u") seria o primeiro mais barato se o fim da palavra não pesasse
		assertThat(escolher("chatu", "chat", "chato", "chata", "chats", "chau", "Catu"))
				.isEqualTo(new Ranqueador.Escolha("chato", true));
		assertThat(escolher("cansadu", "cansado", "cansada", "cansa du")).isEqualTo(new Ranqueador.Escolha("cansado", true));
		assertThat(escolher("jogu", "jogo", "gol", "joga", "jogou", "Joel")).isEqualTo(new Ranqueador.Escolha("jogo", true));
	}

	@Test
	void letrasTrocadasDeLugar() {
		assertThat(escolher("qeu", "que", "seu", "deu", "céu", "eu", "meu")).isEqualTo(new Ranqueador.Escolha("que", true));
		assertThat(escolher("celualr", "celular")).isEqualTo(new Ranqueador.Escolha("celular", true));
	}

	@Test
	void teclaVizinhaDesempata() {
		// "i" fica do lado do "o"; do "a", não
		assertThat(escolher("obrigadi", "obrigado", "obrigada", "obrigai")).isEqualTo(new Ranqueador.Escolha("obrigado", true));
	}

	@Test
	void naoTrocaSozinhoQuandoNenhumaSugestaoCombina() {
		// "amanhã" nem está na lista; "amana" e "ananás" seriam piores do que deixar como está
		assertThat(escolher("amanah", "ananás", "Anamã", "Ananas", "amanhar", "anana", "ananaí", "amana", "amanha").certeza())
				.isFalse();
	}

	@Test
	void naoTrocaSozinhoQuandoDuasSugestoesEmpatam() {
		// "casa" ou "caso": faltou a última letra e qualquer uma serve
		assertThat(escolher("cas", "casa", "caso").certeza()).isFalse();
	}

	@Test
	void nomeProprioSoTrocaSeForAcento() {
		assertThat(escolher("Joao", "João", "jogo", "jogou")).isEqualTo(new Ranqueador.Escolha("João", true));
		assertThat(escolher("Kaua", "Kauã", "Kana", "Saúa").certeza()).isTrue();
		// maiúscula no meio da frase e longe da sugestão: provavelmente um nome que o dicionário não conhece
		assertThat(escolher("Thayna", "Tainha", "Thayer").certeza()).isFalse();
		// no começo da frase a maiúscula não quer dizer nada
		assertThat(Ranqueador.escolher("Presiso", List.of("Preciso", "Precisou"), true).certeza()).isTrue();
	}

	@Test
	void mantemMaiusculasDoTexto() {
		assertThat(Ranqueador.manterCaixa("Voce", "você")).isEqualTo("Você");
		assertThat(Ranqueador.manterCaixa("NAO", "não")).isEqualTo("NÃO");
		assertThat(Ranqueador.manterCaixa("joao", "João")).isEqualTo("João");
	}

	@Test
	void custosRefletemOTipoDeErro() {
		assertThat(Ranqueador.custo("nao", "não")).isLessThan(Ranqueador.custo("nao", "nada"));
		assertThat(Ranqueador.custo("porisso", "por isso")).isLessThan(1);
		assertThat(Ranqueador.custo("vamo", "vamos")).isLessThan(Ranqueador.custo("vamo", "ramo"));
		assertThat(Ranqueador.custo("oje", "hoje")).isLessThan(Ranqueador.custo("oje", "ode"));
		// "s" e "d" são vizinhas no teclado; "s" e "p" não
		assertThat(Ranqueador.custo("dsdo", "dado")).isLessThan(Ranqueador.custo("dpdo", "dado"));
	}

}
