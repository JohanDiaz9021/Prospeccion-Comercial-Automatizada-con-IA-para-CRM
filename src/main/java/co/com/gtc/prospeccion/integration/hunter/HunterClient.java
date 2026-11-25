package co.com.gtc.prospeccion.integration.hunter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.IntegrationsProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.Provider;
import co.com.gtc.prospeccion.config.RestClientConfig;
import co.com.gtc.prospeccion.integration.Sleeper;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterAccountResponse;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterEmailFinderResponse;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterVerifierResponse;
import co.com.gtc.prospeccion.multitenancy.AwsSsmTenantConfigResolver;

/**
 * Cliente de Hunter API v2 (tarea 4.1): Email Finder y Email Verifier.
 * <ul>
 * <li>Llave en el encabezado X-API-KEY (SSM, o HUNTER_API_KEY solo en local), nunca en la URL.</li>
 * <li>Email Finder cobra 1 búsqueda solo si encuentra el correo; Email Verifier cuesta 0,5 crédito. El costo real
 * de cada llamada llega en X-Credits-Used y se devuelve para el registro de créditos (4.2).</li>
 * <li>403 = límite por segundo: espera y reintenta. 429 = se acabó el cupo del plan: QuotaExhausted, sin reintentos.
 * 451 = la persona pidió no ser procesada: OptedOut. 5xx y fallas de red: reintentos con espera exponencial.</li>
 * <li>Email Verifier responde 202 (o 222) mientras la verificación sigue en curso: se vuelve a llamar y Hunter lo
 * cuenta como una sola petición.</li>
 * </ul>
 * Nunca registra la llave ni correos.
 */
@Component
public class HunterClient {

    private static final Logger log = LoggerFactory.getLogger(HunterClient.class);

    static final String FINDER_PATH = "/email-finder";
    static final String VERIFIER_PATH = "/email-verifier";
    static final String ACCOUNT_PATH = "/account";
    static final Duration BASE_WAIT = Duration.ofSeconds(1);
    /** Entre llamadas al verificador mientras responde 202 (cada llamada ya tarda hasta 20 s en Hunter). */
    static final Duration PENDING_WAIT = Duration.ofSeconds(2);
    static final int MAX_PENDING_CHECKS = 3;

    private final RestClient restClient;
    private final Provider properties;
    private final Supplier<String> apiKey;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    @Autowired
    public HunterClient(@Qualifier(RestClientConfig.HUNTER) RestClient restClient, IntegrationsProperties integrations,
            AwsSsmTenantConfigResolver ssm, ObjectMapper objectMapper) {
        this(restClient, integrations.getHunter(), () -> resolveApiKey(integrations.getHunter(), ssm), objectMapper,
                Sleeper.REAL);
    }

