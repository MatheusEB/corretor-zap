package com.corretorzap.atalho;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

class AtalhoTest {

	@Test
	void interpretaAtalhoPadrao() {
		Atalho atalho = Atalho.parse("ctrl + shift + space");

		assertThat(atalho).isEqualTo(new Atalho(true, true, false, NativeKeyEvent.VC_SPACE));
	}

	@Test
	void aceitaLetrasNumerosETeclasDeFuncao() {
		assertThat(Atalho.parse("ALT+K").tecla()).isEqualTo(NativeKeyEvent.VC_K);
		assertThat(Atalho.parse("CTRL+1").tecla()).isEqualTo(NativeKeyEvent.VC_1);
		assertThat(Atalho.parse("CTRL+F9").tecla()).isEqualTo(NativeKeyEvent.VC_F9);
	}

	@Test
	void recusaAtalhosInvalidos() {
		assertThatThrownBy(() -> Atalho.parse("CTRL+BANANA")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Atalho.parse("CTRL+SHIFT")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Atalho.parse("SHIFT+A")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Atalho.parse("CTRL+A+B")).isInstanceOf(IllegalArgumentException.class);
	}

}
