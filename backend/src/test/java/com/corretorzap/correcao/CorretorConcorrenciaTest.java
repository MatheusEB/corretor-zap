package com.corretorzap.correcao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corretorzap.config.ConfiguracaoService;

import tools.jackson.databind.json.JsonMapper;

/** Várias correções ao mesmo tempo (painel + atalho) não podem misturar resultados. */
class CorretorConcorrenciaTest {

	@TempDir
	Path pasta;

	@Test
	void correcoesParalelasDaoSempreOMesmoResultado() throws Exception {
		CorretorService corretor = new CorretorService(new ConfiguracaoService(pasta, JsonMapper.builder().build()));
		List<String[]> casos = List.of(
				new String[] { "voce nao vai", corretor.corrigir("voce nao vai").corrigido() },
				new String[] { "Voce sabe oque e isso", corretor.corrigir("Voce sabe oque e isso").corrigido() },
				new String[] { "Tudo certo por aqui.", "Tudo certo por aqui." });

		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			List<Future<Boolean>> resultados = new ArrayList<>();
			for (int i = 0; i < 200; i++) {
				String[] caso = casos.get(i % casos.size());
				resultados.add(executor.submit(() -> corretor.corrigir(caso[0]).corrigido().equals(caso[1])));
			}
			for (Future<Boolean> resultado : resultados) {
				assertThat(resultado.get()).isTrue();
			}
		}
	}

}
