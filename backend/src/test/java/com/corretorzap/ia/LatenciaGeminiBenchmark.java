package com.corretorzap.ia;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Mede a latência real do Gemini com a chave salva em ~/.corretor-zap/ia.json. Gasta cota da API, por isso fica
 * fora da suíte; para rodar: {@code mvnw test -Dtest=LatenciaGeminiBenchmark -Dsurefire.failIfNoSpecifiedTests=false
 * -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition}.
 */
@Disabled("benchmark manual, usa a API de verdade")
class LatenciaGeminiBenchmark {

	private static final List<String> MENSAGENS = List.of(
			"voce vem amanha ou nao",
			"bom dia gente vamos comecar a reuniao as 10 hrs se der certo eu te aviso",
			"porisso eu nao fui na festa mas a gente se ve semana que vem quando voce voltar de viagem");

	@Test
	void medir() throws Exception {
		JsonMapper mapper = JsonMapper.builder().build();
		ConfiguracaoIa cfg = mapper.readValue(
				Files.readString(Path.of(System.getProperty("user.home"), ".corretor-zap", "ia.json")), ConfiguracaoIa.class);
		System.setProperty("jdk.httpclient.keepalive.timeout", "600");
		ClienteIa cliente = new ClienteIa(mapper);

		// 1º pedido com a conexão fria: TLS + DNS + modelo
		medirUma("frio (sem pré-aquecer)", () -> cliente.revisar(cfg, MENSAGENS.get(0), Set.of()));

		// o que o atalho faz: pré-aquece e o pedido sai ~150 ms depois (tempo de copiar o texto)
		ClienteIa outro = new ClienteIa(mapper);
		outro.preaquecer(cfg);
		Thread.sleep(150);
		medirUma("pré-aquecido 150 ms antes", () -> outro.revisar(cfg, MENSAGENS.get(0), Set.of()));

		for (String m : MENSAGENS) {
			double[] t = new double[5];
			for (int i = 0; i < t.length; i++) {
				long inicio = System.nanoTime();
				String r = cliente.revisar(cfg, m, Set.of());
				t[i] = (System.nanoTime() - inicio) / 1e6;
				if (i == 0) {
					System.out.printf("   -> %s%n", r.replace('\n', ' '));
				}
			}
			Arrays.sort(t);
			System.out.printf("revisar %3d chars | mediana %6.0f ms | min %6.0f | max %6.0f%n", m.length(), t[2], t[0], t[4]);
		}
		for (ClienteIa.Tom tom : ClienteIa.Tom.values()) {
			medirUma("tom " + tom, () -> cliente.reescrever(cfg, MENSAGENS.get(1), tom, Set.of()));
		}
	}

	private static void medirUma(String nome, Supplier<String> pedido) {
		long inicio = System.nanoTime();
		String r;
		try {
			r = pedido.get().replace('\n', ' ');
		} catch (ClienteIa.FalhaIa e) {
			r = "FALHOU: " + e.getMessage();
		}
		System.out.printf("%-28s %6d ms -> %s%n", nome, (System.nanoTime() - inicio) / 1_000_000, r);
	}

}
