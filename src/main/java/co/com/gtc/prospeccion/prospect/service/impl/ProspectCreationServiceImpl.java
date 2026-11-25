package co.com.gtc.prospeccion.prospect.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.CrmContactMediumType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.CrmProspectStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.dto.FullProspectRequestDTO;
import co.com.gtc.prospeccion.crm.dto.LeafletContactMediumRequestDTO;
import co.com.gtc.prospeccion.crm.dto.LeafletContactRequestDTO;
import co.com.gtc.prospeccion.crm.dto.LeafletRequestDTO;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort.CreatedProspect;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloOrganizationDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.entities.ContactExtET;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.entities.LeafletExtET;
import co.com.gtc.prospeccion.prospect.persistence.ProspectExtDAO;
import co.com.gtc.prospeccion.prospect.repository.LeafletExtRepository;
import co.com.gtc.prospeccion.prospect.service.ProspectCreationService;
import co.com.gtc.prospeccion.search.rules.ProfileExclusions;

/**
 * Tarea 3.2, paso "crear": la empresa, el contacto y su correo van al CRM por {@link CrmProspectPort} (en local, a la
 * base local; en QA y producción, a msa-cotizaciones) y lo que el CRM no guarda queda en la_* con su fuente.
 * <p>
 * Solo se guarda lo que Apollo entregó: lo que no vino queda sin evidencia y la redacción no lo usa.
 */
@Service
public class ProspectCreationServiceImpl implements ProspectCreationService {

    static final int MAX_REASON = 500;
    static final String NO_NAME = "Empresa sin nombre";

    private final CrmProspectPort crmProspectPort;
    private final ProspectExtDAO prospectExtDAO;
    private final LeafletExtRepository leafletExtRepository;
    private final MasterTableRepository masterTableRepository;
    private final UserRepository userRepository;
    private final DateTableResolver dateTableResolver;

    public ProspectCreationServiceImpl(CrmProspectPort crmProspectPort, ProspectExtDAO prospectExtDAO,
            LeafletExtRepository leafletExtRepository, MasterTableRepository masterTableRepository,
            UserRepository userRepository, DateTableResolver dateTableResolver) {
        this.crmProspectPort = crmProspectPort;
        this.prospectExtDAO = prospectExtDAO;
        this.leafletExtRepository = leafletExtRepository;
        this.masterTableRepository = masterTableRepository;
        this.userRepository = userRepository;
        this.dateTableResolver = dateTableResolver;
    }

    @Override
    @Transactional
    public Integer createProspect(EmailDiscoveryResult found, GeminiQualificationResult qualification,
            Integer searchRunId, Integer pmListContactId, Integer userId) {
        if (found == null || !found.isFound() || found.getPerson() == null) {
            throw new IllegalArgumentException("Solo se crea el prospecto de un contacto con correo aceptado");
        }
        ApolloPersonDTO person = found.getPerson();
        ApolloOrganizationDTO org = person.getOrganization() != null ? person.getOrganization()
                : new ApolloOrganizationDTO();
        String email = found.getEmail().strip().toLowerCase(Locale.ROOT);
        String companyName = blank(org.getName()) ? NO_NAME : org.getName().strip();
        Integer score = qualification == null ? null : qualification.getScore();

        CreatedProspect created = crmProspectPort.createProspect(FullProspectRequestDTO.builder()
                .leaflet(LeafletRequestDTO.builder()
                        .firstName(companyName)
                        .mtStatusId(masterId(CrmProspectStatus.TABLECODE, CrmProspectStatus.NEW))
                        .interest(score == null ? "Prospección IA" : "Prospección IA (puntaje " + score + ")")
                        .createdBy(userId)
                        .build())
                .contact(LeafletContactRequestDTO.builder()
                        .userFlg(1)
                        .name(trimmed(person.getFirstName()))
                        .surname(trimmed(person.getLastName()))
                        .position(trimmed(person.getTitle()))
                        .createdBy(userId)
                        .build())
                .contactMediumList(List.of(LeafletContactMediumRequestDTO.builder()
                        .mtContactProviderETId(masterId(CrmContactMediumType.TABLECODE, CrmContactMediumType.EMAIL))
                        .value(email)
                        .createdBy(userId)
                        .build()))
                .contactListIds(pmListContactId == null ? null : List.of(pmListContactId))
                .build(), userId);
        Integer contactId = created.leafletContactId();

        UserET author = userRepository.getReferenceById(userId);
        DateTableET today = dateTableResolver.today();
        saveLeafletExt(created.leafletId(), org, author, today);
        saveContactExt(contactId, person, found, qualification, searchRunId, author, today);
        saveEvidence(contactId, person, org, email, found.getSource(), author, today);
        return contactId;
    }

