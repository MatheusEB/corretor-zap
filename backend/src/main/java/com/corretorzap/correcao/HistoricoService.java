package com.corretorzap.correcao;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

/** Últimas correções feitas pelo atalho (só em memória). */
@Service
public class HistoricoService {

	private static final int MAXIMO = 50;

	public record Registro(Instant quando, String original, String corrigido, List<Correcao> correcoes) {
	}

	private final ConcurrentLinkedDeque<Registro> registros = new ConcurrentLinkedDeque<>();

	public void registrar(ResultadoCorrecao resultado) {
		registros.addFirst(new Registro(Instant.now(), resultado.original(), resultado.corrigido(), resultado.correcoes()));
		while (registros.size() > MAXIMO) {
			registros.pollLast();
		}
	}

	public List<Registro> listar() {
		return List.copyOf(registros);
	}

	public void limpar() {
		registros.clear();
	}

}
