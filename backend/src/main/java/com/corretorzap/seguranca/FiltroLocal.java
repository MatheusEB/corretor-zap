package com.corretorzap.seguranca;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * O painel só existe para quem está nesta máquina. Além de escutar só em 127.0.0.1, recusa:
 * <ul>
 * <li>Host que não seja local: bloqueia DNS rebinding (um site "vira" 127.0.0.1 e lê o histórico)</li>
 * <li>Origin de outro site em requisições que alteram algo: bloqueia CSRF</li>
 * </ul>
 * e coloca cabeçalhos que impedem o painel de ser embutido ou de carregar scripts de fora.
 */
@Component
public class FiltroLocal extends OncePerRequestFilter {

	private static final Set<String> HOSTS_LOCAIS = Set.of("localhost", "127.0.0.1", "[::1]");
	private static final Set<String> METODOS_SEGUROS = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name());

	// motion anima via atributo style, por isso style-src precisa de 'unsafe-inline'
	private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
			+ "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
			+ "base-uri 'none'; form-action 'self'";

	private final List<String> origensPermitidas;

	public FiltroLocal(@Value("${corretor.origens-permitidas}") List<String> origensPermitidas) {
		this.origensPermitidas = origensPermitidas;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!HOSTS_LOCAIS.contains(request.getServerName())) {
			recusar(response, "Host não permitido");
			return;
		}
		String origem = request.getHeader("Origin");
		if (!METODOS_SEGUROS.contains(request.getMethod()) && origem != null && !origensPermitidas.contains(origem)) {
			recusar(response, "Origem não permitida");
			return;
		}

		response.setHeader("Content-Security-Policy", CSP);
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
		response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
		if (request.getRequestURI().startsWith("/api/")) {
			// o histórico tem mensagens pessoais; nada de cache em disco
			response.setHeader("Cache-Control", "no-store");
		}
		chain.doFilter(request, response);
	}

	private static void recusar(HttpServletResponse response, String motivo) throws IOException {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"" + motivo + "\"}");
	}

}
