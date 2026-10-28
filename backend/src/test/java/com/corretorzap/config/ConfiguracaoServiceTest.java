package com.corretorzap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class ConfiguracaoServiceTest {

	@TempDir
	Path pasta;

	/** Reproduz o HTTP 500 visto no teste de estresse: outro processo (antivírus) com o arquivo aberto. */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void salvaMesmoComArquivoTravadoPorAlgunsInstantes() throws Exception {
		ConfiguracaoService configuracao = new ConfiguracaoService(pasta, JsonMapper.builder().build());
		configuracao.adicionarPalavra("primeira");

		// no Windows, um arquivo aberto assim não pode ser substituído até ser fechado
		FileInputStream trava = new FileInputStream(pasta.resolve("config.json").toFile());
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(150);
				trava.close();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		configuracao.adicionarPalavra("segunda");

		ConfiguracaoService recarregada = new ConfiguracaoService(pasta, JsonMapper.builder().build());
		assertThat(recarregada.atual().dicionario()).containsExactly("primeira", "segunda");
	}

	@Test
	void recusaPalavraAlemDoLimite() {
		ConfiguracaoService configuracao = new ConfiguracaoService(pasta, JsonMapper.builder().build());
		TreeSet<String> cheio = new TreeSet<>();
		for (int i = 0; i < Configuracao.MAXIMO_PALAVRAS; i++) {
			cheio.add("p" + i);
		}
		configuracao.salvar(configuracao.atual().comDicionario(cheio));

		assertThatThrownBy(() -> configuracao.adicionarPalavra("demais"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
