package co.com.gtc.prospeccion.integration.apollo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import co.com.gtc.prospeccion.config.PropertiesConfig.IntegrationsProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;
import co.com.gtc.prospeccion.config.RestClientConfig;
import co.com.gtc.prospeccion.integration.Sleeper;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage.ApolloRateLimit;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.multitenancy.AwsSsmTenantConfigResolver;

/**
 * Cliente de Apollo: People API Search (tarea 3.1, POST /mixed_people/api_search) y People Enrichment
 * (tarea 4.1, POST /people/match).
 * <ul>
 * <li>Buscar no consume créditos y no devuelve correos ni teléfonos (eso es People Enrichment, tarea 4.1).</li>
 * <li>Paginación: hasta 100 por página y 500 páginas, es decir, un tope de 50.000 resultados visibles.</li>
 * <li>Llave en el encabezado x-api-key (SSM, o APOLLO_API_KEY solo en local), nunca en la URL.</li>
 * <li>Límites de tasa por equipo y por endpoint (minuto, hora y día). Ante un 429 con retry-after corto
 * (hasta 60 s) espera y reintenta; si es más largo (se agotó la hora o el día) lanza RateLimited para que quien
 * llama reprograme, en lugar de bloquear el hilo. Ante 5xx o fallas de red reintenta con espera exponencial.</li>
 * </ul>
 * Nunca registra la llave ni datos de personas.
 */
@Component
public class ApolloClient {

    private static final Logger log = LoggerFactory.getLogger(ApolloClient.class);

    static final String SEARCH_PATH = "/mixed_people/api_search";
    static final String MATCH_PATH = "/people/match";
    /** Apollo devuelve este correo de relleno cuando no lo entrega (por ejemplo sin créditos). */
    static final String LOCKED_EMAIL_MARK = "email_not_unlocked";
    public static final int MAX_PER_PAGE = 100;
    public static final int MAX_PAGE = 500;
    public static final int MAX_VISIBLE_RESULTS = 50_000;
    static final Duration MAX_INLINE_WAIT = Duration.ofSeconds(60);
    static final Duration BASE_WAIT = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final Provider properties;
    private final Supplier<String> apiKey;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    @Autowired
    public ApolloClient(@Qualifier(RestClientConfig.APOLLO) RestClient restClient, IntegrationsProperties integrations,
            AwsSsmTenantConfigResolver ssm, ObjectMapper objectMapper) {
        this(restClient, integrations.getApollo(), () -> resolveApiKey(integrations.getApollo(), ssm), objectMapper,
                Sleeper.REAL);
    }

