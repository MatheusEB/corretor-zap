package com.corretorzap.ia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.corretorzap.config.Arquivos;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConfiguracaoIaService {

	private static final Logger log = LoggerFactory.getLogger(ConfiguracaoIaService.class);

	private final Path arquivo;
	private final ObjectMapper mapper;
	private volatile ConfiguracaoIa atual;

	public ConfiguracaoIaService(@Value("${corretor.config-dir}") Path pasta, ObjectMapper mapper) {
		this.arquivo = pasta.resolve("ia.json");
		this.mapper = mapper;
		this.atual = carregar();
	}

	public ConfiguracaoIa atual() {
		return atual;
	}

	/** @param chave null mantém a chave salva; qualquer outro valor (inclusive vazio) troca */
	public synchronized ConfiguracaoIa salvar(boolean ativo, String provedor, String url, String modelo, String chave) {
		ConfiguracaoIa nova = new ConfiguracaoIa(ativo, provedor, url, modelo, chave == null ? atual.chave() : chave);
		if (!nova.url().isEmpty()) {
			ConfiguracaoIa.validarUrl(nova.url());
		}
		if (nova.ativo() && !nova.pronta()) {
			throw new IllegalArgumentException("Preencha o endereço e o modelo antes de ligar a IA");
		}
		try {
			Arquivos.gravar(arquivo, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nova));
		} catch (IOException e) {
			throw new UncheckedIOException("Não foi possível salvar " + arquivo, e);
		}
		atual = nova;
		return nova;
	}

	private ConfiguracaoIa carregar() {
		if (!Files.exists(arquivo)) {
			return ConfiguracaoIa.padrao();
		}
		try {
			return mapper.readValue(Files.readString(arquivo), ConfiguracaoIa.class);
		} catch (IOException | JacksonException e) {
			log.warn("Config da IA inválida em {}, usando o padrão: {}", arquivo, e.getMessage());
			return ConfiguracaoIa.padrao();
		}
	}

}
