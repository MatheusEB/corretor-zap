package com.corretorzap.atalho;

import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.Transferable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.corretorzap.config.Configuracao;
import com.corretorzap.config.ConfiguracaoService;
import com.corretorzap.correcao.AprendizadoService;
import com.corretorzap.correcao.HistoricoService;
import com.corretorzap.correcao.ResultadoCorrecao;
import com.corretorzap.desktop.AppDesktop;
import com.corretorzap.desktop.Decisao;
import com.corretorzap.desktop.Windows;
import com.corretorzap.ia.ClienteIa.Tom;
import com.corretorzap.ia.RevisorIa;
import com.corretorzap.ia.RevisorIa.Etapas;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Escuta o atalho em qualquer janela. Quando ele é apertado, espera as teclas serem soltas,
 * copia o texto do campo em foco, corrige e cola de volta.
 */
@Component
@ConditionalOnProperty(name = "corretor.atalho.habilitado", havingValue = "true")
public class AtalhoGlobal implements NativeKeyListener, SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(AtalhoGlobal.class);

	private final ConfiguracaoService configuracao;
	private final RevisorIa corretor;
	private final HistoricoService historico;
	private final AprendizadoService aprendizado;
	private final ObjectProvider<AppDesktop> appDesktop;

	// se algum evento de "tecla solta" se perder, dispara mesmo assim depois desse tempo
	private static final long ESPERA_MAXIMA_SOLTAR_MS = 1000;
	private static final long ESPERA_MAXIMA_PREVIA_MIN = 5;
	private static final long VOLTA_DO_FOCO_MS = 160;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().daemon().name("corretor-atalho").factory());
	private final Set<Integer> teclasApertadas = ConcurrentHashMap.newKeySet();
	private volatile boolean pendente;
	private volatile boolean executando;
	private volatile boolean registrado;
	private volatile String atalhoTexto;
	private volatile Atalho atalho;
	private AreaDeTrabalho areaDeTrabalho;

	public AtalhoGlobal(ConfiguracaoService configuracao, RevisorIa corretor, HistoricoService historico,
			AprendizadoService aprendizado, ObjectProvider<AppDesktop> appDesktop) {
		this.configuracao = configuracao;
		this.corretor = corretor;
		this.historico = historico;
		this.aprendizado = aprendizado;
		this.appDesktop = appDesktop;
	}

	public boolean registrado() {
		return registrado;
	}

	@Override
	public void start() {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("Ambiente sem interface gráfica: atalho global desativado");
			return;
		}
		// o JNativeHook loga cada tecla em INFO; silencia
		java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);
		try {
			areaDeTrabalho = new AreaDeTrabalho();
			GlobalScreen.registerNativeHook();
			GlobalScreen.addNativeKeyListener(this);
			registrado = true;
			log.info("Atalho global ativo: {}", configuracao.atual().atalho());
		} catch (NativeHookException | java.awt.AWTException | UnsatisfiedLinkError e) {
			// sem atalho o painel continua útil (testar texto, dicionário); o status mostra "indisponível"
			log.error("Não foi possível registrar o atalho global", e);
		}
	}

	@Override
	public void stop() {
		if (registrado) {
			GlobalScreen.removeNativeKeyListener(this);
			try {
				GlobalScreen.unregisterNativeHook();
			} catch (NativeHookException e) {
				log.warn("Falha ao remover o hook de teclado", e);
			}
			registrado = false;
		}
		executor.shutdownNow();
	}

	@Override
	public boolean isRunning() {
		return registrado;
	}

	@Override
	public void nativeKeyPressed(NativeKeyEvent evento) {
		if (executando) {
			return;
		}
		teclasApertadas.add(evento.getKeyCode());
		Configuracao cfg = configuracao.atual();
		if (cfg.ativo() && !pendente && atalhoAtual(cfg).corresponde(evento)) {
			pendente = true;
			// enquanto a pessoa solta as teclas e o texto é copiado, a conexão com a IA já vai abrindo
			Thread.ofVirtual().start(corretor::preaquecer);
			executor.schedule(this::dispararSePendente, ESPERA_MAXIMA_SOLTAR_MS, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public void nativeKeyReleased(NativeKeyEvent evento) {
		if (executando) {
			return;
		}
		teclasApertadas.remove(evento.getKeyCode());
		// só age quando o usuário soltou tudo; senão o Shift/Ctrl apertado bagunça o Ctrl+A/C/V simulado
		if (pendente && teclasApertadas.isEmpty()) {
			dispararSePendente();
		}
	}

	private synchronized void dispararSePendente() {
		if (!pendente) {
			return;
		}
		pendente = false;
		executando = true;
		executor.submit(this::corrigirCampoEmFoco);
	}

	private void corrigirCampoEmFoco() {
		Configuracao cfg = configuracao.atual();
		Transferable clipboardAnterior = null;
		long inicio = System.nanoTime();
		try {
			areaDeTrabalho.soltarModificadores();
			clipboardAnterior = areaDeTrabalho.salvarClipboard();
			String texto = areaDeTrabalho.copiar(cfg.selecionarTudo());
			long copiado = System.nanoTime();
			if (texto == null || texto.isBlank()) {
				log.info("Atalho: nada copiado em {} ms", ms(inicio, copiado));
				return;
			}
			AppDesktop app = cfg.previa() ? appDesktop.getIfAvailable() : null;
			Optional<Decisao> decisao;
			if (app == null) {
				ResultadoCorrecao resultado = corretor.corrigir(texto);
				log.info("Atalho: copiar {} ms, corrigir {} ms", ms(inicio, copiado), ms(copiado, System.nanoTime()));
				decisao = resultado.mudou()
						? Optional.of(new Decisao(resultado.corrigido(), resultado.correcoes(), null))
						: Optional.empty();
			} else {
				Etapas etapas = corretor.corrigirEmEtapas(texto);
				log.info("Atalho: copiar {} ms, corretor local {} ms", ms(inicio, copiado), ms(copiado, System.nanoTime()));
				if (!etapas.local().mudou() && etapas.ia() == null) {
					return;
				}
				decisao = perguntarNaPrevia(app, texto, etapas);
			}
			if (decisao.isEmpty()) {
				return;
			}
			Decisao escolha = decisao.get();
			List<String> aprendidas = aprendizado.registrarRecusas(escolha.recusadas());
			if (!aprendidas.isEmpty()) {
				log.info("{} palavra(s) nova(s) no dicionário", aprendidas.size());
			}
			if (!escolha.texto().equals(texto)) {
				areaDeTrabalho.colar(escolha.texto());
				historico.registrar(new ResultadoCorrecao(texto, escolha.texto(), escolha.aceitas()));
				log.info("{} correção(ões) aplicada(s)", escolha.aceitas().size());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			log.error("Erro ao corrigir o texto", e);
		} finally {
			areaDeTrabalho.restaurarClipboard(clipboardAnterior);
			teclasApertadas.clear();
			executando = false;
		}
	}

	/**
	 * Mostra a prévia por cima do app e espera a decisão. A prévia pega o foco (senão o Enter iria para o
	 * WhatsApp e enviaria a mensagem); ao final o foco volta para a janela onde o texto estava.
	 */
	private Optional<Decisao> perguntarNaPrevia(AppDesktop app, String texto, Etapas etapas) throws InterruptedException {
		HWND janelaDoTexto = Windows.janelaEmFoco();
		long pedidoIa = System.nanoTime();
		String estadoIa = etapas.ia() == null ? "desligada" : "revisando";
		Function<String, CompletableFuture<ResultadoCorrecao>> tons = corretor.iaLigada()
				? nome -> corretor.reescreverEmSegundoPlano(texto, Tom.valueOf(nome))
				: null;
		// a prévia abre já com a correção local; a da IA chega depois e troca o conteúdo
		CompletableFuture<Optional<Decisao>> aberta = app.previa(etapas.local(), estadoIa, tons);
		if (etapas.ia() != null) {
			etapas.ia().whenComplete((revisado, erro) -> {
				if (erro == null) {
					log.info("IA respondeu em {} ms", ms(pedidoIa, System.nanoTime()));
					app.atualizarPrevia(aberta, revisado, "pronta");
				} else {
					log.warn("IA falhou em {} ms ({}); a prévia fica com o corretor local", ms(pedidoIa, System.nanoTime()),
							erro.getCause() != null ? erro.getCause().getMessage() : erro.getMessage());
					app.atualizarPrevia(aberta, etapas.local(), "falhou");
				}
			});
		}
		Optional<Decisao> decisao;
		try {
			decisao = aberta.get(ESPERA_MAXIMA_PREVIA_MIN, TimeUnit.MINUTES);
		} catch (TimeoutException | ExecutionException e) {
			app.cancelarPrevia();
			decisao = Optional.empty();
		}
		if (decisao.isEmpty()) {
			Windows.focar(janelaDoTexto);
			return decisao;
		}
		Windows.focar(janelaDoTexto);
		// o app precisa de um instante para devolver a seleção antes do Ctrl+V
		Thread.sleep(VOLTA_DO_FOCO_MS);
		// nunca colar numa janela diferente da que tinha o texto (ela pode ter sido fechada nesse meio-tempo)
		if (janelaDoTexto == null || !janelaDoTexto.equals(Windows.janelaEmFoco())) {
			log.warn("A janela de origem não voltou ao foco; a correção não foi colada");
			return Optional.empty();
		}
		return decisao;
	}

	private static long ms(long de, long ate) {
		return (ate - de) / 1_000_000;
	}

	private Atalho atalhoAtual(Configuracao cfg) {
		if (!cfg.atalho().equals(atalhoTexto)) {
			atalho = Atalho.parse(cfg.atalho());
			atalhoTexto = cfg.atalho();
		}
		return atalho;
	}

}
