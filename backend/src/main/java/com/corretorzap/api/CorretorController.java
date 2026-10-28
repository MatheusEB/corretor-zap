package com.corretorzap.api;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.corretorzap.atalho.Atalho;
import com.corretorzap.atalho.AtalhoGlobal;
import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.CorretorService;
import com.corretorzap.correcao.HistoricoService;
import com.corretorzap.correcao.ResultadoCorrecao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class CorretorController {

	public record PedidoCorrecao(@NotBlank @Size(max = 20_000) String texto) {
	}

	public record PedidoPalavra(
			@NotBlank @Size(max = 100) @Pattern(regexp = "[^\\s,]+", message = "uma palavra só, sem espaços ou vírgulas") String palavra) {
	}

	private final CorretorService corretor;
	private final ConfiguracaoService configuracao;
	private final HistoricoService historico;
	private final ObjectProvider<AtalhoGlobal> atalhoGlobal;
	private final boolean demonstracao;

	public CorretorController(CorretorService corretor, ConfiguracaoService configuracao, HistoricoService historico,
			ObjectProvider<AtalhoGlobal> atalhoGlobal, @Value("${corretor.demo:false}") boolean demonstracao) {
		this.corretor = corretor;
		this.configuracao = configuracao;
		this.historico = historico;
		this.atalhoGlobal = atalhoGlobal;
		this.demonstracao = demonstracao;
	}

	@PostMapping("/corrigir")
	public ResultadoCorrecao corrigir(@Valid @RequestBody PedidoCorrecao pedido) {
		return corretor.corrigir(pedido.texto());
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		AtalhoGlobal atalho = atalhoGlobal.getIfAvailable();
		// no modo demonstração o atalho fica desligado de propósito, mas a tela mostra como no uso normal
		return Map.of("atalhoRegistrado", demonstracao || (atalho != null && atalho.registrado()));
	}

	@GetMapping("/config")
	public Configuracao config() {
		return configuracao.atual();
	}

	@PutMapping("/config")
	public Configuracao salvarConfig(@Valid @RequestBody Configuracao nova) {
		Atalho.parse(nova.atalho());
		return configuracao.salvar(nova);
	}

	@PostMapping("/dicionario")
	public Configuracao adicionarPalavra(@Valid @RequestBody PedidoPalavra pedido) {
		return configuracao.adicionarPalavra(pedido.palavra());
	}

	@DeleteMapping("/dicionario/{palavra}")
	public Configuracao removerPalavra(@PathVariable String palavra) {
		return configuracao.removerPalavra(palavra);
	}

	@GetMapping("/historico")
	public List<HistoricoService.Registro> historico() {
		return historico.listar();
	}

	@DeleteMapping("/historico")
	public void limparHistorico() {
		historico.limpar();
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail argumentoInvalido(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

}
