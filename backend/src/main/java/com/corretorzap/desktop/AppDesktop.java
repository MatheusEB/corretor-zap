package com.corretorzap.desktop;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.corretorzap.atualizacao.AtualizacaoService.VersaoDisponivel;
import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.ResultadoCorrecao;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import tools.jackson.databind.ObjectMapper;

/**
 * A "cara" de app do Corretor Zap: janela nativa (JavaFX + WebView com o painel React) e ícone na bandeja.
 * Fechar a janela só esconde; o corretor continua ouvindo o atalho até "Sair" na bandeja.
 */
@Component
@ConditionalOnProperty(name = "corretor.janela.habilitada", havingValue = "true")
public class AppDesktop {

	static final String TITULO = "Corretor Zap";
	private static final Logger log = LoggerFactory.getLogger(AppDesktop.class);

	private final ApplicationContext contexto;
	private final ConfiguracaoService configuracao;
	private final ObjectMapper mapper;
	private final Path pastaDados;
	private final boolean comecarMinimizado;
	private final AtomicBoolean avisouBandeja = new AtomicBoolean();

	private Stage janela;
	private volatile PreviaFlutuante previa;
	private volatile boolean barraEscura = Windows.temaEscuro();
	private TrayIcon iconeBandeja;
	private MenuItem itemPausar;
	private MenuItem itemAtualizar;

	public AppDesktop(ApplicationContext contexto, ConfiguracaoService configuracao, ObjectMapper mapper,
			@Value("${corretor.config-dir}") Path pastaDados,
			@Value("${corretor.minimizado:false}") boolean comecarMinimizado) {
		this.contexto = contexto;
		this.configuracao = configuracao;
		this.mapper = mapper;
		this.pastaDados = pastaDados;
		this.comecarMinimizado = comecarMinimizado;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void abrir() {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("Sem interface gráfica: rodando só com o atalho");
			return;
		}
		String porta = contexto.getEnvironment().getProperty("local.server.port");
		criarBandeja();
		Platform.setImplicitExit(false);
		Platform.startup(() -> {
			criarJanela("http://localhost:" + porta + "/");
			previa = new PreviaFlutuante("http://localhost:" + porta + "/?previa=1", mapper);
		});
	}

	/**
	 * Mostra a prévia da correção perto do mouse e espera a decisão.
	 *
	 * @param ia   estado da revisão da IA: "desligada", "revisando", "pronta" ou "falhou"
	 * @param tons pede uma reescrita no tom; null esconde os botões de tom
	 * @return o que a pessoa escolheu, ou vazio se ela cancelou
	 */
	public CompletableFuture<Optional<Decisao>> previa(ResultadoCorrecao resultado, String ia,
			Function<String, CompletableFuture<ResultadoCorrecao>> tons) {
		PreviaFlutuante atual = previa;
		if (atual == null) {
			return CompletableFuture.completedFuture(resultado.mudou()
					? Optional.of(new Decisao(resultado.corrigido(), resultado.correcoes(), null))
					: Optional.empty());
		}
		return atual.mostrar(resultado, barraEscura, ia, tons);
	}

	/** A IA respondeu: troca o conteúdo da prévia que ainda está aberta. */
	public void atualizarPrevia(CompletableFuture<Optional<Decisao>> aberta, ResultadoCorrecao resultado, String ia) {
		PreviaFlutuante atual = previa;
		if (atual != null) {
			atual.atualizar(aberta, resultado, ia);
		}
	}

	public void cancelarPrevia() {
		PreviaFlutuante atual = previa;
		if (atual != null) {
			atual.cancelar();
		}
	}

	/** Traz a janela para a frente (usado pela bandeja e quando o app é aberto de novo). */
	public void mostrar() {
		Platform.runLater(() -> {
			if (janela == null) {
				return;
			}
			janela.setIconified(false);
			janela.show();
			janela.toFront();
			janela.requestFocus();
			Windows.barraDeTituloEscura(TITULO, barraEscura);
		});
	}

