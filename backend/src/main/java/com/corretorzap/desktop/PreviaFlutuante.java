package com.corretorzap.desktop;

import java.awt.MouseInfo;
import java.awt.Point;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corretorzap.correcao.ResultadoCorrecao;
import com.corretorzap.ia.ClienteIa;

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
import tools.jackson.core.JacksonException;
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
	private CompletableFuture<Optional<Decisao>> decisao;
	private Function<String, CompletableFuture<ResultadoCorrecao>> tons;
	private long mostradaEm;

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
				Object json = web.getEngine().executeScript("window.decisaoDaPrevia ? window.decisaoDaPrevia() : null");
				if (json instanceof String decisaoJson) {
					ponte.aplicar(decisaoJson);
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
	 * @param ia   estado da revisão da IA: "desligada", "revisando", "pronta" ou "falhou"
	 * @param tons pede uma reescrita no tom ("FORMAL", "CURTO", "GENTIL"); null esconde os botões
	 * @return o que ela escolheu, ou vazio se cancelou. O próprio futuro identifica esta prévia em
	 *         {@link #atualizar}.
	 */
	CompletableFuture<Optional<Decisao>> mostrar(ResultadoCorrecao resultado, boolean escuro, String ia,
			Function<String, CompletableFuture<ResultadoCorrecao>> tons) {
		CompletableFuture<Optional<Decisao>> futuro = new CompletableFuture<>();
		long pedidaEm = System.nanoTime();
		Platform.runLater(() -> {
			if (!pronta) {
				log.warn("Prévia ainda carregando; corrigindo direto");
				futuro.complete(resultado.mudou()
						? Optional.of(new Decisao(resultado.corrigido(), resultado.correcoes(), null))
						: Optional.empty());
				return;
			}
			decidir(Optional.empty());
			decisao = futuro;
			this.tons = tons;
			mostradaEm = pedidaEm;
			posicionada = false;
			String dados = mapper.writeValueAsString(
					Map.of("resultado", resultado, "escuro", escuro, "ia", ia, "tons", tons != null));
			web.getEngine().executeScript("window.mostrarPrevia(" + dados + ")");
		});
		return futuro;
	}

	/**
	 * Troca o conteúdo da prévia aberta (a IA respondeu). Não faz nada se a pessoa já decidiu ou se outra prévia
	 * tomou o lugar desta.
	 */
	void atualizar(CompletableFuture<Optional<Decisao>> previa, ResultadoCorrecao resultado, String ia) {
		Platform.runLater(() -> {
			if (decisao != previa) {
				return;
			}
			String dados = mapper.writeValueAsString(Map.of("resultado", resultado, "ia", ia));
			web.getEngine().executeScript("window.atualizarPrevia(" + dados + ")");
		});
	}

	void cancelar() {
		Platform.runLater(() -> decidir(Optional.empty()));
	}

	private void decidir(Optional<Decisao> escolha) {
		if (decisao == null) {
			return;
		}
		CompletableFuture<Optional<Decisao>> atual = decisao;
		decisao = null;
		tons = null;
		palco.hide();
		log.info("Prévia: {}", escolha.isPresent() ? "aplicada" : "cancelada");
		atual.complete(escolha);
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
		log.info("Prévia aberta em ({}, {}) em {} ms, com foco: {}", (int) palco.getX(), (int) palco.getY(),
				(System.nanoTime() - mostradaEm) / 1_000_000, comFoco);
	}

	/** Chamada pelo JavaScript da prévia (sempre na thread do JavaFX). */
	public final class Ponte {

		public void tamanho(double largura, double altura) {
			if (decisao != null) {
				abrirPerto(largura, altura);
			}
		}

		/** @param json {@code {texto, aceitas, recusadas}} */
		public void aplicar(String json) {
			try {
				decidir(Optional.of(mapper.readValue(json, Decisao.class)));
			} catch (JacksonException e) {
				log.error("Decisão da prévia em formato inesperado", e);
				decidir(Optional.empty());
			}
		}

		public void cancelar() {
			decidir(Optional.empty());
		}

		/** Botões de tom: a resposta volta por {@code window.mostrarTom}. */
		public void tom(String nome) {
			Function<String, CompletableFuture<ResultadoCorrecao>> pedir = tons;
			CompletableFuture<Optional<Decisao>> previa = decisao;
			if (pedir == null || previa == null) {
				return;
			}
			pedir.apply(nome).whenComplete((resultado, erro) -> Platform.runLater(() -> {
				if (decisao != previa) {
					return;
				}
				Map<String, Object> dados = erro == null
						? Map.of("tom", nome, "resultado", resultado)
						: Map.of("tom", nome, "erro", mensagemDe(erro));
				web.getEngine().executeScript("window.mostrarTom(" + mapper.writeValueAsString(dados) + ")");
			}));
		}

	}

	private static String mensagemDe(Throwable erro) {
		Throwable causa = erro instanceof CompletionException && erro.getCause() != null ? erro.getCause() : erro;
		return causa instanceof ClienteIa.FalhaIa ? causa.getMessage() : "Não foi possível ajustar o tom agora";
	}

}
