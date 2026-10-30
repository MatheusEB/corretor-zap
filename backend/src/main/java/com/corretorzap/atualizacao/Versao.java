package com.corretorzap.atualizacao;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Número de versão no formato das releases do GitHub: "v0.3.0" ou "0.3.0". */
record Versao(int[] partes) implements Comparable<Versao> {

	private static final Pattern FORMATO = Pattern.compile("v?\\d{1,5}(\\.\\d{1,5}){0,3}");

	static Optional<Versao> ler(String texto) {
		if (texto == null || !FORMATO.matcher(texto.strip()).matches()) {
			return Optional.empty();
		}
		String numeros = texto.strip().replaceFirst("^v", "");
		return Optional.of(new Versao(Arrays.stream(numeros.split("\\.")).mapToInt(Integer::parseInt).toArray()));
	}

	@Override
	public int compareTo(Versao outra) {
		for (int i = 0; i < Math.max(partes.length, outra.partes.length); i++) {
			// "0.3" é igual a "0.3.0"
			int diferenca = Integer.compare(parte(i), outra.parte(i));
			if (diferenca != 0) {
				return diferenca;
			}
		}
		return 0;
	}

	boolean maiorQue(Versao outra) {
		return compareTo(outra) > 0;
	}

	private int parte(int i) {
		return i < partes.length ? partes[i] : 0;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Versao v && compareTo(v) == 0;
	}

	@Override
	public int hashCode() {
		int fim = partes.length;
		while (fim > 0 && partes[fim - 1] == 0) {
			fim--;
		}
		return Arrays.hashCode(Arrays.copyOf(partes, fim));
	}

	@Override
	public String toString() {
		return String.join(".", Arrays.stream(partes).mapToObj(String::valueOf).toList());
	}

}
