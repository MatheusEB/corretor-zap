package com.corretorzap.desktop;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Integrações pequenas com o Windows: tema, barra de título escura e "iniciar com o Windows". */
public final class Windows {

	private static final String CHAVE_TEMA = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
	private static final String CHAVE_INICIAR = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
	private static final String NOME_INICIAR = "CorretorZap";
	private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

	private interface Dwmapi extends StdCallLibrary {
		Dwmapi INSTANCIA = Native.load("dwmapi", Dwmapi.class);

		int DwmSetWindowAttribute(HWND hwnd, int atributo, IntByReference valor, int tamanho);
	}

	private interface User32 extends StdCallLibrary {
		User32 INSTANCIA = Native.load("user32", User32.class);

		HWND FindWindowW(String classe, char[] titulo);

		HWND GetForegroundWindow();

		boolean SetForegroundWindow(HWND hwnd);

		boolean BringWindowToTop(HWND hwnd);

		int GetWindowThreadProcessId(HWND hwnd, IntByReference processo);

		boolean AttachThreadInput(int deThread, int paraThread, boolean anexar);
	}

	private interface Kernel32 extends StdCallLibrary {
		Kernel32 INSTANCIA = Native.load("kernel32", Kernel32.class);

		int GetCurrentThreadId();
	}

	private Windows() {
	}

	public static boolean ehWindows() {
		return System.getProperty("os.name", "").startsWith("Windows");
	}

	/** true se o Windows está no modo escuro para apps. */
	public static boolean temaEscuro() {
		if (!ehWindows()) {
			return false;
		}
		try {
			return Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, CHAVE_TEMA, "AppsUseLightTheme") == 0;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Pinta a barra de título nativa de escuro/claro (Windows 10 20H1+ e 11). */
	public static void barraDeTituloEscura(String tituloDaJanela, boolean escura) {
		if (!ehWindows()) {
			return;
		}
		try {
			HWND hwnd = User32.INSTANCIA.FindWindowW(null, (tituloDaJanela + "\0").toCharArray());
			if (hwnd != null) {
				Dwmapi.INSTANCIA.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(escura ? 1 : 0), 4);
			}
		} catch (RuntimeException | UnsatisfiedLinkError e) {
			// só estética; versões antigas do Windows não têm o atributo
		}
	}

	/** A janela que está recebendo o teclado agora (para devolver o foco a ela depois). */
	public static HWND janelaEmFoco() {
		return ehWindows() ? User32.INSTANCIA.GetForegroundWindow() : null;
	}

	/** Devolve o foco a uma janela (de qualquer app), com o mesmo contorno de {@link #trazerParaFrente(String)}. */
	public static boolean focar(HWND janela) {
		if (janela == null || !ehWindows()) {
			return false;
		}
		focarSemBloqueio(janela);
		return janela.equals(User32.INSTANCIA.GetForegroundWindow());
	}

	/**
	 * Traz para a frente uma janela deste processo mesmo quando outro app está em primeiro plano (o Windows
	 * normalmente bloqueia isso). Precisa ser chamado na thread dona da janela.
	 *
	 * @return true se a janela ficou de fato com o foco do teclado
	 */
	public static boolean trazerParaFrente(String titulo) {
		if (!ehWindows()) {
			return false;
		}
		HWND alvo = User32.INSTANCIA.FindWindowW(null, (titulo + "\0").toCharArray());
		if (alvo == null) {
			return false;
		}
		focarSemBloqueio(alvo);
		return alvo.equals(User32.INSTANCIA.GetForegroundWindow());
	}

	// o Windows só deixa trocar o foco quem está ligado à entrada do teclado; anexar à thread em foco libera
	private static void focarSemBloqueio(HWND alvo) {
		HWND atual = User32.INSTANCIA.GetForegroundWindow();
		int threadDoFoco = User32.INSTANCIA.GetWindowThreadProcessId(atual, null);
		int estaThread = Kernel32.INSTANCIA.GetCurrentThreadId();
		boolean anexou = threadDoFoco != estaThread && User32.INSTANCIA.AttachThreadInput(estaThread, threadDoFoco, true);
		try {
			User32.INSTANCIA.BringWindowToTop(alvo);
			User32.INSTANCIA.SetForegroundWindow(alvo);
		} finally {
			if (anexou) {
				User32.INSTANCIA.AttachThreadInput(estaThread, threadDoFoco, false);
			}
		}
	}

	/** Caminho do .exe quando o app foi instalado pelo jpackage; null rodando pelo jar. */
	public static String executavelInstalado() {
		return System.getProperty("jpackage.app-path");
	}

	public static boolean iniciaComWindows() {
		return ehWindows() && Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, CHAVE_INICIAR, NOME_INICIAR);
	}

	public static void iniciarComWindows(boolean ligar) {
		String exe = executavelInstalado();
		if (!ehWindows() || exe == null) {
			throw new IllegalStateException("Disponível só na versão instalada do app");
		}
		if (ligar) {
			Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, CHAVE_INICIAR, NOME_INICIAR,
					"\"" + exe + "\" --minimizado");
		} else if (iniciaComWindows()) {
			Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, CHAVE_INICIAR, NOME_INICIAR);
		}
	}

}
