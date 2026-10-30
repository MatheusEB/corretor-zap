package com.corretorzap.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.Correcao;
import com.corretorzap.correcao.CorretorService;
import com.corretorzap.correcao.ResultadoCorrecao;
import com.corretorzap.ia.ClienteIa.FalhaIa;
import com.corretorzap.ia.ClienteIa.Tom;
import com.corretorzap.ia.RevisorIa.Etapas;

import tools.jackson.databind.json.JsonMapper;

class RevisorIaTest {

	@TempDir
	Path pasta;

	private ConfiguracaoService configuracao;
	private ConfiguracaoIaService configuracaoIa;
	private Function<String, String> ia;
	private BiFunction<String, Tom, String> tomIa;
	private final AtomicInteger chamadasIa = new AtomicInteger();
	private RevisorIa revisor;

	@BeforeEach
	void setUp() {
		JsonMapper mapper = JsonMapper.builder().build();
		configuracao = new ConfiguracaoService(pasta, mapper);
		configuracaoIa = new ConfiguracaoIaService(pasta, mapper);
		ClienteIa falso = new ClienteIa(mapper) {
			@Override
			public String revisar(ConfiguracaoIa cfg, String texto, Set<String> protegidas) {
				chamadasIa.incrementAndGet();
				return ia.apply(texto);
			}

			@Override
			public String reescrever(ConfiguracaoIa cfg, String texto, Tom tom, Set<String> protegidas) {
				return tomIa.apply(texto, tom);
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

	// ---- prévia em duas etapas ----

	@Test
	void semIaAPreviaSoTemAEtapaLocal() {
		Etapas etapas = revisor.corrigirEmEtapas("voce nao vai acreditar");

		assertThat(etapas.local().corrigido()).isEqualTo("Você não vai acreditar.");
		assertThat(etapas.ia()).isNull();
		assertThat(chamadasIa).hasValue(0);
	}

	@Test
	void aEtapaLocalNaoEsperaAIaLenta() throws Exception {
		revisor.corrigir("aquecendo o corretor"); // a primeira correção carrega o dicionário
		ligarIa();
		ia = texto -> {
			dormir(Duration.ofSeconds(2));
			return "Você não vai acreditar no que aconteceu!";
		};

		long inicio = System.nanoTime();
		Etapas etapas = revisor.corrigirEmEtapas("voce nao vai acreditar oque aconteceu");
		long local = (System.nanoTime() - inicio) / 1_000_000;

		assertThat(local).as("a prévia não pode esperar a IA").isLessThan(1000);
		assertThat(etapas.local().mudou()).isTrue();
		assertThat(etapas.ia()).isNotDone();
		assertThat(etapas.ia().get(5, TimeUnit.SECONDS).corrigido()).isEqualTo("Você não vai acreditar no que aconteceu!");
	}

	@Test
	void falhaDaIaChegaPeloFuturoEALocalContinuaValendo() {
		ligarIa();
		ia = texto -> {
			throw new FalhaIa("A IA está sobrecarregada agora (503)");
		};

		Etapas etapas = revisor.corrigirEmEtapas("voce nao vai acreditar");

		assertThat(etapas.local().corrigido()).isEqualTo("Você não vai acreditar.");
		assertThatThrownBy(() -> etapas.ia().join()).isInstanceOf(CompletionException.class)
				.hasCauseInstanceOf(FalhaIa.class);
	}

	@Test
	void mensagemLongaDemaisFicaSoComOCorretorLocal() {
		ligarIa();
		ia = texto -> {
			throw new AssertionError("não devia chamar a IA");
		};

		Etapas etapas = revisor.corrigirEmEtapas("voce nao vem ".repeat(400));

		assertThat(etapas.ia()).isNull();
	}

	@Test
	void mesmaMensagemDeNovoVemDoCacheSemGastarCota() throws Exception {
		ligarIa();
		ia = texto -> "Você não vai acreditar no que aconteceu!";

		revisor.corrigirEmEtapas("voce nao vai acreditar oque aconteceu").ia().get(5, TimeUnit.SECONDS);
		ResultadoCorrecao denovo = revisor.corrigirEmEtapas("voce nao vai acreditar oque aconteceu").ia().get(5, TimeUnit.SECONDS);
		revisor.corrigir("voce nao vai acreditar oque aconteceu");

		assertThat(denovo.corrigido()).isEqualTo("Você não vai acreditar no que aconteceu!");
		assertThat(chamadasIa).hasValue(1);
	}

	@Test
	void cacheNaoGuardaFalhaEMudarODicionarioPedeDeNovo() {
		ligarIa();
		ia = texto -> {
			throw new FalhaIa("Limite de uso da IA atingido (429)");
		};
		revisor.corrigir("voce viu o Zezinho");
		ia = texto -> "Você viu o Zézinho?";
		revisor.corrigir("voce viu o Zezinho");
		assertThat(chamadasIa).hasValue(2);

		configuracao.adicionarPalavra("Zezinho");
		ResultadoCorrecao comDicionario = revisor.corrigir("voce viu o Zezinho");

		assertThat(chamadasIa).hasValue(3);
		assertThat(comDicionario.corrigido()).isEqualTo("Você viu o Zezinho");
	}

	@Test
	void cacheTemLimiteEContinuaCertoDepoisDeEncher() {
		ligarIa();
		ia = texto -> texto.toUpperCase();

		for (int i = 0; i < 500; i++) {
			assertThat(revisor.corrigir("mensagem numero " + i).corrigido()).isEqualTo("MENSAGEM NUMERO " + i);
		}
		// a primeira já saiu do cache (limite de 64) e precisa ir de novo à IA
		int antes = chamadasIa.get();
		revisor.corrigir("mensagem numero 0");
		assertThat(chamadasIa.get()).isEqualTo(antes + 1);
	}

	@Test
	void muitosAtalhosAoMesmoTempoNaoMisturamResultados() {
		ligarIa();
		ia = texto -> {
			dormir(Duration.ofMillis(ThreadLocalRandom.current().nextInt(0, 60)));
			if (texto.endsWith("7")) {
				throw new FalhaIa("A IA está sobrecarregada agora (503)");
			}
			return texto.toUpperCase();
		};

		List<CompletableFuture<Void>> todos = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			String texto = "mensagem de teste " + i;
			todos.add(CompletableFuture.runAsync(() -> {
				Etapas etapas = revisor.corrigirEmEtapas(texto);
				assertThat(etapas.local().original()).isEqualTo(texto);
				try {
					ResultadoCorrecao revisado = etapas.ia().join();
					assertThat(revisado.original()).isEqualTo(texto);
					assertThat(revisado.corrigido()).isEqualTo(texto.toUpperCase());
				} catch (CompletionException e) {
					assertThat(texto).endsWith("7");
					assertThat(e).hasCauseInstanceOf(FalhaIa.class);
				}
			}));
		}

		CompletableFuture.allOf(todos.toArray(CompletableFuture[]::new)).orTimeout(60, TimeUnit.SECONDS).join();
	}

	// ---- tom ----

	@Test
	void ajustarOTomPodeTrocarPalavras() {
		ligarIa();
		tomIa = (texto, tom) -> {
			assertThat(tom).isEqualTo(Tom.FORMAL);
			return "Prezado, você poderá vir amanhã?";
		};

		ResultadoCorrecao formal = revisor.reescrever("vc vem amanha?", Tom.FORMAL);

		assertThat(formal.original()).isEqualTo("vc vem amanha?");
		assertThat(formal.corrigido()).isEqualTo("Prezado, você poderá vir amanhã?");
		assertThat(formal.correcoes()).isNotEmpty();
	}

	@Test
	void tomComIaDesligadaExplicaOQueFazer() {
		assertThatThrownBy(() -> revisor.reescrever("vc vem amanha?", Tom.CURTO)).isInstanceOf(FalhaIa.class)
				.hasMessageContaining("Ligue a IA");
		assertThat(revisor.iaLigada()).isFalse();
	}

	@Test
	void tomEmSegundoPlanoDevolveOErroPeloFuturo() {
		ligarIa();
		tomIa = (texto, tom) -> {
			throw new FalhaIa("Limite de uso da IA atingido (429)");
		};

		assertThatThrownBy(() -> revisor.reescreverEmSegundoPlano("oi", Tom.GENTIL).join())
				.hasCauseInstanceOf(FalhaIa.class);
	}

	private static void dormir(Duration tempo) {
		try {
			Thread.sleep(tempo);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
