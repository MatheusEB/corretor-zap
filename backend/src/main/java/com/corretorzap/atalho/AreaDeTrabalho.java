package com.corretorzap.atalho;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.util.UUID;

/**
 * Lê e escreve texto no app que está em foco simulando Ctrl+A / Ctrl+C / Ctrl+V,
 * sem precisar saber nada sobre o app (funciona no WhatsApp e em qualquer outro).
 */
class AreaDeTrabalho {

	private static final long ESPERA_COPIA_MS = 600;
	// quanto esperar depois do Ctrl+V antes de restaurar o clipboard antigo: tempo para o app de destino ler a
	// área de transferência. Não há sinal de que ele terminou, então é um valor fixo conservador. O texto já
	// apareceu na tela quando essa espera começa, então ela não deixa nada mais lento para quem usa; com 120 ms
	// o app que acabou de receber o foco de volta da prévia às vezes colava o clipboard antigo.
	private static final long ESPERA_COLAR_MS = 500;
	// atraso entre cada tecla simulada; some em todos os Ctrl+A/C/V. Baixo o bastante para ser rápido, alto o
	// bastante para o app de destino não perder uma tecla.
	private static final int ATRASO_TECLA_MS = 8;

	private final Robot robot;
	private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

	AreaDeTrabalho() throws AWTException {
		robot = new Robot();
		robot.setAutoDelay(ATRASO_TECLA_MS);
	}

	/** Garante que os modificadores do atalho não "vazem" para os Ctrl+C/V simulados. */
	void soltarModificadores() {
		robot.keyRelease(KeyEvent.VK_SHIFT);
		robot.keyRelease(KeyEvent.VK_ALT);
		robot.keyRelease(KeyEvent.VK_CONTROL);
	}

	Transferable salvarClipboard() {
		try {
			return clipboard.getContents(null);
		} catch (IllegalStateException e) {
			return null;
		}
	}

	void restaurarClipboard(Transferable anterior) {
		if (anterior != null) {
			definirClipboard(anterior);
		}
	}

	/** @return o texto copiado, ou null se nada foi copiado (campo vazio ou sem seleção) */
	String copiar(boolean selecionarTudo) throws InterruptedException {
		String marcador = "corretor-zap:" + UUID.randomUUID();
		definirClipboard(new StringSelection(marcador));
		if (selecionarTudo) {
			ctrlMais(KeyEvent.VK_A);
		}
		ctrlMais(KeyEvent.VK_C);

		long limite = System.currentTimeMillis() + ESPERA_COPIA_MS;
		while (System.currentTimeMillis() < limite) {
			Thread.sleep(10);
			String atual = lerTexto();
			if (atual != null && !atual.equals(marcador)) {
				return atual;
			}
		}
		return null;
	}

	void colar(String texto) throws InterruptedException {
		definirClipboard(new StringSelection(texto));
		ctrlMais(KeyEvent.VK_V);
		// o app lê a área de transferência de forma assíncrona; espera antes de restaurar o conteúdo antigo
		Thread.sleep(ESPERA_COLAR_MS);
	}

	private void ctrlMais(int tecla) {
		robot.keyPress(KeyEvent.VK_CONTROL);
		robot.keyPress(tecla);
		robot.keyRelease(tecla);
		robot.keyRelease(KeyEvent.VK_CONTROL);
	}

	private String lerTexto() {
		try {
			if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
				return (String) clipboard.getData(DataFlavor.stringFlavor);
			}
		} catch (Exception e) {
			// outro app está com a área de transferência aberta; tenta de novo no próximo ciclo
		}
		return null;
	}

	private void definirClipboard(Transferable conteudo) {
		for (int tentativa = 0; tentativa < 10; tentativa++) {
			try {
				clipboard.setContents(conteudo, null);
				return;
			} catch (IllegalStateException e) {
				robot.delay(20);
			}
		}
	}

}