    HunterClient(RestClient restClient, Provider properties, Supplier<String> apiKey, ObjectMapper objectMapper,
            Sleeper sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    /**
     * Busca el correo de una persona en el dominio de su empresa. Vacío si Hunter no lo encuentra (no cobra).
     *
     * @throws HunterException.OptedOut si la persona pidió no ser procesada (451)
     */
    public Optional<HunterEmailFinderResponse> findEmail(String domain, String firstName, String lastName) {
        if (isBlank(domain) || isBlank(firstName) || isBlank(lastName)) {
            throw new IllegalArgumentException("Email Finder necesita dominio, nombre y apellido");
        }
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("domain", domain.strip());
        params.add("first_name", firstName.strip());
        params.add("last_name", lastName.strip());

        HttpResult result = sendWithRetries(FINDER_PATH, params, "búsqueda");
        if (result.status() == 404) {
            return Optional.empty();
        }
        HunterEmailFinderResponse data = readData(result.body(), HunterEmailFinderResponse.class);
        if (data == null || isBlank(data.getEmail())) {
            return Optional.empty();
        }
        data.setCreditsUsed(result.creditsUsed());
        return Optional.of(data);
    }

    /**
     * Verifica un correo. Si Hunter sigue verificando (202/222) vuelve a preguntar hasta {@value #MAX_PENDING_CHECKS}
     * veces; después lanza RetriesExhausted (quien llama lo trata como "unknown").
     *
     * @throws HunterException.OptedOut si la persona pidió no ser procesada (451)
     */
    public HunterVerifierResponse verifyEmail(String email) {
        if (isBlank(email) || !email.contains("@")) {
            throw new IllegalArgumentException("Correo inválido para verificar");
        }
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("email", email.strip());

        for (int check = 1; check <= MAX_PENDING_CHECKS; check++) {
            HttpResult result = sendWithRetries(VERIFIER_PATH, params, "verificación");
            if (result.status() == 200) {
                HunterVerifierResponse data = readData(result.body(), HunterVerifierResponse.class);
                if (data == null || isBlank(data.getStatus())) {
                    throw new HunterException.InvalidResponse("Hunter no devolvió el estado de la verificación", null);
                }
                data.setCreditsUsed(result.creditsUsed());
                return data;
            }
            // 202 o 222: la verificación sigue en curso en Hunter.
            log.debug("Hunter: verificación en curso (HTTP {}), consulta {} de {}", result.status(), check,
                    MAX_PENDING_CHECKS);
            if (check < MAX_PENDING_CHECKS) {
                pause(PENDING_WAIT);
            }
        }
        throw new HunterException.RetriesExhausted("Hunter no terminó la verificación tras " + MAX_PENDING_CHECKS
                + " consultas");
    }

    /** Saldo de la cuenta (GET /account). Esta llamada es gratis. */
    public HunterAccountResponse account() {
        HttpResult result = sendWithRetries(ACCOUNT_PATH, new LinkedMultiValueMap<>(), "consulta de cuenta");
        HunterAccountResponse data = readData(result.body(), HunterAccountResponse.class);
        if (data == null) {
            throw new HunterException.InvalidResponse("Hunter no devolvió los datos de la cuenta", null);
        }
        return data;
    }

    // ---------- HTTP ----------

    /**
     * Devuelve las respuestas 200, 202, 222 y (en el finder) 404; las demás se convierten en excepciones o en
     * reintentos.
     */
    private HttpResult sendWithRetries(String path, MultiValueMap<String, String> params, String operation) {
        String key = apiKey.get();
        if (isBlank(key)) {
            throw new HunterException.Configuration(
                    "Falta la llave de Hunter (parámetro SSM de integrations.hunter o HUNTER_API_KEY en local)");
        }
        int maxAttempts = 1 + Math.max(0, properties.getMaxRetries());
        String reason = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResult result = send(key, path, params);
                int status = result.status();
                if (status == 200 || status == 202 || status == 222 || (status == 404 && FINDER_PATH.equals(path))) {
                    return result;
                }
                if (status == 451) {
                    throw new HunterException.OptedOut("Hunter: la persona pidió no ser procesada (451)");
                }
                if (status == 429) {
                    throw new HunterException.QuotaExhausted("Hunter: se acabó el cupo de " + operation
                            + " del plan (" + errorMessage(result.body()) + ")");
                }
                if (status == 403 || status >= 500) {
                    reason = "HTTP " + status;
                } else {
                    throw new HunterException.Rejected("Hunter rechazó la %s (%d): %s"
                            .formatted(operation, status, errorMessage(result.body())), status);
                }
            } catch (ResourceAccessException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                reason = "falla de red (" + cause.getClass().getSimpleName() + ")";
            }
            if (attempt == maxAttempts) {
                break;
            }
            Duration wait = exponentialWait(attempt);
            log.warn("Hunter: {}; reintento {} de {} en {} ms", reason, attempt, maxAttempts - 1, wait.toMillis());
            pause(wait);
        }
        throw new HunterException.RetriesExhausted(
                "Hunter no respondió tras %d intentos (último error: %s)".formatted(maxAttempts, reason));
    }

    private HttpResult send(String key, String path, MultiValueMap<String, String> params) {
        return restClient.get()
                .uri(builder -> builder.path(path).queryParams(params).build())
                .header("X-API-KEY", key)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> new HttpResult(
                        res.getStatusCode().value(),
                        decimal(res.getHeaders().getFirst("X-Credits-Used")),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private <T> T readData(String body, Class<T> type) {
        try {
            JavaType wrapper = objectMapper.getTypeFactory().constructParametricType(DataWrapper.class, type);
            DataWrapper<T> parsed = objectMapper.readValue(body, wrapper);
            return parsed == null ? null : parsed.data();
        } catch (JsonProcessingException e) {
            throw new HunterException.InvalidResponse("Hunter respondió algo que no es JSON", e);
        }
    }

    private void pause(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HunterException.RetriesExhausted("Se interrumpió la espera entre reintentos");
        }
    }

    /** 1 s, 2 s, 4 s... más hasta 0,5 s al azar. */
    static Duration exponentialWait(int attempt) {
        long base = BASE_WAIT.toMillis() * (1L << (attempt - 1));
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(0, 500));
    }

    private String errorMessage(String body) {
        String message;
        try {
            ErrorBody error = objectMapper.readValue(body, ErrorBody.class);
            message = error != null && error.errors() != null && error.errors().length > 0
                    ? error.errors()[0].id() + ": " + error.errors()[0].details()
                    : body;
        } catch (JsonProcessingException | RuntimeException ignored) {
            message = body;
        }
        message = message == null ? "" : message;
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static Double decimal(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String resolveApiKey(Provider properties, AwsSsmTenantConfigResolver ssm) {
        if (!isBlank(properties.getApiKey())) {
            return properties.getApiKey();
        }
        String parameter = properties.getApiKeySsmParameter();
        return isBlank(parameter) ? null : ssm.readParameter(parameter);
    }

    private record HttpResult(int status, Double creditsUsed, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DataWrapper<T>(T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(ErrorItem[] errors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorItem(String id, Integer code, String details) {
    }
}
