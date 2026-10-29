package com.corretorzap.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.Correcao;
import com.corretorzap.correcao.CorretorService;
import com.corretorzap.correcao.ResultadoCorrecao;
import com.corretorzap.ia.ClienteIa.FalhaIa;

import tools.jackson.databind.json.JsonMapper;

class RevisorIaTest {

	@TempDir
	Path pasta;

	private ConfiguracaoService configuracao;
	private ConfiguracaoIaService configuracaoIa;
	private Function<String, String> ia;
	private RevisorIa revisor;

	@BeforeEach
	void setUp() {
		JsonMapper mapper = JsonMapper.builder().build();
		configuracao = new ConfiguracaoService(pasta, mapper);
		configuracaoIa = new ConfiguracaoIaService(pasta, mapper);
		ClienteIa falso = new ClienteIa(mapper) {
			@Override
			public String revisar(ConfiguracaoIa cfg, String texto, Set<String> protegidas) {
				return ia.apply(texto);
			}
		};
		revisor = new RevisorIa(new CorretorService(configuracao), configuracao, configuracaoIa, falso);
	}

	private void ligarIa() {
		configuracaoIa.salvar(true, "gemini", ConfiguracaoIa.URL_GEMINI, ConfiguracaoIa.MODELO_GEMINI, "chave-de-teste");
	}

	@Test
	void desligadaUsaOCorretorLocal() {
		ia = texto -> {
			throw new AssertionError("não devia chamar a IA");
		};

		ResultadoCorrecao resultado = revisor.corrigir("voce nao vai acreditar");

		assertThat(resultado.corrigido()).isEqualTo("Você não vai acreditar.");
	}

	@Test
	void ligadaUsaARespostaDaIa() {
		ligarIa();
		ia = texto -> "Você não vai acreditar no que aconteceu!";

		ResultadoCorrecao resultado = revisor.corrigir("voce nao vai acreditar oque aconteceu");

		assertThat(resultado.corrigido()).isEqualTo("Você não vai acreditar no que aconteceu!");
		assertThat(resultado.correcoes()).extracting(Correcao::regra).containsOnly(Diferencas.REGRA);
	}

	@Test
	void seAIaFalharOCorretorLocalAssume() {
		ligarIa();
		ia = texto -> {
			throw new FalhaIa("Limite de uso da IA atingido (429)");
		};

		assertThat(revisor.corrigir("voce nao vai acreditar").corrigido()).isEqualTo("Você não vai acreditar.");
	}

	@Test
	void respostaQueReescreveAMensagemEhDescartada() {
		ligarIa();
		ia = texto -> "Claro! Aqui está a mensagem revisada com um tom mais formal para você.";

		assertThat(revisor.corrigir("voce nao vai acreditar oque aconteceu").correcoes())
				.extracting(Correcao::regra).doesNotContain(Diferencas.REGRA);
		assertThatThrownBy(() -> revisor.comIa(configuracaoIa.atual(), "voce nao vai acreditar oque aconteceu"))
				.isInstanceOf(FalhaIa.class);
	}

	@Test
	void naoMexeEmPalavraDoDicionario() {
		ligarIa();
		configuracao.adicionarPalavra("Zezinho");
		ia = texto -> "Você viu o Zézinho?";

		ResultadoCorrecao resultado = revisor.corrigir("voce viu o Zezinho");

		assertThat(resultado.corrigido()).isEqualTo("Você viu o Zezinho");
	}

	@Test
	void limpaOQueAIaPoeEmVolta() {
		assertThat(RevisorIa.limpar("oi tudo bem", "```\nOi, tudo bem?\n```")).isEqualTo("Oi, tudo bem?");
		assertThat(RevisorIa.limpar("oi tudo bem", "\"Oi, tudo bem?\"")).isEqualTo("Oi, tudo bem?");
		assertThat(RevisorIa.limpar("\"oi\" ela disse", "\"Oi\", ela disse.")).isEqualTo("\"Oi\", ela disse.");
		// espaços das pontas e \r\n do WhatsApp no Windows voltam como estavam
		assertThat(RevisorIa.limpar("  oi\r\ntudo bem \n", "Oi!\nTudo bem?")).isEqualTo("  Oi!\r\nTudo bem? \n");
	}

	@Test
	void distingueCorrecaoDeReescrita() {
		assertThat(RevisorIa.reescreveu("oi tudo bem joao", "Oi, tudo bem, João?")).isFalse();
		assertThat(RevisorIa.reescreveu("voce nao vai acreditar oque aconteceu", "Você não vai acreditar no que aconteceu."))
				.isFalse();
		assertThat(RevisorIa.reescreveu("voce nao vai acreditar oque aconteceu", "Prezado, segue em anexo o relatório solicitado."))
				.isTrue();
		// mensagem curta que virou um parágrafo: a IA respondeu em vez de corrigir
		assertThat(RevisorIa.reescreveu("blz", "Beleza! Fico feliz em ajudar, qualquer coisa me chame por aqui.")).isTrue();
	}

}
