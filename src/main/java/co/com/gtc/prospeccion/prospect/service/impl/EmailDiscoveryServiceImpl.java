package co.com.gtc.prospeccion.prospect.service.impl;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.config.PropertiesConfig.EmailDiscovery;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.credits.CreditCharge;
import co.com.gtc.prospeccion.credits.CreditContext;
import co.com.gtc.prospeccion.credits.service.CreditLedgerService;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.hunter.HunterClient;
import co.com.gtc.prospeccion.integration.hunter.HunterException;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterEmailFinderResponse;
import co.com.gtc.prospeccion.integration.hunter.dto.HunterVerifierResponse;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult.Outcome;
import co.com.gtc.prospeccion.prospect.service.EmailDiscoveryService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Correo de trabajo de un contacto calificado (tarea 4.1, reglas en docs/negocio/correo-de-contacto.md):
 * <ol>
 * <li>Apollo People Enrichment (1 crédito si entrega el correo). Trae también el apellido completo y el dominio.</li>
 * <li>Si Apollo lo entrega como "verified" se acepta (configurable); si no, se verifica con Hunter (0,5).</li>
 * <li>Si Apollo no lo tiene, Hunter Email Finder con nombre, apellido y dominio (1 búsqueda, solo si encuentra).
 * Si el finder ya lo trae verificado con buen puntaje se acepta; si no, se verifica.</li>
 * <li>Solo se aceptan los estados de Hunter configurados (por defecto valid y accept_all). Se descartan los
 * correos de dominios personales del perfil (exclusions.freeEmailDomains).</li>
 * </ol>
 * Créditos (4.2): antes de cada llamada que cuesta se revisan los topes y, apenas se gasta, se anota en el libro.
 * Un 451 de Hunter (la persona pidió no ser procesada) termina en OPTED_OUT. La falta de llave, el cupo agotado y
 * las fallas de red se propagan para que el job reintente más tarde. No abre transacción ni guarda nada.
 */
