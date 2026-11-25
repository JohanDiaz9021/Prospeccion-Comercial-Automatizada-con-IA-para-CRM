package co.com.gtc.prospeccion.draft.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.util.HtmlSanitizerUtil;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.mapper.DraftMapper;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.draft.rules.DraftRules;
import co.com.gtc.prospeccion.integration.gemini.dto.DraftContact;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Apoyo de la bandeja de borradores (5.1), compartido por DraftService y ApprovalService:
 * <ul>
 * <li>Completa la respuesta con los datos del contacto (de su evidencia), la versión en texto, las palabras, los
 * avisos de {@link DraftRules} con el mensaje base vigente y, si está aprobado, la hora en que sale.</li>
 * <li>Valida que solo se edite, descarte o apruebe la última versión y mientras esté "Por revisar".</li>
 * </ul>
 * Debe llamarse dentro de una transacción (las relaciones de la entidad son perezosas).
 */
@Component
public class DraftSupport {

    private final DraftDAO draftDAO;
    private final DraftRepository draftRepository;
    private final DraftMapper draftMapper;
    private final EvidenceRepository evidenceRepository;
    private final TenantConfigRepository configRepository;
    private final SendQueueRepository sendQueueRepository;
    private final MasterTableRepository masterTableRepository;
    private final Ley2300Calendar calendar;
    private final ObjectMapper objectMapper;

    public DraftSupport(DraftDAO draftDAO, DraftRepository draftRepository, DraftMapper draftMapper,
            EvidenceRepository evidenceRepository, TenantConfigRepository configRepository,
            SendQueueRepository sendQueueRepository, MasterTableRepository masterTableRepository,
            Ley2300Calendar calendar, ObjectMapper objectMapper) {
        this.draftDAO = draftDAO;
        this.draftRepository = draftRepository;
        this.draftMapper = draftMapper;
        this.evidenceRepository = evidenceRepository;
        this.configRepository = configRepository;
        this.sendQueueRepository = sendQueueRepository;
        this.masterTableRepository = masterTableRepository;
        this.calendar = calendar;
        this.objectMapper = objectMapper;
    }

    // ---------- reglas de la bandeja ----------

    public DraftET find(Integer id) {
        return draftDAO.findById(id)
                .orElseThrow(() -> new NotFoundException(MessageKeys.NOT_FOUND, "No existe el borrador " + id));
    }

    /**
     * Solo se actúa sobre la última versión y mientras esté "Por revisar". Si alguien creó una versión más nueva
     * mientras otro la revisaba: 409 (recargar). Si ya se aprobó, descartó o venció: 400.
     */
    public void requireLatestPending(DraftET draft) {
        DraftET latest = draftDAO.findLatestByContact(draft.getPmLeafletContactId()).orElse(draft);
        if (!latest.getId().equals(draft.getId())) {
            throw new ConflictException(MessageKeys.CONFLICT, "El borrador " + draft.getId()
                    + " ya no es la última versión (la última es la " + latest.getVersion() + ")");
        }
        String code = draft.getStatus() == null ? null : draft.getStatus().getCode();
        if (!DraftStatus.PENDING_REVIEW.equals(code)) {
            throw new BusinessException(MessageKeys.DRAFT_NOT_PENDING,
                    "El borrador " + draft.getId() + " no está por revisar (estado " + code + ")");
        }
    }

    public MasterTableET status(String code) {
        return masterTableRepository.findFirstByTableCodeAndCode(DraftStatus.TABLECODE, code)
                .orElseThrow(() -> new IllegalStateException("Falta el estado de borrador 601/" + code
                        + " en sy_mastertable (V2__catalogos_y_etiquetas.sql)"));
    }

    // ---------- respuesta ----------

    public DraftResponseDTO describe(DraftET draft) {
        return describe(List.of(draft)).get(0);
    }

    /** Completa varias respuestas con pocas consultas (una de evidencia y una de versiones para todas). */
    public List<DraftResponseDTO> describe(List<DraftET> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        Set<Integer> contactIds = drafts.stream().map(DraftET::getPmLeafletContactId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Integer, List<EvidenceET>> evidence = evidenceRepository.findByPmLeafletContactIdIn(contactIds).stream()
                .collect(Collectors.groupingBy(EvidenceET::getPmLeafletContactId));
        Map<Integer, Integer> versions = countVersions(contactIds);
        TenantConfigET config = configRepository.findFirstByFlgActiveTrueOrderByIdDesc().orElse(null);
        String baseMessage = config == null ? null : config.getBaseMessage();
        String company = companyOf(config == null ? null : config.getProfileOfInterest());

        List<DraftResponseDTO> result = new ArrayList<>(drafts.size());
        for (DraftET draft : drafts) {
            DraftResponseDTO dto = draftMapper.toResponse(draft);
            List<EvidenceET> deContacto = evidence.getOrDefault(draft.getPmLeafletContactId(), List.of());
            DraftContact contact = DraftServiceImpl.toContact(deContacto);
            dto.setContactEmail(DraftServiceImpl.emailOf(deContacto));
            dto.setContactName(contact.firstName());
            dto.setContactTitle(contact.title());
            dto.setOrganizationName(contact.organizationName());
            dto.setVersions(versions.getOrDefault(draft.getPmLeafletContactId(), 1));

            String text = HtmlSanitizerUtil.toPlainText(draft.getBody());
            DraftRules.Check check = DraftRules.check(draft.getSubject(), text, baseMessage,
                    knownData(contact, company));
            dto.setBodyText(text);
            dto.setWords(check.words());
            // Quien revisa ve todo: también los errores que en una edición humana no bloquean (por ejemplo, la firma).
            List<String> issues = new ArrayList<>();
            check.errors().forEach(i -> issues.add(i.name()));
            check.warnings().forEach(i -> issues.add(i.name()));
            dto.setWarnings(issues);

            if (DraftStatus.APPROVED.equals(dto.getStatusCode())) {
                sendQueueRepository.findByDraftId(draft.getId())
                        .ifPresent(q -> dto.setScheduledAt(local(q.getScheduledAt())));
            }
            result.add(dto);
        }
        return result;
    }

    /** Lo que Gemini recibe además del mensaje base: así DraftRules sabe qué números vienen en los datos. */
    String knownData(DraftContact contact, String company) {
        StringBuilder data = new StringBuilder();
        try {
            data.append(objectMapper.writeValueAsString(contact));
        } catch (JsonProcessingException e) {
            // DraftContact siempre se serializa; si no, no aporta datos.
        }
        if (company != null) {
            data.append('\n').append(company);
        }
        return data.toString();
    }

    /** La parte company del perfil como texto, o null. */
    String companyOf(String profileOfInterest) {
        if (profileOfInterest == null || profileOfInterest.isBlank()) {
            return null;
        }
        try {
            JsonNode company = objectMapper.readTree(profileOfInterest).path("company");
            return company.isMissingNode() ? null : company.toString();
        } catch (JsonProcessingException e) {
            return null; // perfil dañado: GeminiClient tampoco lo envía
        }
    }

    private Map<Integer, Integer> countVersions(Collection<Integer> contactIds) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Object[] row : draftRepository.countVersions(contactIds)) {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    private LocalDateTime local(LocalDateTime utc) {
        return utc == null ? null : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(calendar.zone()).toLocalDateTime();
    }
}
