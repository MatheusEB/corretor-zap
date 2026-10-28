package com.corretorzap.config;

import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Preferências do usuário, salvas em ~/.corretor-zap/config.json.
 *
 * @param ativo             liga/desliga a correção pelo atalho
 * @param atalho            combinação de teclas, ex.: "CTRL+SHIFT+SPACE"
 * @param selecionarTudo    true = corrige o campo inteiro (Ctrl+A); false = só o texto já selecionado
 * @param dicionario        palavras que nunca devem ser corrigidas (gírias, nomes...)
 * @param regrasDesativadas ids de regras do LanguageTool que não devem ser aplicadas
 * @param previa            true = o atalho mostra a prévia antes de trocar o texto; false = corrige direto
 */
public record Configuracao(
		boolean ativo,
		@NotBlank @Size(max = 40) String atalho,
		boolean selecionarTudo,
		@Size(max = Configuracao.MAXIMO_PALAVRAS) SortedSet<@Size(max = 100) String> dicionario,
		@Size(max = 1000) SortedSet<@Size(max = 100) String> regrasDesativadas,
		Boolean previa) {

	public static final String ATALHO_PADRAO = "CTRL+SHIFT+SPACE";
	public static final int MAXIMO_PALAVRAS = 5000;

	public Configuracao {
		dicionario = ordenado(dicionario, true);
		regrasDesativadas = ordenado(regrasDesativadas, false);
		// configs salvas antes da prévia existir não têm o campo
		previa = previa == null || previa;
	}

	public static Configuracao padrao() {
		return new Configuracao(true, ATALHO_PADRAO, true, null, null, true);
	}

	public Configuracao comAtivo(boolean novoAtivo) {
		return new Configuracao(novoAtivo, atalho, selecionarTudo, dicionario, regrasDesativadas, previa);
	}

	public Configuracao comDicionario(SortedSet<String> novoDicionario) {
		return new Configuracao(ativo, atalho, selecionarTudo, novoDicionario, regrasDesativadas, previa);
	}

	public Configuracao comRegrasDesativadas(SortedSet<String> novasRegras) {
		return new Configuracao(ativo, atalho, selecionarTudo, dicionario, novasRegras, previa);
	}

	public boolean estaNoDicionario(String palavra) {
		return dicionario.contains(palavra.toLowerCase());
	}

	private static SortedSet<String> ordenado(Collection<String> valores, boolean minusculas) {
		TreeSet<String> copia = new TreeSet<>();
		if (valores != null) {
			valores.stream()
					.map(String::trim)
					.filter(v -> !v.isEmpty())
					.map(v -> minusculas ? v.toLowerCase() : v)
					.forEach(copia::add);
		}
		return Collections.unmodifiableSortedSet(copia);
	}

}