@Service
public class EmailDiscoveryServiceImpl implements EmailDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailDiscoveryServiceImpl.class);

    /** Si el perfil no trae exclusions.freeEmailDomains (mismos valores del perfil aprobado v1). */
    static final Set<String> DEFAULT_FREE_DOMAINS =
            Set.of("gmail.com", "hotmail.com", "outlook.com", "live.com", "yahoo.com", "icloud.com", "aol.com");
    /** Costos documentados por Hunter, si no llega el encabezado X-Credits-Used. */
    static final double HUNTER_SEARCH_COST = 1.0;
    static final double HUNTER_VERIFICATION_COST = 0.5;

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final ApolloClient apollo;
    private final HunterClient hunter;
    private final TenantConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final CreditLedgerService credits;
    private final EmailDiscovery rules;

    public EmailDiscoveryServiceImpl(ApolloClient apollo, HunterClient hunter, TenantConfigRepository configRepository,
            ObjectMapper objectMapper, CreditLedgerService credits, ProspeccionProperties properties) {
        this.apollo = apollo;
        this.hunter = hunter;
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
        this.credits = credits;
        this.rules = properties.getEmailDiscovery();
    }

    @Override
    public EmailDiscoveryResult discover(String apolloPersonId, CreditContext context) {
        CreditContext ctx = context != null ? context : CreditContext.NONE;
        Set<String> freeDomains = freeEmailDomains();

        // 1. Apollo People Enrichment: 1 crédito si entrega el correo.
        credits.ensureAvailable(CreditCharge.APOLLO, ctx.searchRunId(), ONE);
        Optional<ApolloPersonDTO> match = apollo.enrichPerson(apolloPersonId);
        if (match.isEmpty()) {
            return log(EmailDiscoveryResult.builder().outcome(Outcome.NO_MATCH).build());
        }
        ApolloPersonDTO person = match.get();
        EmailDiscoveryResult.EmailDiscoveryResultBuilder result = EmailDiscoveryResult.builder().person(person);

        String apolloEmail = person.getEmail();
        if (apolloEmail != null) {
            charge(ctx, CreditCharge.APOLLO, CreditCharge.PEOPLE_ENRICHMENT, ONE);
            result.apolloCredits(1).source(EmailDiscoveryResult.SOURCE_APOLLO).email(apolloEmail);
            if (isPersonal(apolloEmail, freeDomains)) {
                return log(result.outcome(Outcome.DISCARDED).verification(EmailDiscoveryResult.PERSONAL_DOMAIN).build());
            }
            if (rules.isTrustApolloVerified() && "verified".equalsIgnoreCase(person.getEmailStatus())) {
                return log(result.outcome(Outcome.FOUND).verification(EmailDiscoveryResult.APOLLO_VERIFIED).build());
            }
            return log(verify(apolloEmail, result, 0, ctx));
        }

        // 2. Apollo no lo tiene: Hunter Email Finder con el dominio de la empresa.
        String domain = person.getOrganization() == null ? null : person.getOrganization().getPrimaryDomain();
        return log(findWithHunter(domain, person.getFirstName(), person.getLastName(), result, freeDomains, ctx));
    }

    @Override
    public EmailDiscoveryResult discoverWithHunter(String firstName, String lastName, String domain,
            CreditContext context) {
        CreditContext ctx = context != null ? context : CreditContext.NONE;
        return log(findWithHunter(normalizeDomain(domain), strip(firstName), strip(lastName),
                EmailDiscoveryResult.builder(), freeEmailDomains(), ctx));
    }

    /**
     * Hunter Email Finder (1 búsqueda, solo si encuentra) y, si hace falta, Email Verifier (0,5). Mismas reglas de
     * aceptación y mismos topes de créditos venga o no de Apollo.
     */
    private EmailDiscoveryResult findWithHunter(String domain, String firstName, String lastName,
            EmailDiscoveryResult.EmailDiscoveryResultBuilder result, Set<String> freeDomains, CreditContext ctx) {
        if (isBlank(domain) || isBlank(firstName) || isBlank(lastName)
                || freeDomains.contains(domain.toLowerCase(Locale.ROOT))) {
            return result.outcome(Outcome.NOT_FOUND).build();
        }
        credits.ensureAvailable(CreditCharge.HUNTER, ctx.searchRunId(), BigDecimal.valueOf(HUNTER_SEARCH_COST));
        Optional<HunterEmailFinderResponse> found;
        try {
            found = hunter.findEmail(domain, firstName, lastName);
        } catch (HunterException.OptedOut e) {
            return result.outcome(Outcome.OPTED_OUT).source(EmailDiscoveryResult.SOURCE_HUNTER).build();
        }
        if (found.isEmpty()) {
            return result.outcome(Outcome.NOT_FOUND).build();
        }
        HunterEmailFinderResponse finder = found.get();
        double searchCredits = finder.getCreditsUsed() != null ? finder.getCreditsUsed() : HUNTER_SEARCH_COST;
        charge(ctx, CreditCharge.HUNTER, CreditCharge.EMAIL_FINDER, BigDecimal.valueOf(searchCredits));
        result.source(EmailDiscoveryResult.SOURCE_HUNTER).email(finder.getEmail()).score(finder.getScore())
                .hunterSearchCredits(searchCredits);
        if (isPersonal(finder.getEmail(), freeDomains)) {
            return result.outcome(Outcome.DISCARDED).verification(EmailDiscoveryResult.PERSONAL_DOMAIN).build();
        }
        String finderStatus = finder.verificationStatus();
        if (accepted(finderStatus) && finder.getScore() != null && finder.getScore() >= rules.getMinFinderScore()) {
            return result.outcome(Outcome.FOUND).verification(finderStatus).build();
        }
        return verify(finder.getEmail(), result, searchCredits, ctx);
    }

    /**
     * Deja solo el dominio: "https://www.Empresa.co/contacto", "@empresa.co" y "ana@empresa.co" → "empresa.co".
     * null si no queda nada.
     */
    static String normalizeDomain(String value) {
        if (isBlank(value)) {
            return null;
        }
        String domain = value.strip().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "");
        int arroba = domain.lastIndexOf('@');
        if (arroba >= 0) {
            domain = domain.substring(arroba + 1);
        }
        int corte = domain.indexOf('/');
        if (corte >= 0) {
            domain = domain.substring(0, corte);
        }
        domain = domain.replaceFirst("^www\\.", "").replaceAll("^\\.+|\\.+$", "").strip();
        return domain.isEmpty() ? null : domain;
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** Hunter Email Verifier (0,5 crédito) y decisión con los estados aceptados. */
    private EmailDiscoveryResult verify(String email, EmailDiscoveryResult.EmailDiscoveryResultBuilder result,
            double searchCredits, CreditContext ctx) {
        credits.ensureAvailable(CreditCharge.HUNTER, ctx.searchRunId(), BigDecimal.valueOf(HUNTER_VERIFICATION_COST));
        HunterVerifierResponse verification;
        try {
            verification = hunter.verifyEmail(email);
        } catch (HunterException.OptedOut e) {
            return result.outcome(Outcome.OPTED_OUT).hunterSearchCredits(searchCredits).build();
        }
        double cost = verification.getCreditsUsed() != null ? verification.getCreditsUsed() : HUNTER_VERIFICATION_COST;
        charge(ctx, CreditCharge.HUNTER, CreditCharge.EMAIL_VERIFIER, BigDecimal.valueOf(cost));
        result.hunterVerificationCredits(cost).verification(verification.getStatus());
        if (verification.getScore() != null) {
            result.score(verification.getScore());
        }
        return result.outcome(accepted(verification.getStatus()) ? Outcome.FOUND : Outcome.DISCARDED).build();
    }

    /** Anota el gasto apenas ocurre: si después algo falla, lo gastado no se pierde del libro. */
    private void charge(CreditContext ctx, String provider, String operation, BigDecimal amount) {
        credits.record(new CreditCharge(provider, operation, amount, ctx.searchRunId(), ctx.pmLeafletContactId(),
                ctx.userId()));
    }

    private boolean accepted(String hunterStatus) {
        if (hunterStatus == null) {
            return false;
        }
        List<String> acceptedStatuses = rules.getAcceptedHunterStatuses();
        return acceptedStatuses != null && acceptedStatuses.stream()
                .anyMatch(s -> s != null && s.strip().equalsIgnoreCase(hunterStatus));
    }

    /** Dominios personales del perfil vigente (exclusions.freeEmailDomains) o los de por defecto. */
    Set<String> freeEmailDomains() {
        Optional<String> profile = configRepository.findFirstByFlgActiveTrueOrderByIdDesc()
                .map(TenantConfigET::getProfileOfInterest);
        if (profile.isPresent() && !isBlank(profile.get())) {
            try {
                JsonNode list = objectMapper.readTree(profile.get()).path("exclusions").path("freeEmailDomains");
                if (list.isArray() && !list.isEmpty()) {
                    Set<String> domains = new HashSet<>();
                    list.forEach(d -> domains.add(d.asText().strip().toLowerCase(Locale.ROOT)));
                    return domains;
                }
            } catch (Exception e) {
                // 6.2 valida el JSON al guardar; si alguien lo dañó en la base, se usan los de por defecto.
                log.warn("El perfil vigente no se pudo leer; se usan los dominios personales por defecto");
            }
        }
        return DEFAULT_FREE_DOMAINS;
    }

    static boolean isPersonal(String email, Set<String> freeDomains) {
        int at = email.lastIndexOf('@');
        return at >= 0 && freeDomains.contains(email.substring(at + 1).strip().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Registra solo el resultado y los créditos, nunca el correo. */
    private static EmailDiscoveryResult log(EmailDiscoveryResult result) {
        log.info("Correo de contacto: {}", result);
        return result;
    }
}
