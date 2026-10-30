package com.corretorzap.atualizacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Roda o atualizar.ps1 de verdade no PowerShell, com pastas falsas no lugar do app. */
@EnabledOnOs(OS.WINDOWS)
class AtualizarScriptTest {

	/** No Windows todo PID é múltiplo de 4, então este nunca existe. */
	private static final long NAO_EXISTE = 999_999;

	@TempDir
	Path pasta;

	private Path script;
	private Path atual;
	private Path nova;
	private Path log;

	@BeforeEach
	void setUp() throws IOException {
		script = pasta.resolve("atualizar.ps1");
		try (InputStream modelo = AtualizarScriptTest.class.getResourceAsStream("/atualizacao/atualizar.ps1")) {
			Files.write(script, new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
			Files.write(script, modelo.readAllBytes(), StandardOpenOption.APPEND);
		}
		atual = pasta.resolve("Corretor Zap");
		nova = pasta.resolve("Corretor Zap.nova");
		log = pasta.resolve("atualizacao.log");
		Files.createDirectories(atual.resolve("app"));
		Files.writeString(atual.resolve("Corretor Zap.exe"), "antiga");
		Files.writeString(atual.resolve("app").resolve("velho.jar"), "velho");
		Files.createDirectories(nova.resolve("app"));
		Files.writeString(nova.resolve("Corretor Zap.exe"), "nova");
		Files.writeString(nova.resolve("app").resolve("novo.jar"), "novo");
	}

	private Process rodar(long processo) throws IOException {
		List<String> comando = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
				"-File", script.toString(), "-Processo", String.valueOf(processo), "-Atual", atual.toString(), "-Nova",
				nova.toString(), "-Exe", "Corretor Zap.exe", "-Log", log.toString(), "-NaoAbrir"));
		return new ProcessBuilder(comando).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
	}

	private static void terminar(Process p) throws InterruptedException {
		assertThat(p.waitFor(90, TimeUnit.SECONDS)).as("o script terminou").isTrue();
	}

	/** Um processo qualquer que fica vivo alguns segundos, no papel do app que ainda está fechando. */
	private static Process appFechando(int segundos) throws IOException {
		return new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds " + segundos)
				.start();
	}

	@Test
	void trocaAPastaInteiraPelaNova() throws Exception {
		terminar(rodar(NAO_EXISTE)); // o app já fechou

		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).isEqualTo("nova");
		assertThat(atual.resolve("app").resolve("novo.jar")).exists();
		assertThat(atual.resolve("app").resolve("velho.jar")).as("nada da versão antiga sobra").doesNotExist();
		assertThat(nova).doesNotExist();
		assertThat(pasta.resolve("Corretor Zap.antiga")).doesNotExist();
		assertThat(Files.readString(log)).contains("versão nova instalada");
	}

	@Test
	void esperaOAppFecharAntesDeMexerNaPasta() throws Exception {
		Process app = appFechando(4);
		long inicio = System.nanoTime();

		Process atualizacao = rodar(app.pid());
		Thread.sleep(1500);
		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).as("o app ainda está aberto").isEqualTo("antiga");
		terminar(atualizacao);

		assertThat(app.isAlive()).isFalse();
		assertThat((System.nanoTime() - inicio) / 1_000_000).isGreaterThanOrEqualTo(3000);
		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).isEqualTo("nova");
	}

	@Test
	void esperaQuandoQuemAbriuOScriptEOProprioApp() throws Exception {
		// como no app de verdade: o processo que o script espera é o pai dele, e fecha uns segundos depois
		Path pai = pasta.resolve("pai.ps1");
		Files.writeString(pai, """
				param($Script, $Atual, $Nova, $Log)
				$q = [char]34
				Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass',
				    '-File', "$q$Script$q", '-Processo', $PID, '-Atual', "$q$Atual$q", '-Nova', "$q$Nova$q",
				    '-Exe', "${q}Corretor Zap.exe$q", '-Log', "$q$Log$q", '-NaoAbrir')
				Start-Sleep -Seconds 3
				""");
		Process app = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
				pai.toString(), "-Script", script.toString(), "-Atual", atual.toString(), "-Nova", nova.toString(), "-Log",
				log.toString()).start();
		assertThat(app.waitFor(30, TimeUnit.SECONDS)).isTrue();

		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (System.nanoTime() < limite && !(Files.exists(log) && Files.readString(log).contains("instalada"))) {
			Thread.sleep(200);
		}

		assertThat(Files.readString(log)).contains("o app fechou").contains("versão nova instalada").doesNotContain("ERRO");
		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).isEqualTo("nova");
	}

	@Test
	void versaoNovaSemOExecutavelDeixaAAntigaIntacta() throws Exception {
		Files.delete(nova.resolve("Corretor Zap.exe"));

		terminar(rodar(NAO_EXISTE));

		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).isEqualTo("antiga");
		assertThat(atual.resolve("app").resolve("velho.jar")).exists();
		assertThat(nova).as("a versão quebrada é apagada").doesNotExist();
		assertThat(Files.readString(log)).contains("ERRO");
	}

	@Test
	void pastaAtualPresaPorOutroProgramaDesisteSemPerderNada() throws Exception {
		// um arquivo aberto sem permitir apagar trava a renomeação da pasta, como um antivírus segurando o .exe
		Process trava = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
				"$f = [System.IO.File]::Open('" + atual.resolve("Corretor Zap.exe")
						+ "', 'Open', 'Read', 'Read'); Start-Sleep -Seconds 25; $f.Close()")
				.start();
		try {
			Thread.sleep(1500);
			terminar(rodar(NAO_EXISTE));
		} finally {
			trava.destroyForcibly().waitFor();
		}

		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).isEqualTo("antiga");
		assertThat(Files.readString(log)).contains("ERRO");
		assertThat(nova).doesNotExist();
	}

	@Test
	void caminhosComEspacoEAcentoFuncionam() throws Exception {
		Path outra = pasta.resolve("Programas do João");
		Files.createDirectories(outra);
		Path atualAcentuada = outra.resolve("Corretor Zap");
		Path novaAcentuada = outra.resolve("Corretor Zap.nova");
		Files.move(atual, atualAcentuada);
		Files.move(nova, novaAcentuada);
		atual = atualAcentuada;
		nova = novaAcentuada;

		terminar(rodar(NAO_EXISTE));

		assertThat(Files.readString(atual.resolve("Corretor Zap.exe"))).isEqualTo("nova");
		try (FileChannel c = FileChannel.open(log)) {
			assertThat(c.size()).isPositive();
		}
	}

}
