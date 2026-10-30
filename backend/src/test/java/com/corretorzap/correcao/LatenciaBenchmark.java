package com.corretorzap.correcao;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.languagetool.JLanguageTool;
import org.languagetool.Languages;

import com.corretorzap.config.ConfiguracaoService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Mede a latência do corretor. Desabilitado no build normal; para rodar, comente o {@code @Disabled} ou use
 * {@code mvnw test -Dtest=LatenciaBenchmark}.
 */
@Disabled("benchmark manual, não faz parte da suíte")
class LatenciaBenchmark {

	private static final List<String> MENSAGENS = List.of(
			"oi tudo bem",
			"voce vem amanha ou nao",
			"bom dia gente vamos comecar a reuniao as 10 hrs se der certo eu te aviso",
			"porisso eu nao fui na festa mas a gente se ve semana que vem quando voce voltar de viagem",
			"oi mae tudo bem\neu queria saber se voce consegue buscar as criancas na escola amanha porque eu vou sair tarde\nse nao der tudo bem eu vejo com o joao\nbeijos");

	@Test
	void medir() throws Exception {
		long t0 = System.nanoTime();
		CorretorService corretor = new CorretorService(
				new ConfiguracaoService(Files.createTempDirectory("cz"), JsonMapper.builder().build()));
		corretor.corrigir("aquecendo");
		System.out.printf("1a correcao (carrega dicionario): %d ms%n", (System.nanoTime() - t0) / 1_000_000);

		JLanguageTool lt = new JLanguageTool(Languages.getLanguageForShortCode("pt-BR"));
		for (int i = 0; i < 30; i++) {
			for (String m : MENSAGENS) {
				corretor.corrigir(m);
				lt.check(m);
				lt.analyzeText(m);
			}
		}
		for (String m : MENSAGENS) {
			System.out.printf("%4d chars | corrigir %7.2f ms | check %7.2f ms | analyzeText %7.2f ms%n", m.length(),
					mediana(() -> corretor.corrigir(m)), mediana(() -> lt.check(m)), mediana(() -> lt.analyzeText(m)));
		}
	}

	private static double mediana(Callable<?> tarefa) throws Exception {
		double[] tempos = new double[25];
		for (int i = 0; i < tempos.length; i++) {
			long inicio = System.nanoTime();
			tarefa.call();
			tempos[i] = (System.nanoTime() - inicio) / 1e6;
		}
		Arrays.sort(tempos);
		return tempos[tempos.length / 2];
	}

}
