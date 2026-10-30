package com.corretorzap.correcao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corretorzap.config.ConfiguracaoService;

import tools.jackson.databind.json.JsonMapper;

class AprendizadoServiceTest {

	@TempDir
	Path pasta;

	private final JsonMapper mapper = JsonMapper.builder().build();
	private ConfiguracaoService configuracao;
	private AprendizadoService aprendizado;

	@BeforeEach
	void setUp() {
		configuracao = new ConfiguracaoService(pasta, mapper);
		aprendizado = new AprendizadoService(pasta, mapper, configuracao);
	}

	private static Correcao correcao(String trecho, boolean certeza) {
		return new Correcao(0, trecho.length(), trecho, "x", "", "MORFOLOGIK_RULE_PT_BR", "Ortografia", certeza);
	}

	@Test
	void desfazerTresVezesEnsinaAPalavra() {
		assertThat(aprendizado.registrarRecusas(List.of(correcao("Zezinho", true)))).isEmpty();
		assertThat(aprendizado.registrarRecusas(List.of(correcao("zezinho", true)))).isEmpty();
		assertThat(configuracao.atual().estaNoDicionario("zezinho")).isFalse();

		assertThat(aprendizado.registrarRecusas(List.of(correcao("ZEZINHO", true)))).containsExactly("zezinho");

		assertThat(configuracao.atual().estaNoDicionario("zezinho")).isTrue();
		assertThat(aprendizado.recusasDe("zezinho")).isZero();
	}

	@Test
	void duvidosasDeixadasDesmarcadasNaoContam() {
		for (int i = 0; i < 5; i++) {
			aprendizado.registrarRecusas(List.of(correcao("Zezinho", false)));
		}

		assertThat(configuracao.atual().estaNoDicionario("zezinho")).isFalse();
	}

	@Test
	void soAprendePalavraSoltaNaoPontuacaoNemFrase() {
		for (int i = 0; i < 5; i++) {
			aprendizado.registrarRecusas(List.of(correcao(",", true), correcao("de repente", true), correcao("a", true),
					correcao("123", true)));
		}

		assertThat(configuracao.atual().dicionario()).isEmpty();
	}

	@Test
	void mesmaPalavraDuasVezesNaMesmaMensagemContaUma() {
		aprendizado.registrarRecusas(List.of(correcao("blz", true), correcao("blz", true)));

		assertThat(aprendizado.recusasDe("blz")).isEqualTo(1);
	}

	@Test
	void lembraAsContagensDepoisDeReabrirOApp() {
		aprendizado.registrarRecusas(List.of(correcao("Zezinho", true)));
		aprendizado.registrarRecusas(List.of(correcao("Zezinho", true)));

		AprendizadoService reaberto = new AprendizadoService(pasta, mapper, configuracao);

		assertThat(reaberto.recusasDe("zezinho")).isEqualTo(2);
		assertThat(reaberto.registrarRecusas(List.of(correcao("Zezinho", true)))).containsExactly("zezinho");
	}

	@Test
	void arquivoEstragadoComecaDoZero() throws Exception {
		Files.writeString(pasta.resolve("aprendizado.json"), "{isso não é json");

		AprendizadoService comArquivoRuim = new AprendizadoService(pasta, mapper, configuracao);

		assertThat(comArquivoRuim.recusasDe("qualquer")).isZero();
		assertThat(comArquivoRuim.registrarRecusas(List.of(correcao("Zezinho", true)))).isEmpty();
	}

	@Test
	void muitasPalavrasDiferentesNaoFazemOArquivoCrescerSemFim() {
		IntStream.range(0, 2100).forEach(i -> aprendizado.registrarRecusas(List.of(correcao("palavra" + letras(i), true))));

		assertThat(pasta.resolve("aprendizado.json")).exists();
		assertThat(aprendizado.recusasDe("palavra" + letras(2099))).isEqualTo(1);
		assertThat(aprendizado.recusasDe("palavra" + letras(0))).as("as de uma vez só são descartadas").isZero();
	}

	@Test
	void recusasEmParaleloNaoPerdemContagem() {
		// 30 prévias aplicadas ao mesmo tempo, cada uma desfazendo a mesma palavra
		IntStream.range(0, 30).parallel()
				.forEach(i -> aprendizado.registrarRecusas(List.of(correcao("Zezinho", true), correcao("outra" + letras(i), true))));

		assertThat(configuracao.atual().estaNoDicionario("zezinho")).isTrue();
		// aprendeu na 3ª; dali em diante ela está no dicionário e não conta mais
		assertThat(aprendizado.recusasDe("zezinho")).isZero();
		assertThat(configuracao.atual().dicionario()).containsOnlyOnce("zezinho");
	}

	/** Palavras só de letras (a regra ignora dígitos), diferentes para cada número. */
	private static String letras(int n) {
		StringBuilder s = new StringBuilder();
		do {
			s.append((char) ('a' + n % 26));
			n /= 26;
		} while (n > 0);
		return s.toString();
	}

}
