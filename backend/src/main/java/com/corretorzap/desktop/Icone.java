package com.corretorzap.desktop;

import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

/** Ícones do app (gerados em src/main/resources/icone). */
final class Icone {

	private Icone() {
	}

	static InputStream png(int tamanho) {
		InputStream entrada = Icone.class.getResourceAsStream("/icone/icone-" + tamanho + ".png");
		if (entrada == null) {
			throw new IllegalStateException("Ícone " + tamanho + "px não encontrado");
		}
		return entrada;
	}

	static Image awt(int tamanho) {
		try (InputStream entrada = png(tamanho)) {
			return ImageIO.read(entrada);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
