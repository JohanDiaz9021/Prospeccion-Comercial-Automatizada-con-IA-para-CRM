package co.com.gtc.prospeccion.crm.service.impl;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.crm.service.CrmExclusionService;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.prospect.repository.LeafletExtRepository;
import co.com.gtc.prospeccion.search.rules.ProfileExclusions;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;

/**
 * Exclusiones de la búsqueda (tareas 3.2 y 3.4), de la más barata a la más cara:
 * <ul>
 * <li>Lista de bloqueo (la_suppression).</li>
 * <li>Listas del perfil: dominios propios, competidores y empresas excluidas a mano.</li>
 * <li>Contactos y empresas que ya trajo la prospección (la_evidence y la_leafletext).</li>
 * <li>Lo que ya está en el CRM, por correo: contactos de prospectos ({@code pm_leafletcontactmedium}, también los
 * creados a mano) y proveedores ({@code pv_contactmediumprovider}: colaboradores, clientes, aliados). Por dominio:
 * si alguien de la misma empresa ya está en el CRM, no se le escribe a otra persona de esa empresa.</li>
 * </ul>
 * Las tablas del CRM se leen directo: viven en la misma base del tenant y msa-cotizaciones no tiene un endpoint para
 * buscar por correo. Solo lectura.
 */
@Service
public class CrmExclusionServiceImpl implements CrmExclusionService {

    /** Correo exacto (sin mayúsculas ni espacios) en prospectos o proveedores del CRM. */
    static final String CRM_EMAIL_SQL = "SELECT (EXISTS (SELECT 1 FROM pm_leafletcontactmedium "
            + "WHERE LOWER(TRIM(VALUE)) = ?) OR EXISTS (SELECT 1 FROM pv_contactmediumprovider "
            + "WHERE LOWER(TRIM(VALUE)) = ?))";
    /** Algún correo del CRM con el mismo dominio ("%@empresa.com"). */
    static final String CRM_DOMAIN_SQL = "SELECT (EXISTS (SELECT 1 FROM pm_leafletcontactmedium "
            + "WHERE LOWER(TRIM(VALUE)) LIKE ? ESCAPE '!') OR EXISTS (SELECT 1 FROM pv_contactmediumprovider "
            + "WHERE LOWER(TRIM(VALUE)) LIKE ? ESCAPE '!'))";

    private final SuppressionService suppressionService;
    private final EvidenceRepository evidenceRepository;
    private final LeafletExtRepository leafletExtRepository;
    private final JdbcTemplate jdbc;

    public CrmExclusionServiceImpl(SuppressionService suppressionService, EvidenceRepository evidenceRepository,
            LeafletExtRepository leafletExtRepository, JdbcTemplate jdbc) {
        this.suppressionService = suppressionService;
        this.evidenceRepository = evidenceRepository;
        this.leafletExtRepository = leafletExtRepository;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Reason> exclusionReason(String email, String companyDomain, ProfileExclusions lists) {
        String normalizedEmail = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        String emailDomain = ProfileExclusions.normalizeDomain(normalizedEmail);
        String company = companyDomain != null ? ProfileExclusions.normalizeDomain(companyDomain) : emailDomain;
        ProfileExclusions l = lists == null ? ProfileExclusions.NONE : lists;

        if (normalizedEmail != null && suppressionService.isSuppressed(normalizedEmail)) {
            return Optional.of(Reason.SUPPRESSED);
        }
        if (matches(emailDomain, l.ownDomains()) || matches(company, l.ownDomains())) {
            return Optional.of(Reason.COLLABORATOR);
        }
        if (matches(company, l.notToSearch()) || matches(emailDomain, l.notToSearch())) {
            return Optional.of(Reason.EXCLUDED_COMPANY);
        }
        if (normalizedEmail != null
                && evidenceRepository.existsByFieldAndValue(EvidenceFields.EMAIL, normalizedEmail)) {
            return Optional.of(Reason.EXISTING_CONTACT);
        }
        if (normalizedEmail != null && exists(CRM_EMAIL_SQL, normalizedEmail)) {
            return Optional.of(Reason.CRM_CONTACT);
        }
        if (company != null && leafletExtRepository.existsByCompanyDomain(company)) {
            return Optional.of(Reason.EXISTING_COMPANY);
        }
        if (emailDomain != null && exists(CRM_DOMAIN_SQL, "%@" + escapeLike(emailDomain))
                || company != null && !company.equals(emailDomain)
                        && exists(CRM_DOMAIN_SQL, "%@" + escapeLike(company))) {
            return Optional.of(Reason.CRM_COMPANY);
        }
        return Optional.empty();
    }

    private boolean exists(String sql, String value) {
        Boolean found = jdbc.queryForObject(sql, Boolean.class, value, value);
        return Boolean.TRUE.equals(found);
    }

    /** "_" y "%" son comodines de LIKE: en un dominio se buscan tal cual. */
    static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** El dominio es uno de la lista o un subdominio suyo (ventas.empresa.com cuenta como empresa.com). */
    private static boolean matches(String domain, Set<String> list) {
        if (domain == null || list.isEmpty()) {
            return false;
        }
        for (String excluded : list) {
            if (domain.equals(excluded) || domain.endsWith("." + excluded)) {
                return true;
            }
        }
        return false;
    }
}
