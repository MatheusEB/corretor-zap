package com.corretorzap.demo;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.CorretorService;
import com.corretorzap.correcao.HistoricoService;
import com.corretorzap.correcao.ResultadoCorrecao;

/**
 * Preenche histórico e dicionário com mensagens inventadas, para prints e apresentações
 * ({@code --corretor.demo=true}). Use junto com uma pasta de configuração temporária.
 */
@Component
@ConditionalOnProperty(name = "corretor.demo", havingValue = "true")
public class DadosDeDemonstracao {

	private static final List<String> MENSAGENS = List.of(
			"comprei pao leite e ovos pra amanha",
			"se der certo eu te aviso",
			"oi pessoal a reuniao foi remarcada pra sexta",
			"eu fui na academia mas tava fechada",
			"voce viu o jogo ontem");
	private static final List<String> PALAVRAS = List.of("blz", "vlw", "tmj", "Zezinho", "pdc");

	private final CorretorService corretor;
	private final HistoricoService historico;
	private final ConfiguracaoService configuracao;

	public DadosDeDemonstracao(CorretorService corretor, HistoricoService historico, ConfiguracaoService configuracao) {
		this.corretor = corretor;
		this.historico = historico;
		this.configuracao = configuracao;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void preencher() {
		PALAVRAS.forEach(configuracao::adicionarPalavra);
		for (String mensagem : MENSAGENS) {
			ResultadoCorrecao resultado = corretor.corrigir(mensagem);
			if (resultado.mudou()) {
				historico.registrar(resultado);
			}
		}
	}

}