    private void saveLeafletExt(Integer leafletId, ApolloOrganizationDTO org, UserET author, DateTableET today) {
        String domain = ProfileExclusions.normalizeDomain(org.getPrimaryDomain());
        // Columnas únicas: si otra empresa ya tiene ese dominio o ID de Apollo, este prospecto queda sin ellos.
        if (domain != null && leafletExtRepository.existsByCompanyDomain(domain)) {
            domain = null;
        }
        String providerId = blank(org.getId()) || leafletExtRepository.existsByProviderId(org.getId()) ? null
                : org.getId();
        LeafletExtET ext = new LeafletExtET();
        ext.setPmLeafletId(leafletId);
        ext.setProviderId(providerId);
        ext.setCompanyDomain(domain);
        ext.setIndustry(cut(org.getIndustry(), 150));
        ext.setCompanySize(org.getEstimatedNumEmployees() == null ? null : org.getEstimatedNumEmployees().toString());
        ext.setCity(cut(org.getCity(), 150));
        ext.setCompanyLinkedinUrl(cut(org.getLinkedinUrl(), 500));
        ext.setCreatedBy(author);
        ext.setCreatedDate(today);
        prospectExtDAO.saveLeafletExt(ext);
    }

    private void saveContactExt(Integer contactId, ApolloPersonDTO person, EmailDiscoveryResult found,
            GeminiQualificationResult qualification, Integer searchRunId, UserET author, DateTableET today) {
        ContactExtET ext = new ContactExtET();
        ext.setPmLeafletContactId(contactId);
        ext.setProviderId(person.getId());
        ext.setTitle(cut(person.getTitle(), 255));
        ext.setSeniority(cut(person.getSeniority(), 50));
        ext.setLinkedinUrl(cut(person.getLinkedinUrl(), 500));
        ext.setEmailSource(found.getSource());
        ext.setEmailVerification(cut(found.getVerification(), 30));
        if (qualification != null) {
            ext.setScore(qualification.getScore());
            ext.setScoreReason(cut(qualification.getReason(), MAX_REASON));
            ext.setModel(cut(qualification.getModel(), 100));
            ext.setPromptVersion(cut(qualification.getPromptVersion(), 30));
        }
        ext.setSearchRunId(searchRunId);
        ext.setCreatedBy(author);
        ext.setCreatedDate(today);
        prospectExtDAO.saveContactExt(ext);
    }

    private void saveEvidence(Integer contactId, ApolloPersonDTO person, ApolloOrganizationDTO org, String email,
            String emailSource, UserET author, DateTableET today) {
        Map<String, String> apollo = new LinkedHashMap<>();
        apollo.put(EvidenceFields.FIRST_NAME, person.getFirstName());
        apollo.put(EvidenceFields.TITLE, person.getTitle());
        apollo.put(EvidenceFields.ORGANIZATION_NAME, org.getName());
        apollo.put(EvidenceFields.INDUSTRY, org.getIndustry());
        apollo.put(EvidenceFields.COMPANY_SIZE,
                org.getEstimatedNumEmployees() == null ? null : org.getEstimatedNumEmployees().toString());
        apollo.put(EvidenceFields.CITY, person.getCity());
        apollo.put(EvidenceFields.ORGANIZATION_CITY, org.getCity());

        List<EvidenceET> rows = new ArrayList<>();
        apollo.forEach((field, value) -> {
            if (!blank(value)) {
                rows.add(evidence(contactId, EvidenceFields.SOURCE_APOLLO, field, value,
                        EvidenceFields.TITLE.equals(field) ? person.getLinkedinUrl() : null, author, today));
            }
        });
        String source = EvidenceFields.SOURCE_HUNTER.equals(emailSource) ? EvidenceFields.SOURCE_HUNTER
                : EvidenceFields.SOURCE_APOLLO;
        rows.add(evidence(contactId, source, EvidenceFields.EMAIL, email, null, author, today));
        rows.forEach(prospectExtDAO::saveEvidence);
    }

    private static EvidenceET evidence(Integer contactId, String source, String field, String value, String url,
            UserET author, DateTableET today) {
        EvidenceET e = new EvidenceET();
        e.setPmLeafletContactId(contactId);
        e.setSource(source);
        e.setField(field);
        e.setValue(cut(value.strip(), 1000));
        e.setSourceUrl(cut(url, 500));
        e.setCreatedBy(author);
        e.setCreatedDate(today);
        return e;
    }

    private Integer masterId(Integer tableCode, String code) {
        return masterTableRepository.findFirstByTableCodeAndCode(tableCode, code).map(MasterTableET::getId)
                .orElse(null);
    }

    private static String trimmed(String value) {
        return blank(value) ? null : cut(value.strip(), 255);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
