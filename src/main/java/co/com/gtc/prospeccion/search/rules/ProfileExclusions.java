package co.com.gtc.prospeccion.search.rules;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Listas de exclusión del perfil de interés ({@code exclusions}, docs/negocio/perfil-de-interes.md, sección 8), en
 * minúsculas y sin "www.". Si el perfil no las trae, quedan vacías.
 *
 * @param competitorDomains      competidores: no se buscan en Apollo
 * @param excludedCompanyDomains empresas excluidas a mano (por ejemplo, clientes): no se buscan en Apollo
 * @param ownDomains             dominios de la propia empresa: sus colaboradores nunca son prospectos
 */
public record ProfileExclusions(Set<String> competitorDomains, Set<String> excludedCompanyDomains,
        Set<String> ownDomains) {

    public static final ProfileExclusions NONE = new ProfileExclusions(Set.of(), Set.of(), Set.of());

    public static ProfileExclusions from(String profileOfInterest, ObjectMapper objectMapper) {
        if (profileOfInterest == null || profileOfInterest.isBlank()) {
            return NONE;
        }
        try {
            JsonNode exclusions = objectMapper.readTree(profileOfInterest).path("exclusions");
            return new ProfileExclusions(domains(exclusions.path("competitorDomains")),
                    domains(exclusions.path("excludedCompanyDomains")), domains(exclusions.path("ownDomains")));
        } catch (JsonProcessingException e) {
            return NONE;
        }
    }

    /** Competidores y excluidas a mano: van en not_organization_websites_list de Apollo. */
    public Set<String> notToSearch() {
        Set<String> all = new LinkedHashSet<>(competitorDomains);
        all.addAll(excludedCompanyDomains);
        return all;
    }

    /** "https://www.Empresa.com/x", "ana@empresa.com" o "empresa.com." quedan como "empresa.com". */
    public static String normalizeDomain(String domain) {
        if (domain == null) {
            return null;
        }
        String d = domain.strip().toLowerCase(Locale.ROOT);
        int at = d.lastIndexOf('@');
        if (at >= 0) {
            d = d.substring(at + 1);
        }
        d = d.replaceFirst("^[a-z]+://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        if (d.startsWith("www.")) {
            d = d.substring(4);
        }
        while (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        return d.isEmpty() ? null : d;
    }

    private static Set<String> domains(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array.isArray()) {
            array.forEach(v -> {
                String d = v.isTextual() ? normalizeDomain(v.asText()) : null;
                if (d != null) {
                    values.add(d);
                }
            });
        }
        return Set.copyOf(values);
    }
}