    ApolloClient(RestClient restClient, Provider properties, Supplier<String> apiKey, ObjectMapper objectMapper,
            Sleeper sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    /** Una página de resultados. page desde 1 (máximo 500); perPage de 1 a 100. 0 créditos. */
    public ApolloSearchPage searchPage(ApolloPeopleSearchRequest request, int page, int perPage) {
        if (page < 1 || page > MAX_PAGE) {
            throw new IllegalArgumentException("page debe estar entre 1 y " + MAX_PAGE);
        }
        if (perPage < 1 || perPage > MAX_PER_PAGE) {
            throw new IllegalArgumentException("perPage debe estar entre 1 y " + MAX_PER_PAGE);
        }
        String key = requireKey();
        MultiValueMap<String, String> params = toQueryParams(request);
        params.add("page", String.valueOf(page));
        params.add("per_page", String.valueOf(perPage));

        HttpResult result = sendWithRetries(key, SEARCH_PATH, params, "búsquedas");
        SearchResponse body;
        try {
            body = objectMapper.readValue(result.body(), SearchResponse.class);
        } catch (JsonProcessingException e) {
            throw new ApolloException.InvalidResponse("Apollo respondió algo que no es JSON", e);
        }
        List<ApolloSearchPersonDTO> people = body.people() == null ? List.of() : body.people();
        int total = body.totalEntries() == null ? 0 : body.totalEntries();
        log.debug("Apollo search página {}: {} personas de {} (cupo minuto/hora/día {})", page, people.size(), total,
                result.rateLimit());
        return new ApolloSearchPage(total, page, perPage, people, result.rateLimit());
    }

    /**
     * Recorre páginas de 100 hasta juntar maxResults personas, acabar los resultados o llegar al tope de 50.000.
     * 0 créditos, pero cada página cuenta para los límites de tasa (en el plan gratis, 50 por minuto y 600 por día).
     */
    public ApolloSearchResult searchAll(ApolloPeopleSearchRequest request, int maxResults) {
        int limit = Math.min(Math.max(maxResults, 0), MAX_VISIBLE_RESULTS);
        List<ApolloSearchPersonDTO> people = new ArrayList<>();
        int page = 0;
        ApolloSearchPage current = null;
        while (people.size() < limit && (current == null || current.hasMore())) {
            page++;
            current = searchPage(request, page, MAX_PER_PAGE);
            for (ApolloSearchPersonDTO person : current.people()) {
                if (people.size() == limit) {
                    break;
                }
                people.add(person);
            }
        }
        return new ApolloSearchResult(current == null ? 0 : current.totalEntries(), people, page,
                current == null ? ApolloRateLimit.UNKNOWN : current.rateLimit());
    }

    /**
     * Tarea 4.1: People Enrichment (POST /people/match?id=...). Devuelve el correo de trabajo, el apellido completo
     * y la empresa con su dominio. GASTA 1 crédito cuando entrega el correo (por eso solo se llama para los
     * contactos que pasan el umbral de 3.5). Nunca pide correos personales ni teléfonos.
     * <p>
     * Vacío si Apollo no reconoce la persona. Si Apollo no entrega el correo (correo de relleno
     * "email_not_unlocked@..."), el correo queda en null. TODO: confirmar campos contra una respuesta real.
     */
    public Optional<ApolloPersonDTO> enrichPerson(String apolloPersonId) {
        if (apolloPersonId == null || apolloPersonId.isBlank()) {
            throw new IllegalArgumentException("Falta el ID de Apollo de la persona");
        }
        String key = requireKey();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("id", apolloPersonId.strip());
        params.add("reveal_personal_emails", "false");
        params.add("reveal_phone_number", "false");

        HttpResult result = sendWithRetries(key, MATCH_PATH, params, "enriquecimientos");
        MatchResponse body;
        try {
            body = objectMapper.readValue(result.body(), MatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new ApolloException.InvalidResponse("Apollo respondió algo que no es JSON", e);
        }
        if (body == null || body.person() == null) {
            return Optional.empty();
        }
        ApolloPersonDTO person = body.person();
        person.setEmail(usableEmail(person.getEmail()));
        return Optional.of(person);
    }

    /** null si el correo viene vacío, sin @ o es el de relleno de Apollo. */
    static String usableEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")
                || email.toLowerCase(Locale.ROOT).contains(LOCKED_EMAIL_MARK)) {
            return null;
        }
        return email.strip();
    }

