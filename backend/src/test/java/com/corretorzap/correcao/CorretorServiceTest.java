package com.corretorzap.correcao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;

import tools.jackson.databind.json.JsonMapper;

class CorretorServiceTest {

	@TempDir
	Path pasta;

	private ConfiguracaoService configuracao;
	private CorretorService corretor;

	@BeforeEach
	void setUp() {
		configuracao = new ConfiguracaoService(pasta, JsonMapper.builder().build());
		corretor = new CorretorService(configuracao);
	}

	@Test
	void corrigeErrosComunsDeDigitacao() {
		ResultadoCorrecao resultado = corretor.corrigir("Voce nao vai acreditar no que aconteceu");

		assertThat(resultado.corrigido()).isEqualTo("Você não vai acreditar no que aconteceu.");
		assertThat(resultado.correcoes()).extracting(Correcao::trecho).containsExactly("Voce", "nao", "aconteceu");
	}

	@Test
	void escolheASugestaoMaisParecidaComOQueFoiDigitado() {
		// a primeira sugestão do LanguageTool para "voces" é "Bosses"
		ResultadoCorrecao resultado = corretor.corrigir("e voces vem que horas");

		assertThat(resultado.correcoes()).filteredOn(c -> c.trecho().equals("voces"))
				.singleElement().extracting(Correcao::sugestao).isEqualTo("vocês");
	}

	@Test
	void sugestaoDuvidosaNaoEntraNoTextoCorrigido() {
		// nenhuma sugestão do LanguageTool para "amanah" é "amanhã"; a primeira seria "Ananás"
		ResultadoCorrecao resultado = corretor.corrigir("eu vou amanah cedo");

		assertThat(resultado.corrigido()).isEqualTo("Eu vou amanah cedo.");
		assertThat(resultado.correcoes()).filteredOn(c -> c.trecho().equals("amanah"))
				.singleElement().extracting(Correcao::certeza).isEqualTo(false);
	}

	@Test
	void comecaFrase() {
		assertThat(CorretorService.comecaFrase("Joao veio", 0)).isTrue();
		assertThat(CorretorService.comecaFrase("oi. Joao veio", 4)).isTrue();
		assertThat(CorretorService.comecaFrase("oi\n  Joao veio", 5)).isTrue();
		assertThat(CorretorService.comecaFrase("falei com Joao", 10)).isFalse();
	}

	@Test
	void naoMexeEmTextoCorreto() {
		ResultadoCorrecao resultado = corretor.corrigir("Você não vai acreditar no que aconteceu.");

		assertThat(resultado.mudou()).isFalse();
		assertThat(resultado.correcoes()).isEmpty();
	}

	@Test
	void respeitaDicionarioPessoal() {
		configuracao.adicionarPalavra("Voce");

		ResultadoCorrecao resultado = corretor.corrigir("Voce nao vai acreditar");

		assertThat(resultado.corrigido()).isEqualTo("Voce não vai acreditar.");
	}

	@Test
	void respeitaRegrasDesativadas() {
		String regra = corretor.corrigir("Voce nao vai acreditar").correcoes().getFirst().regra();
		Configuracao cfg = configuracao.atual();
		configuracao.salvar(cfg.comRegrasDesativadas(new TreeSet<>(Set.of(regra))));

		ResultadoCorrecao resultado = corretor.corrigir("Voce nao vai acreditar");

		assertThat(resultado.correcoes()).extracting(Correcao::regra).doesNotContain(regra);
	}

	@Test
	void textoLongoDivididoEmTrechosCorrigeTudo() {
		String frase = "Voce nao vai acreditar. ";
		String texto = frase.repeat(400); // ~9.600 caracteres, vários trechos

		ResultadoCorrecao resultado = corretor.corrigir(texto);

		assertThat(resultado.corrigido()).isEqualTo("Você não vai acreditar. ".repeat(400));
		assertThat(resultado.correcoes()).hasSize(800);
	}

	@Test
	void divideEmFimDeFraseSemPerderNada() {
		String texto = "Uma frase qualquer aqui. ".repeat(300);

		List<int[]> partes = CorretorService.dividir(texto);

		assertThat(partes).hasSizeGreaterThan(1);
		int esperado = 0;
		for (int[] parte : partes) {
			assertThat(parte[0]).isEqualTo(esperado);
			assertThat(parte[1] - parte[0]).isLessThanOrEqualTo(CorretorService.TAMANHO_TRECHO);
			assertThat(texto.charAt(parte[1] - 1)).isIn('.', ' ');
			esperado = parte[1];
		}
		assertThat(esperado).isEqualTo(texto.length());
	}

	@Test
	void textoSemEspacosAindaEhDividido() {
		String texto = "a".repeat(5000);

		assertThat(CorretorService.dividir(texto)).extracting(p -> p[1] - p[0]).containsExactly(2000, 2000, 1000);
	}

	@Test
	void configuracaoSobreviveAoReinicio() {
		configuracao.adicionarPalavra("Zapzap");

		ConfiguracaoService recarregada = new ConfiguracaoService(pasta, JsonMapper.builder().build());

		assertThat(recarregada.atual().dicionario()).containsExactly("zapzap");
	}

}
