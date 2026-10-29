package com.corretorzap.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConfiguracaoService {

	private static final Logger log = LoggerFactory.getLogger(ConfiguracaoService.class);

	private final Path arquivo;
	private final ObjectMapper mapper;
	private volatile Configuracao atual;

	public ConfiguracaoService(@Value("${corretor.config-dir}") Path pasta, ObjectMapper mapper) {
		this.arquivo = pasta.resolve("config.json");
		this.mapper = mapper;
		this.atual = carregar();
	}

	public Configuracao atual() {
		return atual;
	}

	public synchronized Configuracao salvar(Configuracao nova) {
		try {
			Arquivos.gravar(arquivo, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(nova));
		} catch (IOException e) {
			throw new UncheckedIOException("Não foi possível salvar " + arquivo, e);
		}
		atual = nova;
		return nova;
	}

	public synchronized Configuracao adicionarPalavra(String palavra) {
		TreeSet<String> dicionario = new TreeSet<>(atual.dicionario());
		if (dicionario.size() >= Configuracao.MAXIMO_PALAVRAS) {
			throw new IllegalArgumentException("O dicionário já tem " + Configuracao.MAXIMO_PALAVRAS + " palavras");
		}
		dicionario.add(palavra.trim().toLowerCase());
		return salvar(atual.comDicionario(dicionario));
	}

	public synchronized Configuracao removerPalavra(String palavra) {
		TreeSet<String> dicionario = new TreeSet<>(atual.dicionario());
		dicionario.remove(palavra.trim().toLowerCase());
		return salvar(atual.comDicionario(dicionario));
	}

	private Configuracao carregar() {
		if (!Files.exists(arquivo)) {
			return Configuracao.padrao();
		}
		try {
			return mapper.readValue(Files.readString(arquivo), Configuracao.class);
		} catch (IOException | JacksonException e) {
			log.warn("Config inválida em {}, usando o padrão: {}", arquivo, e.getMessage());
			return Configuracao.padrao();
		}
	}

}
