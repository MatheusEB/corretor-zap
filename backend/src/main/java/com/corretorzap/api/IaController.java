package com.corretorzap.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.corretorzap.correcao.ResultadoCorrecao;
import com.corretorzap.ia.ClienteIa.FalhaIa;
import com.corretorzap.ia.ConfiguracaoIa;
import com.corretorzap.ia.ConfiguracaoIaService;
import com.corretorzap.ia.RevisorIa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/ia")
public class IaController {

	/** @param chave null mantém a chave salva; vazio apaga */
	public record PedidoIa(boolean ativo, @Size(max = 20) String provedor, @Size(max = 300) String url,
			@Size(max = 100) String modelo, @Size(max = 500) String chave) {
	}

	public record PedidoTeste(@NotBlank @Size(max = 2000) String texto) {
	}

	public record ResultadoTeste(ResultadoCorrecao resultado, long milissegundos) {
	}

	private final ConfiguracaoIaService configuracao;
	private final RevisorIa revisor;

	public IaController(ConfiguracaoIaService configuracao, RevisorIa revisor) {
		this.configuracao = configuracao;
		this.revisor = revisor;
	}

	@GetMapping
	public ConfiguracaoIa.Publica config() {
		return configuracao.atual().publica();
	}

	@PutMapping
	public ConfiguracaoIa.Publica salvar(@Valid @RequestBody PedidoIa pedido) {
		return configuracao.salvar(pedido.ativo(), pedido.provedor(), pedido.url(), pedido.modelo(), pedido.chave()).publica();
	}

	/** Manda uma frase para a IA configurada (mesmo desligada) e mostra o que voltou, ou o erro. */
	@PostMapping("/testar")
	public ResultadoTeste testar(@Valid @RequestBody PedidoTeste pedido) {
		ConfiguracaoIa atual = configuracao.atual();
		if (atual.url().isEmpty() || atual.modelo().isEmpty()) {
			throw new IllegalArgumentException("Preencha o endereço e o modelo");
		}
		long inicio = System.nanoTime();
		ResultadoCorrecao resultado = revisor.comIa(atual, pedido.texto());
		return new ResultadoTeste(resultado, (System.nanoTime() - inicio) / 1_000_000);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail argumentoInvalido(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(FalhaIa.class)
	public ProblemDetail falhaIa(FalhaIa e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
	}

}
