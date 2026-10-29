package com.corretorzap.config;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Gravação dos arquivos de ~/.corretor-zap. */
public final class Arquivos {

	private static final int TENTATIVAS_AO_SALVAR = 10;

	private Arquivos() {
	}

	/** Grava num temporário e troca de uma vez, para nunca deixar o arquivo pela metade. */
	public static void gravar(Path arquivo, String conteudo) throws IOException {
		Files.createDirectories(arquivo.getParent());
		Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
		Files.writeString(temporario, conteudo);
		substituir(temporario, arquivo);
	}

	// No Windows, antivírus e indexador abrem o arquivo por alguns milissegundos logo depois de cada gravação;
	// enquanto isso a troca falha com AccessDeniedException. Tenta de novo com espera crescente.
	private static void substituir(Path temporario, Path arquivo) throws IOException {
		for (int tentativa = 1;; tentativa++) {
			try {
				Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (AccessDeniedException e) {
				if (tentativa == TENTATIVAS_AO_SALVAR) {
					throw e;
				}
				try {
					Thread.sleep(15L * tentativa);
				} catch (InterruptedException interrompido) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

}
