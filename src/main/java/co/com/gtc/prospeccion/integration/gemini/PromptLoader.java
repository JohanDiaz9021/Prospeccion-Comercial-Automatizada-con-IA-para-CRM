package co.com.gtc.prospeccion.integration.gemini;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Carga las plantillas de prompts desde classpath:prompts/*.txt y las guarda en memoria.
 * <p>
 * Formato del archivo: las primeras líneas que empiezan con '#' son la cabecera y no se envían a Gemini. Una de ellas
 * debe ser {@code # version: <valor>}; ese valor se guarda con cada resultado (PROMPT_VERSION) para saber con qué
 * prompt se calificó o redactó. Cada cambio del texto debe subir la versión.
 */
@Component
public class PromptLoader {

    public static final String INTERPRET_SEARCH = "prompts/interpret-search.txt";
    public static final String QUALIFY_CONTACT = "prompts/qualify-contact.txt";
    public static final String DRAFT_EMAIL = "prompts/draft-email.txt";

    private static final Pattern VERSION = Pattern.compile("^#\\s*version:\\s*(\\S{1,30})\\s*$");

    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    /** Prompt listo para usar: su versión y el texto sin la cabecera. */
    public record Prompt(String path, String version, String text) {
    }

    public Prompt load(String path) {
        return cache.computeIfAbsent(path, PromptLoader::read);
    }

    private static Prompt read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se encontró el prompt " + path, e);
        }
        content = content.replace("\r\n", "\n");
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }

        String version = null;
        String[] lines = content.split("\n", -1);
        int body = 0;
        while (body < lines.length && lines[body].startsWith("#")) {
            Matcher matcher = VERSION.matcher(lines[body].strip());
            if (matcher.matches()) {
                version = matcher.group(1);
            }
            body++;
        }
        if (version == null) {
            throw new IllegalStateException("El prompt " + path + " no tiene la línea '# version: ...' en la cabecera");
        }
        String text = String.join("\n", Arrays.copyOfRange(lines, body, lines.length)).strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("El prompt " + path + " está vacío");
        }
        return new Prompt(path, version, text);
    }
}
