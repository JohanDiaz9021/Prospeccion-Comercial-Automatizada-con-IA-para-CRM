package co.com.gtc.prospeccion.dev.controller;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.draft.service.DraftService;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Datos de prueba para la bandeja de borradores (5.1) mientras no existe la búsqueda (3.2) que crea los contactos.
 * <b>Solo perfil local.</b> Crea un prospecto y un contacto INVENTADOS en la base local (pm_leaflet con nombre que
 * empieza por "[DEMO]"), su evidencia (SOURCE = DEMO) y le redacta el borrador con Gemini. Borrar solo toca lo que
 * empieza por "[DEMO]".
 */
@Profile("local")
@RestController
@PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
@RequestMapping("/api/dev/drafts")
@Tag(name = "Herramientas locales")
public class DevDraftsController {

    static final String MARCA = "[DEMO] ";
    static final String SOURCE_DEMO = "DEMO";

    private final JdbcTemplate jdbc;
    private final DraftService draftService;
    private final DateTableResolver dateTableResolver;
    private final CurrentUserProvider currentUserProvider;
    private final SendQueueRepository sendQueueRepository;
    private final UnsubscribeTokenService tokenService;

    public DevDraftsController(JdbcTemplate jdbc, DraftService draftService, DateTableResolver dateTableResolver,
            CurrentUserProvider currentUserProvider, SendQueueRepository sendQueueRepository,
            UnsubscribeTokenService tokenService) {
        this.sendQueueRepository = sendQueueRepository;
        this.tokenService = tokenService;
        this.jdbc = jdbc;
        this.draftService = draftService;
        this.dateTableResolver = dateTableResolver;
        this.currentUserProvider = currentUserProvider;
    }

    /** Contacto inventado: los datos que daría Apollo más el correo que daría la 4.1. */
    public record DemoRequest(
            @Size(max = 100) String firstName,
            @Size(max = 200) String title,
            @Size(max = 200) String organizationName,
            @Size(max = 150) String industry,
            @Size(max = 30) String companySize,
            @Size(max = 150) String city,
            @Email @Size(max = 320) String email) {
    }

    @PostMapping("/demo")
    @Operation(summary = "Crea un contacto [DEMO] inventado y le redacta el borrador con Gemini (solo datos inventados)")
    public ResponseEntity<GeneralReponse> createDemo(@Valid @RequestBody DemoRequest contact) {
        if (blank(contact.organizationName()) && blank(contact.firstName())) {
            throw new BusinessException(MessageKeys.VALIDATION_ERROR, "Falta al menos el nombre o la empresa");
        }
        String empresa = blank(contact.organizationName()) ? "Empresa Ficticia" : contact.organizationName().strip();
        Integer hoy = dateTableResolver.resolveTodayId();
        Integer usuario = currentUserProvider.getCurrentUserId();

        int leafletId = insert("INSERT INTO pm_leaflet (FIRSTNAME) VALUES (?)", MARCA + empresa);
        int contactId = insert("INSERT INTO pm_leafletcontact (PM_LEAFLETID, NAME, POSITION) VALUES (?, ?, ?)",
                leafletId, contact.firstName(), contact.title());
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put(EvidenceFields.FIRST_NAME, contact.firstName());
        campos.put(EvidenceFields.TITLE, contact.title());
        campos.put(EvidenceFields.ORGANIZATION_NAME, empresa);
        campos.put(EvidenceFields.INDUSTRY, contact.industry());
        campos.put(EvidenceFields.COMPANY_SIZE, contact.companySize());
        campos.put(EvidenceFields.CITY, contact.city());
        campos.put(EvidenceFields.EMAIL, contact.email() == null ? null : contact.email().toLowerCase(Locale.ROOT));
        campos.forEach((campo, valor) -> {
            if (!blank(valor)) {
                jdbc.update("INSERT INTO la_evidence (PM_LEAFLETCONTACTID, SOURCE, FIELD, VALUE, CREATEDDATE, CREATEDBY) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", contactId, SOURCE_DEMO, campo, valor.strip(), hoy, usuario);
            }
        });
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.generate(contactId, null)));
    }

    @GetMapping("/{draftId}/unsubscribe-link")
    @Operation(summary = "Enlace de baja del envío de un borrador aprobado (para probar la página pública)")
    public ResponseEntity<GeneralReponse> unsubscribeLink(@PathVariable Integer draftId) {
        Integer sendId = sendQueueRepository.findByDraftId(draftId).map(SendQueueET::getId)
                .orElseThrow(() -> new NotFoundException(MessageKeys.NOT_FOUND,
                        "El borrador " + draftId + " no está en la cola de envío"));
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, Map.of("url", tokenService.createUrl(sendId))));
    }

    @DeleteMapping("/demo")
    @Operation(summary = "Borra los contactos [DEMO] con sus borradores, evidencia y turnos de envío")
    public ResponseEntity<GeneralReponse> deleteDemo() {
        String demo = "JOIN pm_leafletcontact c ON c.ID = %s JOIN pm_leaflet l ON l.ID = c.PM_LEAFLETID "
                + "WHERE l.FIRSTNAME LIKE '[DEMO]%%'";
        Map<String, Integer> borrados = new LinkedHashMap<>();
        String envioDemo = "JOIN la_sendqueue q ON q.ID = %s JOIN la_draft d ON d.ID = q.DRAFTID "
                + "JOIN pm_leafletcontact c ON c.ID = d.PM_LEAFLETCONTACTID JOIN pm_leaflet l ON l.ID = c.PM_LEAFLETID "
                + "WHERE l.FIRSTNAME LIKE '[DEMO]%%'";
        borrados.put("suppressions", jdbc.update("DELETE s FROM la_suppression s " + envioDemo.formatted("s.SENDQUEUEID")));
        borrados.put("events", jdbc.update("DELETE e FROM la_emailevent e " + envioDemo.formatted("e.SENDQUEUEID")));
        borrados.put("sendQueue", jdbc.update("DELETE q FROM la_sendqueue q JOIN la_draft d ON d.ID = q.DRAFTID "
                + demo.formatted("d.PM_LEAFLETCONTACTID")));
        borrados.put("drafts", jdbc.update("DELETE d FROM la_draft d " + demo.formatted("d.PM_LEAFLETCONTACTID")));
        borrados.put("evidence", jdbc.update("DELETE e FROM la_evidence e " + demo.formatted("e.PM_LEAFLETCONTACTID")));
        borrados.put("contacts", jdbc.update("DELETE c FROM pm_leafletcontact c JOIN pm_leaflet l ON l.ID = c.PM_LEAFLETID "
                + "WHERE l.FIRSTNAME LIKE '[DEMO]%'"));
        borrados.put("prospects", jdbc.update("DELETE FROM pm_leaflet WHERE FIRSTNAME LIKE '[DEMO]%'"));
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, borrados));
    }

    private int insert(String sql, Object... params) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().intValue();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
