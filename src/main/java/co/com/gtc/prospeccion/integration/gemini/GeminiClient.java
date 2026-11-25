package co.com.gtc.prospeccion.integration.gemini;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.GeminiProvider;
import co.com.gtc.prospeccion.config.PropertiesConfig.IntegrationsProperties;
import co.com.gtc.prospeccion.config.RestClientConfig;
import co.com.gtc.prospeccion.integration.Sleeper;
import co.com.gtc.prospeccion.integration.gemini.dto.DraftContact;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiDraftResult;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiSearchFilters;
import co.com.gtc.prospeccion.integration.gemini.dto.QualificationCandidate;
import co.com.gtc.prospeccion.multitenancy.AwsSsmTenantConfigResolver;

/**
 * Cliente único de Gemini: la única salida del servicio hacia la IA (tarea 2.7).
 * <ul>
 * <li>La llave viaja en el encabezado x-goog-api-key, nunca en la URL (no queda en logs ni proxies).
 * Sale de SSM (integrations.gemini.api-key-ssm-parameter) o, solo en local, de GEMINI_API_KEY.</li>
 * <li>Ante 429, 5xx o fallas de red reintenta con espera exponencial (2 s, 4 s, 8 s... más azar), respetando
 * el tiempo que pida Google (Retry-After o RetryInfo), con un máximo de 60 s por espera.</li>
 * <li>Los errores que reintentar no arregla (llave inválida, petición mal formada) se lanzan de inmediato.</li>
 * <li>Con responseSchema, Gemini responde JSON que cumple el esquema.</li>
 * <li>El modelo va en GEMINI_MODEL y los timeouts en el RestClient (RestClientConfig).</li>
 * </ul>
 * Nunca registra el prompt ni la respuesta: pueden traer datos de contactos.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);
    static final Duration BASE_WAIT = Duration.ofSeconds(2);
    static final Duration MAX_WAIT = Duration.ofSeconds(60);
    static final Set<String> BLOCK_REASONS =
            Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY");
    /** Claves de los criterios de puntaje (docs/negocio/perfil-de-interes.md, sección 7). */
    public static final List<String> CRITERIA = List.of("title", "sector", "companySize", "location", "signals");
    static final Map<String, Object> QUALIFICATION_SCHEMA = qualificationSchema();
    static final Map<String, Object> DRAFT_SCHEMA = draftSchema();
    static final Map<String, Object> SEARCH_SCHEMA = searchSchema();
    /** Un poco de variación para que los correos no salgan idénticos, sin alejarse del mensaje base. */
    static final double DRAFT_TEMPERATURE = 0.3;

    private final RestClient restClient;
    private final GeminiProvider properties;
    private final Supplier<String> apiKey;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;
    private final PromptLoader promptLoader;

    @Autowired
    public GeminiClient(@Qualifier(RestClientConfig.GEMINI) RestClient restClient,
            IntegrationsProperties integrations, AwsSsmTenantConfigResolver ssm, ObjectMapper objectMapper,
            PromptLoader promptLoader) {
        this(restClient, integrations.getGemini(), () -> resolveApiKey(integrations.getGemini(), ssm),
                objectMapper, Sleeper.REAL, promptLoader);
    }

    GeminiClient(RestClient restClient, GeminiProvider properties, Supplier<String> apiKey,
            ObjectMapper objectMapper, Sleeper sleeper, PromptLoader promptLoader) {
        this.restClient = restClient;
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
        this.promptLoader = promptLoader;
    }

    /** Envía la petición a Gemini y devuelve su respuesta. */
    public GeminiResponse generate(GeminiRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("El prompt está vacío");
        }
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new GeminiException.Configuration(
                    "Falta la llave de Gemini (parámetro SSM de integrations.gemini o GEMINI_API_KEY en local)");
        }

        Map<String, Object> body = buildBody(request);
        int maxAttempts = 1 + Math.max(0, properties.getMaxRetries());
        long start = System.nanoTime();
        String reason = "";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration wait;
            try {
                HttpResult result = send(key, body);
                if (result.status() == 200) {
                    return readResponse(result.body(), start, attempt);
                }
                if (!RETRYABLE_STATUS.contains(result.status())) {
                    throw new GeminiException.Rejected("Gemini rechazó la petición (%d): %s"
                            .formatted(result.status(), errorMessage(result.body())), result.status());
                }
                reason = "HTTP " + result.status();
                int current = attempt;
                wait = requestedWait(result).orElseGet(() -> exponentialWait(current));
            } catch (ResourceAccessException e) { // tiempo agotado, DNS, conexión rechazada
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                reason = "falla de red (" + cause.getClass().getSimpleName() + ")";
                wait = exponentialWait(attempt);
            }

            if (attempt == maxAttempts) {
                break;
            }
            if (wait.compareTo(MAX_WAIT) > 0) {
                wait = MAX_WAIT;
            }
            log.warn("Gemini: {}; reintento {} de {} en {} ms", reason, attempt, maxAttempts - 1, wait.toMillis());
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GeminiException.RetriesExhausted("Se interrumpió la espera entre reintentos");
            }
        }
        throw new GeminiException.RetriesExhausted(
                "Gemini no respondió tras %d intentos (último error: %s)".formatted(maxAttempts, reason));
    }

    // ---------- Casos de uso (se implementan en sus tareas, siempre sobre generate) ----------

    /**
     * Tarea 3.2: filtros de Apollo a partir de la búsqueda en lenguaje natural ("gerentes de TI de clínicas en
     * Bogotá"). Del perfil de interés solo viajan {@code sectors} y {@code locations}, como guía de los nombres que
     * usa Apollo. La respuesta todavía no está limpia: la limpia SearchInterpretService.
     *
     * @param naturalQuery         lo que escribió el usuario
     * @param profileOfInterest    JSON del perfil; puede ser null
     * @param allowedSeniorities   niveles de cargo válidos (sy_mastertable 605)
     * @param allowedEmployeeRanges rangos de empleados válidos (sy_mastertable 606)
     */
    public GeminiSearchFilters interpretSearch(String naturalQuery, String profileOfInterest,
            List<String> allowedSeniorities, List<String> allowedEmployeeRanges) {
        if (naturalQuery == null || naturalQuery.isBlank()) {
            throw new IllegalArgumentException("La búsqueda está vacía");
        }
        PromptLoader.Prompt prompt = promptLoader.load(PromptLoader.INTERPRET_SEARCH);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", naturalQuery.strip());
        input.put("allowedSeniorities", allowedSeniorities);
        input.put("allowedEmployeeRanges", allowedEmployeeRanges);
        JsonNode profile = profileNode(profileOfInterest);
        if (profile != null && profile.path("sectors").isObject()) {
            input.put("sectors", profile.path("sectors"));
        }
        if (profile != null && profile.path("locations").isObject()) {
            input.put("locations", profile.path("locations"));
        }
        String data;
        try {
            data = objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo armar la petición de interpretación", e);
        }

        GeminiResponse response = generate(GeminiRequest.of(data)
                .withSystemInstruction(prompt.text())
                .withTemperature(0.0)
                .withResponseSchema(SEARCH_SCHEMA));

        JsonNode json;
        try {
            json = objectMapper.readTree(response.text());
        } catch (JsonProcessingException e) {
            throw new GeminiException.InvalidResponse("Los filtros de Gemini no son JSON válido", e);
        }
        if (json == null || !json.isObject()) {
            throw new GeminiException.InvalidResponse("Gemini no devolvió los filtros");
        }
        log.info("Gemini interpretó una búsqueda en {} ms (tokens: {} de entrada, {} de salida)",
                response.durationMs(), response.inputTokens(), response.outputTokens());
        return new GeminiSearchFilters(
                json.path("understood").asBoolean(true),
                texts(json.path("personTitles")),
                texts(json.path("personSeniorities")),
                texts(json.path("organizationLocations")),
                texts(json.path("organizationIndustries")),
                texts(json.path("organizationNumEmployeesRanges")),
                texts(json.path("keywords")),
                response.model(),
                prompt.version());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> {
                if (v.isTextual()) {
                    values.add(v.asText());
                }
            });
        }
        return values;
    }

    private JsonNode profileNode(String profileOfInterest) {
        if (profileOfInterest == null || profileOfInterest.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(profileOfInterest);
        } catch (JsonProcessingException e) {
            log.warn("El perfil de interés no es JSON válido; se interpreta la búsqueda sin sectores ni ubicaciones");
            return null;
        }
    }

    private static Map<String, Object> searchSchema() {
        Map<String, Object> list = Map.of("type", "ARRAY", "items", Map.of("type", "STRING"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("understood", Map.of("type", "BOOLEAN"));
        properties.put("personTitles", list);
        properties.put("personSeniorities", list);
        properties.put("organizationLocations", list);
        properties.put("organizationIndustries", list);
        properties.put("organizationNumEmployeesRanges", list);
        properties.put("keywords", list);
        List<String> order = List.copyOf(properties.keySet());
        return Map.of(
                "type", "OBJECT",
                "properties", properties,
                "required", order,
                "propertyOrdering", order);
    }

    /**
     * Tarea 3.5: puntos por criterio de varios contactos frente al perfil de interés, en una sola llamada (así la
     * capa gratuita alcanza para más contactos y el perfil se envía una vez por lote).
     * <p>
     * Devuelve una lista en el mismo orden que {@code contacts}; un elemento es null si Gemini no devolvió ese
     * {@code ref}. El puntaje total y la categoría NO se toman de Gemini: los calcula QualificationService.
     *
     * @param profileOfInterest JSON del perfil (la_tenantconfig.PROFILE_OF_INTEREST)
     * @param contacts          contactos con ref único dentro del lote
     */
    public List<GeminiQualificationResult> qualifyContacts(String profileOfInterest,
            List<QualificationCandidate> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return List.of();
        }
        JsonNode profile;
        try {
            profile = objectMapper.readTree(profileOfInterest == null ? "" : profileOfInterest);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("El perfil de interés no es JSON válido", e);
        }
        if (profile == null || !profile.isObject()) {
            throw new IllegalArgumentException("El perfil de interés debe ser un objeto JSON");
        }

        PromptLoader.Prompt prompt = promptLoader.load(PromptLoader.QUALIFY_CONTACT);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("profile", profile);
        input.put("contacts", contacts);
        String data;
        try {
            data = objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo armar la petición de calificación", e);
        }

        GeminiResponse response = generate(GeminiRequest.of(data)
                .withSystemInstruction(prompt.text())
                .withTemperature(0)
                .withResponseSchema(QUALIFICATION_SCHEMA));

        Map<String, GeminiQualificationResult> byRef = new LinkedHashMap<>();
        try {
            JsonNode results = objectMapper.readTree(response.text()).path("results");
            if (!results.isArray()) {
                throw new GeminiException.InvalidResponse("La calificación no trae la lista 'results'");
            }
            for (JsonNode item : results) {
                String ref = item.path("ref").asText(null);
                if (ref != null && !byRef.containsKey(ref)) {
                    byRef.put(ref, readQualification(item, response.model(), prompt.version()));
                }
            }
        } catch (JsonProcessingException e) {
            throw new GeminiException.InvalidResponse("La calificación de Gemini no es JSON válido", e);
        }

        List<GeminiQualificationResult> ordered = contacts.stream()
                .map(contact -> byRef.get(contact.ref()))
                .toList();
        long missing = ordered.stream().filter(Objects::isNull).count();
        log.info("Gemini calificó {} de {} contactos en {} ms (tokens: {} de entrada, {} de salida)",
                contacts.size() - missing, contacts.size(), response.durationMs(), response.inputTokens(),
                response.outputTokens());
        return ordered;
    }

    private static GeminiQualificationResult readQualification(JsonNode item, String model, String promptVersion) {
        Map<String, Integer> criteria = new LinkedHashMap<>();
        item.path("criteria").properties().forEach(entry -> {
            if (entry.getValue().isNumber()) {
                criteria.put(entry.getKey(), (int) Math.round(entry.getValue().asDouble()));
            }
        });
        List<String> missingData = new ArrayList<>();
        item.path("missingData").forEach(value -> missingData.add(value.asText()));
        JsonNode penalty = item.path("penalty");
        return GeminiQualificationResult.builder()
                .criteria(criteria)
                .penalty(penalty.isNumber() ? (int) Math.round(penalty.asDouble()) : null)
                .reason(item.path("reason").asText(null))
                .missingData(missingData)
                .model(model)
                .promptVersion(promptVersion)
                .build();
    }

    private static Map<String, Object> qualificationSchema() {
        Map<String, Object> criteriaProperties = new LinkedHashMap<>();
        CRITERIA.forEach(key -> criteriaProperties.put(key, Map.of("type", "INTEGER", "minimum", 0)));
        Map<String, Object> criteria = Map.of(
                "type", "OBJECT",
                "properties", criteriaProperties,
                "required", CRITERIA,
                "propertyOrdering", CRITERIA);

        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("ref", Map.of("type", "STRING"));
        itemProperties.put("criteria", criteria);
        itemProperties.put("penalty", Map.of("type", "INTEGER", "minimum", 0));
        itemProperties.put("missingData", Map.of("type", "ARRAY",
                "items", Map.of("type", "STRING", "enum", CRITERIA)));
        itemProperties.put("reason", Map.of("type", "STRING"));
        List<String> itemOrder = List.copyOf(itemProperties.keySet());
        Map<String, Object> item = Map.of(
                "type", "OBJECT",
                "properties", itemProperties,
                "required", itemOrder,
                "propertyOrdering", itemOrder);

        return Map.of(
                "type", "OBJECT",
                "properties", Map.of("results", Map.of("type", "ARRAY", "items", item)),
                "required", List.of("results"));
    }

    /**
     * Tarea 4.3: borrador del correo para un contacto a partir del mensaje base del tenant.
     * <p>
     * Del perfil de interés solo viaja {@code company} (propuesta de valor y servicios): es lo que Gemini necesita para
     * elegir qué destacar. El HTML que devuelve todavía no está limpio; lo limpia y revisa DraftService.
     *
     * @param baseMessage       texto de la_tenantconfig.BASE_MESSAGE (docs/negocio/mensaje-base.md)
     * @param profileOfInterest JSON del perfil; puede ser null
     * @param contact           datos con evidencia del contacto
     */
    public GeminiDraftResult draftEmail(String baseMessage, String profileOfInterest, DraftContact contact) {
        if (baseMessage == null || baseMessage.isBlank()) {
            throw new IllegalArgumentException("El mensaje base está vacío");
        }
        PromptLoader.Prompt prompt = promptLoader.load(PromptLoader.DRAFT_EMAIL);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("baseMessage", baseMessage.strip());
        JsonNode company = companyOf(profileOfInterest);
        if (company != null) {
            input.put("company", company);
        }
        input.put("contact", contact == null ? Map.of() : contact);
        String data;
        try {
            data = objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo armar la petición de redacción", e);
        }

        GeminiResponse response = generate(GeminiRequest.of(data)
                .withSystemInstruction(prompt.text())
                .withTemperature(DRAFT_TEMPERATURE)
                .withResponseSchema(DRAFT_SCHEMA));

        JsonNode json;
        try {
            json = objectMapper.readTree(response.text());
        } catch (JsonProcessingException e) {
            throw new GeminiException.InvalidResponse("El borrador de Gemini no es JSON válido", e);
        }
        String subject = json.path("subject").asText("");
        String bodyHtml = json.path("bodyHtml").asText("");
        if (subject.isBlank() || bodyHtml.isBlank()) {
            throw new GeminiException.InvalidResponse("El borrador de Gemini no trae asunto o cuerpo");
        }
        log.info("Gemini redactó un borrador en {} ms (tokens: {} de entrada, {} de salida)",
                response.durationMs(), response.inputTokens(), response.outputTokens());
        return GeminiDraftResult.builder()
                .subject(subject)
                .bodyHtml(bodyHtml)
                .model(response.model())
                .promptVersion(prompt.version())
                .build();
    }

    /** {@code company} del perfil, o null si no hay perfil o no es JSON (la redacción sigue solo con el mensaje base). */
    private JsonNode companyOf(String profileOfInterest) {
        if (profileOfInterest == null || profileOfInterest.isBlank()) {
            return null;
        }
        try {
            JsonNode company = objectMapper.readTree(profileOfInterest).path("company");
            return company.isObject() ? company : null;
        } catch (JsonProcessingException e) {
            log.warn("El perfil de interés no es JSON válido; se redacta sin los servicios de la empresa");
            return null;
        }
    }

    private static Map<String, Object> draftSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject", Map.of("type", "STRING"));
        properties.put("bodyHtml", Map.of("type", "STRING"));
        List<String> order = List.copyOf(properties.keySet());
        return Map.of(
                "type", "OBJECT",
                "properties", properties,
                "required", order,
                "propertyOrdering", order);
    }

    // ---------- HTTP ----------

    private HttpResult send(String key, Map<String, Object> body) {
        return restClient.post()
                .uri("/models/{model}:generateContent", properties.getModel())
                .header("x-goog-api-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> new HttpResult(
                        res.getStatusCode().value(),
                        res.getHeaders().getFirst("Retry-After"),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    static Map<String, Object> buildBody(GeminiRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.prompt())))));
        if (request.systemInstruction() != null && !request.systemInstruction().isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        }
        Map<String, Object> config = new LinkedHashMap<>();
        if (request.temperature() != null) {
            config.put("temperature", request.temperature());
        }
        if (request.maxOutputTokens() != null) {
            config.put("maxOutputTokens", request.maxOutputTokens());
        }
        if (request.responseSchema() != null) {
            config.put("responseMimeType", "application/json");
            config.put("responseSchema", request.responseSchema());
        }
        if (!config.isEmpty()) {
            body.put("generationConfig", config);
        }
        return body;
    }

    private GeminiResponse readResponse(String body, long start, int attempts) {
        ApiResponse data;
        try {
            data = objectMapper.readValue(body, ApiResponse.class);
        } catch (JsonProcessingException e) {
            throw new GeminiException.InvalidResponse("Gemini respondió algo que no es JSON", e);
        }
        if (data == null || data.candidates() == null || data.candidates().isEmpty()) {
            String block = data == null || data.promptFeedback() == null ? null : data.promptFeedback().blockReason();
            if (block != null) {
                throw new GeminiException.Blocked("Gemini bloqueó el prompt (motivo: %s)".formatted(block));
            }
            throw new GeminiException.InvalidResponse("Gemini no devolvió ninguna respuesta");
        }

        Candidate candidate = data.candidates().get(0);
        String finishReason = candidate.finishReason();
        List<Part> parts = candidate.content() == null || candidate.content().parts() == null
                ? List.of()
                : candidate.content().parts();
        // Las partes marcadas como "thought" son el razonamiento interno del modelo, no la respuesta.
        String text = parts.stream()
                .filter(p -> !Boolean.TRUE.equals(p.thought()))
                .map(p -> Objects.requireNonNullElse(p.text(), ""))
                .collect(Collectors.joining());
        if (text.isEmpty()) {
            if (BLOCK_REASONS.contains(finishReason)) {
                throw new GeminiException.Blocked("Gemini bloqueó la respuesta (motivo: %s)".formatted(finishReason));
            }
            throw new GeminiException.InvalidResponse(
                    "Gemini devolvió una respuesta vacía (motivo: %s)".formatted(finishReason));
        }

        Usage usage = data.usageMetadata() == null ? new Usage(null, null, null) : data.usageMetadata();
        Integer outputTokens = usage.candidatesTokenCount() == null && usage.thoughtsTokenCount() == null
                ? null
                : Objects.requireNonNullElse(usage.candidatesTokenCount(), 0)
                        + Objects.requireNonNullElse(usage.thoughtsTokenCount(), 0);
        return new GeminiResponse(
                text,
                data.modelVersion() != null ? data.modelVersion() : properties.getModel(),
                finishReason,
                usage.promptTokenCount(),
                outputTokens,
                Duration.ofNanos(System.nanoTime() - start).toMillis(),
                attempts);
    }

    /** 2 s, 4 s, 8 s... más hasta 1 s al azar para que varias instancias no reintenten a la vez. */
    static Duration exponentialWait(int attempt) {
        long base = BASE_WAIT.toMillis() * (1L << (attempt - 1));
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(0, 1000));
    }

    /** Tiempo de espera que pide Google: encabezado Retry-After o RetryInfo en el cuerpo. */
    private Optional<Duration> requestedWait(HttpResult result) {
        if (result.retryAfter() != null) {
            try {
                return Optional.of(seconds(Double.parseDouble(result.retryAfter().strip())));
            } catch (NumberFormatException ignored) {
                // Retry-After también puede ser una fecha HTTP; en ese caso se usa la espera exponencial.
            }
        }
        try {
            ApiError error = objectMapper.readValue(result.body(), ApiError.class);
            if (error == null || error.error() == null || error.error().details() == null) {
                return Optional.empty();
            }
            for (Map<String, Object> detail : error.error().details()) {
                if (String.valueOf(detail.get("@type")).endsWith("google.rpc.RetryInfo")) {
                    String delay = String.valueOf(detail.get("retryDelay")); // "17s" o "1.5s"
                    return Optional.of(seconds(Double.parseDouble(delay.replaceFirst("s$", ""))));
                }
            }
        } catch (JsonProcessingException | NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Duration seconds(double value) {
        return Duration.ofMillis(Math.round(Math.max(0, value) * 1000));
    }

    private String errorMessage(String body) {
        String message;
        try {
            ApiError error = objectMapper.readValue(body, ApiError.class);
            message = error != null && error.error() != null && error.error().message() != null
                    ? error.error().message()
                    : body;
        } catch (JsonProcessingException ignored) {
            message = body;
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String resolveApiKey(GeminiProvider properties, AwsSsmTenantConfigResolver ssm) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            return properties.getApiKey();
        }
        String parameter = properties.getApiKeySsmParameter();
        if (parameter == null || parameter.isBlank()) {
            return null;
        }
        return ssm.readParameter(parameter);
    }

    private record HttpResult(int status, String retryAfter, String body) {
    }

    // ---------- Formato de la respuesta de Gemini (solo los campos que se usan) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(List<Candidate> candidates, PromptFeedback promptFeedback, Usage usageMetadata,
            String modelVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text, Boolean thought) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(String blockReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(Integer promptTokenCount, Integer candidatesTokenCount, Integer thoughtsTokenCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiError(ErrorDetail error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorDetail(String message, List<Map<String, Object>> details) {
    }
}