	/** Versão nova no GitHub: avisa pela bandeja e põe "Atualizar" no menu (o botão de verdade fica no painel). */
	@EventListener
	public void versaoDisponivel(VersaoDisponivel evento) {
		if (iconeBandeja == null) {
			return;
		}
		itemAtualizar.setLabel("Atualizar para a versão " + evento.versao());
		itemAtualizar.setEnabled(true);
		iconeBandeja.displayMessage(TITULO, "Versão " + evento.versao() + " disponível. Abra o app para atualizar.",
				TrayIcon.MessageType.INFO);
	}

	public void barraDeTitulo(boolean escura) {
		this.barraEscura = escura;
		Platform.runLater(() -> Windows.barraDeTituloEscura(TITULO, escura));
	}

	private void criarJanela(String url) {
		WebView web = new WebView();
		web.setContextMenuEnabled(false);
		web.getEngine().setUserDataDirectory(pastaDados.resolve("janela").toFile());
		boolean escuro = Windows.temaEscuro();
		web.getEngine().load(url + "?janela=1&tema=" + (escuro ? "escuro" : "claro"));

		janela = new Stage();
		janela.setTitle(TITULO);
		for (int tamanho : new int[] { 16, 32, 64, 256 }) {
			try (InputStream png = Icone.png(tamanho)) {
				janela.getIcons().add(new Image(png));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		Scene cena = new Scene(web, 1120, 760, escuro ? Color.web("#141311") : Color.web("#F4F1EA"));
		janela.setScene(cena);
		janela.setMinWidth(880);
		janela.setMinHeight(600);
		janela.setOnCloseRequest(evento -> {
			evento.consume();
			janela.hide();
			if (iconeBandeja != null && avisouBandeja.compareAndSet(false, true)) {
				iconeBandeja.displayMessage(TITULO, "Continuo corrigindo por aqui. Abra pelo ícone da bandeja.",
						TrayIcon.MessageType.NONE);
			}
		});
		if (!comecarMinimizado) {
			janela.show();
			Windows.barraDeTituloEscura(TITULO, escuro);
		}
	}

	private void criarBandeja() {
		if (!SystemTray.isSupported()) {
			return;
		}
		PopupMenu menu = new PopupMenu();
		MenuItem abrir = new MenuItem("Abrir o Corretor Zap");
		abrir.addActionListener(e -> mostrar());
		itemPausar = new MenuItem(textoPausar());
		itemPausar.addActionListener(e -> alternarPausa());
		itemAtualizar = new MenuItem("Sem atualizações");
		itemAtualizar.setEnabled(false);
		itemAtualizar.addActionListener(e -> mostrar());
		MenuItem sair = new MenuItem("Sair");
		sair.addActionListener(e -> sair());
		menu.add(abrir);
		menu.add(itemPausar);
		menu.add(itemAtualizar);
		menu.addSeparator();
		menu.add(sair);

		iconeBandeja = new TrayIcon(Icone.awt(32), TITULO, menu);
		iconeBandeja.setImageAutoSize(true);
		iconeBandeja.addActionListener(e -> mostrar());
		// a pausa também pode mudar pelo painel; atualiza o texto antes de o menu abrir
		iconeBandeja.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				itemPausar.setLabel(textoPausar());
			}
		});
		try {
			SystemTray.getSystemTray().add(iconeBandeja);
		} catch (AWTException e) {
			log.warn("Não foi possível criar o ícone na bandeja", e);
			iconeBandeja = null;
		}
	}

	private void alternarPausa() {
		Configuracao atual = configuracao.atual();
		configuracao.salvar(atual.comAtivo(!atual.ativo()));
		itemPausar.setLabel(textoPausar());
	}

	private String textoPausar() {
		return configuracao.atual().ativo() ? "Pausar correção" : "Retomar correção";
	}

	private void sair() {
		if (iconeBandeja != null) {
			SystemTray.getSystemTray().remove(iconeBandeja);
		}
		Platform.exit();
		System.exit(SpringApplication.exit(contexto));
	}

}