    private String requireKey() {
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new ApolloException.Configuration(
                    "Falta la llave de Apollo (parámetro SSM de integrations.apollo o APOLLO_API_KEY en local)");
        }
        return key;
    }

    // ---------- HTTP ----------

    private HttpResult sendWithRetries(String key, String path, MultiValueMap<String, String> params,
            String operation) {
        int maxAttempts = 1 + Math.max(0, properties.getMaxRetries());
        String reason = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration wait;
            try {
                HttpResult result = send(key, path, params);
                if (result.status() == 200) {
                    return result;
                }
                if (result.status() == 429) {
                    Duration retryAfter = result.retryAfter() != null ? result.retryAfter() : exponentialWait(attempt);
                    if (retryAfter.compareTo(MAX_INLINE_WAIT) > 0) {
                        throw new ApolloException.RateLimited(
                                "Apollo: se agotó el cupo de %s; se reinicia en %d s (%s)"
                                        .formatted(operation, retryAfter.toSeconds(), errorMessage(result.body())),
                                retryAfter);
                    }
                    reason = "HTTP 429";
                    wait = retryAfter;
                } else if (result.status() >= 500) {
                    reason = "HTTP " + result.status();
                    wait = exponentialWait(attempt);
                } else {
                    throw new ApolloException.Rejected("Apollo rechazó la petición de %s (%d): %s"
                            .formatted(operation, result.status(), errorMessage(result.body())), result.status());
                }
            } catch (ResourceAccessException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                reason = "falla de red (" + cause.getClass().getSimpleName() + ")";
                wait = exponentialWait(attempt);
            }
            if (attempt == maxAttempts) {
                break;
            }
            log.warn("Apollo: {}; reintento {} de {} en {} ms", reason, attempt, maxAttempts - 1, wait.toMillis());
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApolloException.RetriesExhausted("Se interrumpió la espera entre reintentos");
            }
        }
        throw new ApolloException.RetriesExhausted(
                "Apollo no respondió tras %d intentos (último error: %s)".formatted(maxAttempts, reason));
    }

    private HttpResult send(String key, String path, MultiValueMap<String, String> params) {
        return restClient.post()
                .uri(builder -> builder.path(path).queryParams(params).build())
                .header("x-api-key", key)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> {
                    HttpHeaders headers = res.getHeaders();
                    return new HttpResult(
                            res.getStatusCode().value(),
                            seconds(headers.getFirst("retry-after")),
                            new ApolloRateLimit(integer(headers.getFirst("x-minute-requests-left")),
                                    integer(headers.getFirst("x-hourly-requests-left")),
                                    integer(headers.getFirst("x-24-hour-requests-left"))),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
                });
    }

    /** Filtros como query params de Apollo: las listas van con sufijo [] y se omiten las vacías. */
    static MultiValueMap<String, String> toQueryParams(ApolloPeopleSearchRequest request) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        addAll(params, "person_titles[]", request.getPersonTitles());
        if (request.getIncludeSimilarTitles() != null) {
            params.add("include_similar_titles", request.getIncludeSimilarTitles().toString());
        }
        addAll(params, "person_not_titles[]", request.getPersonNotTitles());
        addAll(params, "person_seniorities[]", request.getPersonSeniorities());
        addAll(params, "person_locations[]", request.getPersonLocations());
        addAll(params, "person_not_locations[]", request.getPersonNotLocations());
        addAll(params, "organization_locations[]", request.getOrganizationLocations());
        addAll(params, "organization_industries[]", request.getOrganizationIndustries());
        addAll(params, "organization_num_employees_ranges[]", request.getOrganizationNumEmployeesRanges());
        addAll(params, "not_organization_websites_list[]", request.getNotOrganizationWebsitesList());
        addAll(params, "contact_email_status[]", request.getContactEmailStatus());
        if (request.getQKeywords() != null && !request.getQKeywords().isBlank()) {
            params.add("q_keywords", request.getQKeywords());
        }
        return params;
    }

    private static void addAll(MultiValueMap<String, String> params, String name, List<String> values) {
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).forEach(v -> params.add(name, v));
        }
    }

    /** 2 s, 4 s, 8 s... más hasta 1 s al azar para que varias instancias no reintenten a la vez. */
    static Duration exponentialWait(int attempt) {
        long base = BASE_WAIT.toMillis() * (1L << (attempt - 1));
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(0, 1000));
    }

    private String errorMessage(String body) {
        String message;
        try {
            ApiError error = objectMapper.readValue(body, ApiError.class);
            message = error.message() != null ? error.message() : error.error() != null ? error.error() : body;
        } catch (JsonProcessingException | RuntimeException ignored) {
            message = body;
        }
        message = message == null ? "" : message;
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static Duration seconds(String value) {
        Integer parsed = integer(value);
        return parsed == null ? null : Duration.ofSeconds(Math.max(0, parsed));
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String resolveApiKey(Provider properties, AwsSsmTenantConfigResolver ssm) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            return properties.getApiKey();
        }
        String parameter = properties.getApiKeySsmParameter();
        return parameter == null || parameter.isBlank() ? null : ssm.readParameter(parameter);
    }

    private record HttpResult(int status, Duration retryAfter, ApolloRateLimit rateLimit, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SearchResponse(Integer totalEntries, List<ApolloSearchPersonDTO> people) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MatchResponse(ApolloPersonDTO person) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiError(String error, String message) {
    }
}
