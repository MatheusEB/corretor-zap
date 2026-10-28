package com.corretorzap.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FiltroLocalTest {

	private final FiltroLocal filtro = new FiltroLocal(List.of("http://localhost:8765"));

	private MockHttpServletResponse executar(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filtro.doFilter(request, response, new MockFilterChain());
		return response;
	}

	private static MockHttpServletRequest pedido(String metodo, String host) {
		MockHttpServletRequest request = new MockHttpServletRequest(metodo, "/api/historico");
		request.setServerName(host);
		return request;
	}

	@Test
	void aceitaHostsLocais() throws Exception {
		for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
			assertThat(executar(pedido("GET", host)).getStatus()).as(host).isEqualTo(200);
		}
	}

	@Test
	void bloqueiaDnsRebinding() throws Exception {
		MockHttpServletResponse response = executar(pedido("GET", "atacante.com"));

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentAsString()).contains("Host não permitido");
	}

	@Test
	void bloqueiaEscritaVindaDeOutroSite() throws Exception {
		MockHttpServletRequest request = pedido("PUT", "localhost");
		request.addHeader("Origin", "https://atacante.com");

		assertThat(executar(request).getStatus()).isEqualTo(403);
	}

	@Test
	void aceitaEscritaDoProprioPainelOuSemOrigin() throws Exception {
		MockHttpServletRequest doPainel = pedido("PUT", "localhost");
		doPainel.addHeader("Origin", "http://localhost:8765");

		assertThat(executar(doPainel).getStatus()).isEqualTo(200);
		assertThat(executar(pedido("DELETE", "localhost")).getStatus()).isEqualTo(200);
	}

	@Test
	void colocaCabecalhosDeSeguranca() throws Exception {
		MockHttpServletResponse response = executar(pedido("GET", "localhost"));

		assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
		assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
		assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
	}

}
