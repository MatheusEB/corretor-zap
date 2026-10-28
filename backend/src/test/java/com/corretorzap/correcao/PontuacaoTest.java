package com.corretorzap.correcao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;

import tools.jackson.databind.json.JsonMapper;

class PontuacaoTest {

	@TempDir
	static Path pasta;

	static ConfiguracaoService configuracao;
	static CorretorService corretor;

	@BeforeAll
	static void setUp() {
		configuracao = new ConfiguracaoService(pasta, JsonMapper.builder().build());
		corretor = new CorretorService(configuracao);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			oi joao tudo bem                   | Oi, João, tudo bem?
			obrigado pela ajuda                | Obrigado pela ajuda.
			quando eu chegar em casa te ligo   | Quando eu chegar em casa, te ligo.
			se der certo eu te aviso           | Se der certo, eu te aviso.
			comprei pao leite e ovos           | Comprei pão, leite e ovos.
			quero pão de queijo café e suco    | Quero pão de queijo, café e suco.
			onde voce está                     | Onde você está?
			bom dia pessoal                    | Bom dia, pessoal
			como foi a prova                   | Como foi a prova?
			assim que terminar eu te mando     | Assim que terminar, eu te mando.
			se eu te ligar você atende         | Se eu te ligar, você atende.
			bom dia gente vamos começar        | Bom dia, gente, vamos começar.
			vou dormir boa noite               | Vou dormir, boa noite.
			vamos almoçar juntos né            | Vamos almoçar juntos, né?
			opa tudo certo                     | Opa, tudo certo?
			o que voce acha                    | O que você acha?
			voce viu oque aconteceu            | Você viu o que aconteceu.
			porisso eu nao fui                 | Por isso eu não fui.
			""")
	void pontuaMensagensDeChat(String texto, String esperado) {
		assertThat(corretor.corrigir(texto).corrigido()).isEqualTo(esperado);
	}

	@Test
	void virgulaAntesDeMas() {
		assertThat(corretor.corrigir("eu queria ir mas nao deu").corrigido()).isEqualTo("Eu queria ir, mas não deu.");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			ok
			né
			kkkkkkk
			beleza
			tudo bem
			Pode ser
			olha esse link https://exemplo.com.br
			""")
	void naoPontuaRespostasCurtasRisadasELinks(String texto) {
		assertThat(corretor.corrigir(texto).corrigido()).doesNotEndWith(".").doesNotEndWith("?").isEqualToIgnoringCase(texto);
	}

	@Test
	void linhaSoComSaudacaoNaoGanhaVirgulaNoFim() {
		assertThat(corretor.corrigir("bom dia\nreunião às 10h\nnão esqueçam o relatório").corrigido())
				.isEqualTo("Bom dia\nReunião às 10h.\nNão esqueçam o relatório.");
	}

	@Test
	void textoJaPontuadoNaoMuda() {
		assertThat(corretor.corrigir("Tudo certo por aqui, e com você?").correcoes()).isEmpty();
	}

	@Test
	void adjetivoNaoViraItemDeLista() {
		assertThat(corretor.corrigir("Gostei da casa amarela e azul").corrigido()).isEqualTo("Gostei da casa amarela e azul.");
	}

	@Test
	void verboAuxiliarNaoSeparaAOracao() {
		assertThat(corretor.corrigir("Se eu tivesse te visto eu teria falado").corrigido())
				.isEqualTo("Se eu tivesse te visto, eu teria falado.");
	}

	@Test
	void cadaLinhaRecebeSuaPontuacao() {
		assertThat(corretor.corrigir("oi tudo bem\nme liga quando puder").corrigido())
				.isEqualTo("Oi, tudo bem?\nMe liga quando puder.");
	}

	@Test
	void pontuacaoApareceComoCorrecaoComRegraPropria() {
		ResultadoCorrecao resultado = corretor.corrigir("Obrigado pela ajuda");

		assertThat(resultado.correcoes()).singleElement().satisfies(c -> {
			assertThat(c.trecho()).isEqualTo("ajuda");
			assertThat(c.sugestao()).isEqualTo("ajuda.");
			assertThat(c.regra()).isEqualTo(Pontuador.PONTO_FINAL);
		});
	}

	@Test
	void regrasDePontuacaoPodemSerDesativadas() {
		Configuracao cfg = configuracao.atual();
		configuracao.salvar(cfg.comRegrasDesativadas(new TreeSet<>(Set.of(Pontuador.PONTO_FINAL, Pontuador.ENUMERACAO))));
		try {
			assertThat(corretor.corrigir("Comprei pão leite e ovos").corrigido()).isEqualTo("Comprei pão leite e ovos");
		} finally {
			configuracao.salvar(cfg);
		}
	}

}
