package com.corretorzap.atalho;

import java.lang.reflect.Field;
import java.util.Locale;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

/**
 * Combinação de teclas no formato "CTRL+SHIFT+SPACE". A tecla final usa os nomes das constantes
 * {@code NativeKeyEvent.VC_*} sem o prefixo: A..Z, 0..9, F1..F12, SPACE, ENTER, etc.
 */
public record Atalho(boolean ctrl, boolean shift, boolean alt, int tecla) {

	private static final int MODIFICADORES = NativeInputEvent.CTRL_MASK | NativeInputEvent.SHIFT_MASK
			| NativeInputEvent.ALT_MASK | NativeInputEvent.META_MASK;

	public static Atalho parse(String texto) {
		boolean ctrl = false;
		boolean shift = false;
		boolean alt = false;
		Integer tecla = null;
		for (String parte : texto.toUpperCase(Locale.ROOT).split("\\+")) {
			switch (parte.trim()) {
				case "CTRL", "CONTROL" -> ctrl = true;
				case "SHIFT" -> shift = true;
				case "ALT" -> alt = true;
				case "" -> throw new IllegalArgumentException("Atalho inválido: " + texto);
				default -> {
					if (tecla != null) {
						throw new IllegalArgumentException("O atalho só pode ter uma tecla além dos modificadores: " + texto);
					}
					tecla = codigoDaTecla(parte.trim());
				}
			}
		}
		if (tecla == null) {
			throw new IllegalArgumentException("Falta a tecla principal no atalho: " + texto);
		}
		if (!ctrl && !alt) {
			throw new IllegalArgumentException("Use CTRL ou ALT no atalho, senão ele dispara enquanto você digita: " + texto);
		}
		return new Atalho(ctrl, shift, alt, tecla);
	}

	public boolean corresponde(NativeKeyEvent evento) {
		int modificadores = evento.getModifiers() & MODIFICADORES;
		int esperado = (ctrl ? NativeInputEvent.CTRL_MASK : 0)
				| (shift ? NativeInputEvent.SHIFT_MASK : 0)
				| (alt ? NativeInputEvent.ALT_MASK : 0);
		return evento.getKeyCode() == tecla
				&& temTodos(modificadores, esperado)
				&& (modificadores & ~esperado) == 0;
	}

	// CTRL_MASK cobre Ctrl esquerdo e direito; basta um dos lados estar apertado
	private static boolean temTodos(int modificadores, int esperado) {
		for (int mascara : new int[] { NativeInputEvent.CTRL_MASK, NativeInputEvent.SHIFT_MASK, NativeInputEvent.ALT_MASK }) {
			if ((esperado & mascara) != 0 && (modificadores & mascara) == 0) {
				return false;
			}
		}
		return true;
	}

	private static int codigoDaTecla(String nome) {
		try {
			Field campo = NativeKeyEvent.class.getField("VC_" + nome);
			return campo.getInt(null);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new IllegalArgumentException("Tecla desconhecida: " + nome);
		}
	}

}
