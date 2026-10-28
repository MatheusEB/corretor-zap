package com.corretorzap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class CorretorZapApplication {

	private static final String ENDERECO = "http://127.0.0.1:8765";

	public static void main(String[] args) {
		// já está aberto? só traz a janela da outra instância para a frente
		boolean demonstracao = Arrays.asList(args).contains("--corretor.demo=true");
		if (!demonstracao && pedirParaInstanciaAberta()) {
			return;
		}
		boolean minimizado = Arrays.asList(args).contains("--minimizado");
		// headless(false): o atalho global e a janela precisam do teclado, da tela e da área de transferência
		new SpringApplicationBuilder(CorretorZapApplication.class)
				.headless(false)
				.properties("corretor.minimizado=" + minimizado)
				.run(args);
	}

	private static boolean pedirParaInstanciaAberta() {
		try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build()) {
			HttpRequest pedido = HttpRequest.newBuilder(URI.create(ENDERECO + "/api/janela/mostrar"))
					.timeout(Duration.ofSeconds(2))
					.POST(HttpRequest.BodyPublishers.noBody())
					.build();
			return http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

}
