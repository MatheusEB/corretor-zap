package com.corretorzap.desktop;

import java.awt.MouseInfo;
import java.awt.Point;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corretorzap.correcao.ResultadoCorrecao;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;
import tools.jackson.databind.ObjectMapper;

/**
 * Janela transparente, sem bordas, que mostra a prévia da correção perto do mouse (o cartão é a página
 * React em {@code ?previa}). Fica carregada e escondida para abrir na hora em que o atalho é apertado.
 */
public final class PreviaFlutuante {

	static final String TITULO = "Corretor Zap · Prévia";
	private static final Logger log = LoggerFactory.getLogger(PreviaFlutuante.class);
	private static final int AFASTAMENTO_DO_MOUSE = 14;

	private final ObjectMapper mapper;
	private final Stage palco;
	private final WebView web;
	// precisa de referência forte: o WebView só guarda uma fraca e o objeto sumiria no GC
	private final Ponte ponte = new Ponte();
	private boolean pronta;
	private boolean posicionada;
	private CompletableFuture<Optional<String>> decisao;

	/** Deve ser criada na thread do JavaFX. */
	PreviaFlutuante(String url, ObjectMapper mapper) {
		this.mapper = mapper;

		// dono invisível: assim a prévia não ganha um ícone próprio na barra de tarefas
		Stage dono = new Stage(StageStyle.UTILITY);
		dono.setOpacity(0);
		dono.setWidth(1);
		dono.setHeight(1);
		dono.setX(-32000);
		dono.show();

		web = new WebView();
		web.setPageFill(Color.TRANSPARENT);
		web.setContextMenuEnabled(false);
		web.getEngine().getLoadWorker().stateProperty().addListener((obs, antes, estado) -> {
			if (estado == Worker.State.SUCCEEDED) {
				((JSObject) web.getEngine().executeScript("window")).setMember("ponte", ponte);
				pronta = true;
			}
		});
		web.getEngine().load(url);

		palco = new Stage(StageStyle.TRANSPARENT);
		palco.initOwner(dono);
		palco.setTitle(TITULO);
		palco.setAlwaysOnTop(true);
		Scene cena = new Scene(web, 472, 300, Color.TRANSPARENT);
		// Enter/Esc tratados aqui também: logo que a janela aparece, o WebView pode ainda não ter o foco interno
		cena.addEventFilter(KeyEvent.KEY_PRESSED, evento -> {
			if (evento.getCode() == KeyCode.ENTER && !evento.isShiftDown()) {
				evento.consume();
				Object texto = web.getEngine().executeScript("window.textoDaPrevia ? window.textoDaPrevia() : null");
				if (texto instanceof String textoFinal) {
					decidir(Optional.of(textoFinal));
				}
			} else if (evento.getCode() == KeyCode.ESCAPE) {
				evento.consume();
				decidir(Optional.empty());
			}
		});
		palco.setScene(cena);
		// clicar fora da prévia é o mesmo que cancelar
		palco.focusedProperty().addListener((obs, tinhaFoco, temFoco) -> {
			if (tinhaFoco && !temFoco) {
				decidir(Optional.empty());
			}
		});
	}

	/**
	 * Mostra a prévia e espera a pessoa decidir.
	 *
	 * @return o texto a colar (só com as correções que ela manteve) ou vazio se cancelou
	 */
	CompletableFuture<Optional<String>> mostrar(ResultadoCorrecao resultado, boolean escuro) {
		CompletableFuture<Optional<String>> futuro = new CompletableFuture<>();
		Platform.runLater(() -> {
			if (!pronta) {
				log.warn("Prévia ainda carregando; corrigindo direto");
				futuro.complete(Optional.of(resultado.corrigido()));
				return;
			}
			decidir(Optional.empty());
			decisao = futuro;
			posicionada = false;
			String dados = mapper.writeValueAsString(Map.of("resultado", resultado, "escuro", escuro));
			web.getEngine().executeScript("window.mostrarPrevia(" + dados + ")");
		});
		return futuro;
	}

	void cancelar() {
		Platform.runLater(() -> decidir(Optional.empty()));
	}

	private void decidir(Optional<String> texto) {
		if (decisao == null) {
			return;
		}
		CompletableFuture<Optional<String>> atual = decisao;
		decisao = null;
		palco.hide();
		log.info("Prévia: {}", texto.isPresent() ? "aplicada" : "cancelada");
		atual.complete(texto);
	}

	private void abrirPerto(double largura, double altura) {
		palco.setWidth(largura);
		palco.setHeight(altura);
		if (posicionada) {
			return;
		}
		Point mouse = MouseInfo.getPointerInfo().getLocation();
		Rectangle2D area = Screen.getScreensForRectangle(mouse.x, mouse.y, 1, 1).stream()
				.findFirst().orElse(Screen.getPrimary()).getVisualBounds();
		double x = mouse.x + AFASTAMENTO_DO_MOUSE;
		double y = mouse.y + AFASTAMENTO_DO_MOUSE;
		if (x + largura > area.getMaxX()) {
			x = area.getMaxX() - largura;
		}
		if (y + altura > area.getMaxY()) {
			y = mouse.y - altura - AFASTAMENTO_DO_MOUSE;
		}
		palco.setX(Math.max(area.getMinX(), x));
		palco.setY(Math.max(area.getMinY(), y));
		posicionada = true;
		palco.show();
		boolean comFoco = Windows.trazerParaFrente(TITULO);
		web.requestFocus();
		Platform.runLater(web::requestFocus);
		log.info("Prévia aberta em ({}, {}), com foco: {}", (int) palco.getX(), (int) palco.getY(), comFoco);
	}

	/** Chamada pelo JavaScript da prévia (sempre na thread do JavaFX). */
	public final class Ponte {

		public void tamanho(double largura, double altura) {
			if (decisao != null) {
				abrirPerto(largura, altura);
			}
		}

		public void aplicar(String texto) {
			decidir(Optional.of(texto));
		}

		public void cancelar() {
			decidir(Optional.empty());
		}

	}

}
