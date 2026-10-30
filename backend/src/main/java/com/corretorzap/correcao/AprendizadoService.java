package com.corretorzap.correcao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.corretorzap.config.Arquivos;
import com.corretorzap.config.ConfiguracaoService;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Aprende com a prévia: uma palavra que a pessoa desfaz várias vezes (gíria, nome, apelido) entra sozinha no
 * dicionário pessoal e para de ser corrigida. As contagens ficam em ~/.corretor-zap/aprendizado.json.
 */
@Service
public class AprendizadoService {

	private static final Logger log = LoggerFactory.getLogger(AprendizadoService.class);
	/** Quantas vezes a mesma palavra precisa ser desfeita para entrar no dicionário. */
	static final int RECUSAS_PARA_APRENDER = 3;
	private static final int MAXIMO_PALAVRAS_CONTADAS = 2000;
	private static final Pattern UMA_PALAVRA = Pattern.compile("[\\p{L}\\p{M}][\\p{L}\\p{M}'-]{1,39}");

	private final Path arquivo;
	private final ObjectMapper mapper;
	private final ConfiguracaoService configuracao;
	private final Map<String, Integer> recusas;

	public AprendizadoService(@Value("${corretor.config-dir}") Path pasta, ObjectMapper mapper,
			ConfiguracaoService configuracao) {
		this.arquivo = pasta.resolve("aprendizado.json");
		this.mapper = mapper;
		this.configuracao = configuracao;
		this.recusas = carregar();
	}

	/**
	 * Conta as correções que a pessoa desfez na prévia antes de aplicar.
	 *
	 * @return as palavras que entraram no dicionário agora
	 */
	public synchronized List<String> registrarRecusas(Collection<Correcao> recusadas) {
		List<String> aprendidas = recusadas.stream()
				.filter(Correcao::certeza) // as duvidosas já chegam desmarcadas: deixar assim não é recusar
				.map(c -> c.trecho().strip())
				.filter(t -> UMA_PALAVRA.matcher(t).matches())
				.map(t -> t.toLowerCase(Locale.ROOT))
				.distinct()
				.filter(t -> !configuracao.atual().estaNoDicionario(t))
				.flatMap(t -> contar(t).stream())
				.toList();
		salvar();
		return aprendidas;
	}

	private Optional<String> contar(String palavra) {
		int vezes = recusas.merge(palavra, 1, Integer::sum);
		if (vezes < RECUSAS_PARA_APRENDER) {
			if (recusas.size() > MAXIMO_PALAVRAS_CONTADAS) {
				// um contador por palavra já basta; joga fora os que só apareceram uma vez
				recusas.entrySet().removeIf(e -> e.getValue() == 1 && !e.getKey().equals(palavra));
			}
			return Optional.empty();
		}
		recusas.remove(palavra);
		try {
			configuracao.adicionarPalavra(palavra);
		} catch (IllegalArgumentException cheio) {
			return Optional.empty();
		}
		log.info("Palavra aprendida (desfeita {} vezes na prévia)", RECUSAS_PARA_APRENDER);
		return Optional.of(palavra);
	}

	synchronized int recusasDe(String palavra) {
		return recusas.getOrDefault(palavra.toLowerCase(Locale.ROOT), 0);
	}

	private void salvar() {
		try {
			Arquivos.gravar(arquivo, mapper.writeValueAsString(recusas));
		} catch (IOException e) {
			log.warn("Não foi possível salvar {}: {}", arquivo, e.getMessage());
		}
	}

	private Map<String, Integer> carregar() {
		if (!Files.exists(arquivo)) {
			return new HashMap<>();
		}
		try {
			return new HashMap<>(mapper.readValue(Files.readString(arquivo), new TypeReference<Map<String, Integer>>() {
			}));
		} catch (IOException | JacksonException e) {
			log.warn("Aprendizado inválido em {}, começando do zero: {}", arquivo, e.getMessage());
			return new HashMap<>();
		}
	}

}
