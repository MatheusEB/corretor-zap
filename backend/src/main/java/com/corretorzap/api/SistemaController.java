package com.corretorzap.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.corretorzap.desktop.AppDesktop;
import com.corretorzap.desktop.Windows;

/** O que é do sistema operacional, não do corretor: janela do app e "iniciar com o Windows". */
@RestController
@RequestMapping("/api")
public class SistemaController {

	public record Sistema(boolean instalado, boolean iniciarComWindows) {
	}

	public record PedidoSistema(boolean iniciarComWindows) {
	}

	private final ObjectProvider<AppDesktop> appDesktop;

	public SistemaController(ObjectProvider<AppDesktop> appDesktop) {
		this.appDesktop = appDesktop;
	}

	@GetMapping("/sistema")
	public Sistema sistema() {
		return new Sistema(Windows.executavelInstalado() != null, Windows.iniciaComWindows());
	}

	@PutMapping("/sistema")
	public Sistema salvar(@RequestBody PedidoSistema pedido) {
		Windows.iniciarComWindows(pedido.iniciarComWindows());
		return sistema();
	}

	/** Chamado quando o usuário abre o app de novo: em vez de outra instância, mostra a janela desta. */
	@PostMapping("/janela/mostrar")
	public ResponseEntity<Void> mostrarJanela() {
		AppDesktop app = appDesktop.getIfAvailable();
		if (app == null) {
			return ResponseEntity.notFound().build();
		}
		app.mostrar();
		return ResponseEntity.ok().build();
	}

	/** O painel avisa quando o tema muda, para a barra de título nativa acompanhar. */
	@PostMapping("/janela/tema")
	public ResponseEntity<Void> temaDaJanela(@RequestParam boolean escuro) {
		AppDesktop app = appDesktop.getIfAvailable();
		if (app != null) {
			app.barraDeTitulo(escuro);
		}
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(IllegalStateException.class)
	public ProblemDetail indisponivel(IllegalStateException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

}
