package com.corretorzap.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.corretorzap.atualizacao.AtualizacaoService;
import com.corretorzap.atualizacao.AtualizacaoService.Estado;

/** Versão do app e atualização automática pelas releases do GitHub. */
@RestController
@RequestMapping("/api/atualizacao")
public class AtualizacaoController {

	private final AtualizacaoService atualizacao;

	public AtualizacaoController(AtualizacaoService atualizacao) {
		this.atualizacao = atualizacao;
	}

	@GetMapping
	public Estado estado() {
		return atualizacao.estado();
	}

	@PostMapping("/verificar")
	public Estado verificar() {
		return atualizacao.verificar();
	}

	/** Baixa e instala; o app fecha e abre de novo sozinho. A tela acompanha o progresso pelo GET. */
	@PostMapping("/instalar")
	public Estado instalar() {
		return atualizacao.instalar();
	}

	@PostMapping("/aviso-visto")
	public ResponseEntity<Void> avisoVisto() {
		atualizacao.avisoVisto();
		return ResponseEntity.noContent().build();
	}

	@ExceptionHandler(IllegalStateException.class)
	public ProblemDetail semVersaoNova(IllegalStateException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

}
