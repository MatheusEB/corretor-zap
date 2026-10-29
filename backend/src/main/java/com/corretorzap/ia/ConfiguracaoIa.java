package com.corretorzap.ia;

import java.net.URI;

/**
 * Qual IA revisa as mensagens do atalho, salva em ~/.corretor-zap/ia.json (separada do config.json para a
 * chave nunca voltar para o painel).
 * <p>
 * Qualquer serviço compatível com a API de chat da OpenAI serve: Gemini, Ollama (local), OpenRouter, Groq…
 *
 * @param ativo    false = só o corretor local (LanguageTool), que é o padrão
 * @param provedor só para o painel lembrar o que foi escolhido: "gemini", "ollama" ou "outro"
 * @param url      endereço base da API, terminado em "/" (ex.: https://generativelanguage.googleapis.com/v1beta/openai/)
 * @param modelo   nome do modelo no provedor
 * @param chave    chave da API; vazia para provedores locais
 */
public record ConfiguracaoIa(boolean ativo, String provedor, String url, String modelo, String chave) {

	public static final String URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/";
	// o 2.5 já não aceita contas novas; o 3.1 lite responde em ~1 s com o raciocínio no mínimo
	public static final String MODELO_GEMINI = "gemini-3.1-flash-lite";

	public ConfiguracaoIa {
		provedor = provedor == null || provedor.isBlank() ? "gemini" : provedor.trim();
		url = url == null ? "" : url.trim();
		if (!url.isEmpty() && !url.endsWith("/")) {
			url = url + "/";
		}
		modelo = modelo == null ? "" : modelo.trim();
		chave = chave == null ? "" : chave.trim();
	}

	public static ConfiguracaoIa padrao() {
		return new ConfiguracaoIa(false, "gemini", URL_GEMINI, MODELO_GEMINI, "");
	}

	public boolean pronta() {
		return ativo && !url.isEmpty() && !modelo.isEmpty();
	}

	/** O que o painel pode ver: tudo menos a chave. */
	public record Publica(boolean ativo, String provedor, String url, String modelo, boolean chaveDefinida) {
	}

	public Publica publica() {
		return new Publica(ativo, provedor, url, modelo, !chave.isEmpty());
	}

	/**
	 * A chave só pode ir por HTTPS; HTTP puro só para um servidor nesta máquina (Ollama, LM Studio).
	 *
	 * @throws IllegalArgumentException se o endereço não serve
	 */
	static void validarUrl(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Endereço da IA inválido");
		}
		String host = uri.getHost();
		boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
		boolean valido = host != null && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local));
		if (!valido) {
			throw new IllegalArgumentException("Use um endereço https:// (ou http://localhost para uma IA no próprio computador)");
		}
	}

}
