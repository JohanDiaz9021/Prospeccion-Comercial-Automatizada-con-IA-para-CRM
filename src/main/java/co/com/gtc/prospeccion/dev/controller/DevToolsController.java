package co.com.gtc.prospeccion.dev.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.service.EmailDiscoveryService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Herramientas para probar en local lo que todavía no tiene pantalla (pestaña "Probar Correo" del mini front).
 * <p>
 * <b>Solo existe con el perfil local</b>: en QA y producción no se registra. Conseguir el correo gasta créditos reales
 * de Apollo y Hunter, con los mismos topes y el mismo libro que la búsqueda (4.2); por eso no se expone en el CRM.
 */
@Profile("local")
@RestController
@PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
@RequestMapping("/api/dev")
@Tag(name = "Herramientas locales")
public class DevToolsController {

    private final ApolloClient apollo;
    private final EmailDiscoveryService emailDiscovery;

    public DevToolsController(ApolloClient apollo, EmailDiscoveryService emailDiscovery) {
        this.apollo = apollo;
        this.emailDiscovery = emailDiscovery;
    }

    /** Filtros mínimos de una búsqueda de prueba en Apollo. */
    public record ApolloSearchRequest(
            @Size(max = 200) String title,
            @Size(max = 100) String location,
            @Size(max = 30) String employeeRange,
            @Size(max = 200) String keywords,
            @Min(1) @Max(5) Integer page) {
    }

    public record EmailDiscoveryRequest(@NotBlank @Size(max = 100) String apolloPersonId) {
    }

    @PostMapping("/apollo/search")
    @Operation(summary = "Búsqueda de personas en Apollo (0 créditos). Máximo 10 por página, 5 páginas.")
    public ResponseEntity<GeneralReponse> searchApollo(@Valid @RequestBody ApolloSearchRequest request) {
        ApolloPeopleSearchRequest filtros = new ApolloPeopleSearchRequest();
        filtros.setPersonTitles(list(request.title()));
        filtros.setPersonLocations(list(request.location()));
        filtros.setOrganizationNumEmployeesRanges(list(request.employeeRange()));
        filtros.setQKeywords(blankToNull(request.keywords()));
        ApolloSearchPage page = apollo.searchPage(filtros, request.page() == null ? 1 : request.page(), 10);

        List<Map<String, Object>> people = new ArrayList<>();
        page.people().forEach(p -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("id", p.getId());
            fila.put("firstName", p.getFirstName());
            fila.put("lastNameObfuscated", p.getLastNameObfuscated());
            fila.put("title", p.getTitle());
            fila.put("organizationName", p.getOrganization() == null ? null : p.getOrganization().getName());
            fila.put("hasEmail", p.getHasEmail());
            people.add(fila);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", page.totalEntries());
        data.put("page", page.page());
        data.put("hasMore", page.hasMore());
        data.put("people", people);
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, data));
    }

    /** Persona ya conocida, para probar solo Hunter. */
    public record HunterEmailRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 255) String domain) {
    }

    @PostMapping("/email-discovery")
    @Operation(summary = "Consigue y verifica el correo de una persona de Apollo (4.1). GASTA CRÉDITOS y los anota (4.2).")
    public ResponseEntity<GeneralReponse> discoverEmail(@Valid @RequestBody EmailDiscoveryRequest request) {
        EmailDiscoveryResult result = emailDiscovery.discover(request.apolloPersonId().strip(), CreditContext.NONE);
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, toData(result)));
    }

    @PostMapping("/email-discovery/hunter")
    @Operation(summary = "Solo Hunter con nombre, apellido y dominio (4.1 sin Apollo). GASTA CRÉDITOS de Hunter y los anota (4.2).")
    public ResponseEntity<GeneralReponse> discoverEmailWithHunter(@Valid @RequestBody HunterEmailRequest request) {
        EmailDiscoveryResult result = emailDiscovery.discoverWithHunter(request.firstName(), request.lastName(),
                request.domain(), CreditContext.NONE);
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, toData(result)));
    }

    private static Map<String, Object> toData(EmailDiscoveryResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", result.getOutcome());
        data.put("email", result.getEmail());
        data.put("source", result.getSource());
        data.put("verification", result.getVerification());
        data.put("score", result.getScore());
        data.put("apolloCredits", result.getApolloCredits());
        data.put("hunterSearchCredits", result.getHunterSearchCredits());
        data.put("hunterVerificationCredits", result.getHunterVerificationCredits());
        ApolloPersonDTO person = result.getPerson();
        if (person != null) {
            Map<String, Object> persona = new LinkedHashMap<>();
            persona.put("firstName", person.getFirstName());
            persona.put("lastName", person.getLastName());
            persona.put("title", person.getTitle());
            persona.put("city", person.getCity());
            persona.put("organizationName", person.getOrganization() == null ? null : person.getOrganization().getName());
            persona.put("domain", person.getOrganization() == null ? null : person.getOrganization().getPrimaryDomain());
            data.put("person", persona);
        }
        return data;
    }

    private static List<String> list(String value) {
        String limpio = blankToNull(value);
        return limpio == null ? null : List.of(limpio);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
