package co.com.gtc.prospeccion.dev.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.search.service.ProspectSearchService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * <b>Solo perfil local.</b> Herramientas para probar la búsqueda y las campañas (3.2) sin esperar al job de las 6:00.
 */
@Profile("local")
@RestController
@PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
@RequestMapping("/api/dev/search")
@Tag(name = "Herramientas locales")
public class DevSearchController {

    private final ProspectSearchService prospectSearchService;
    private final JdbcTemplate jdbc;

    public DevSearchController(ProspectSearchService prospectSearchService, JdbcTemplate jdbc) {
        this.prospectSearchService = prospectSearchService;
        this.jdbc = jdbc;
    }

    @PostMapping("/run-scheduled")
    @Operation(summary = "Repite ahora las campañas activas, les toque o no (force=false: solo las que tocan hoy, como el job)")
    public ResponseEntity<GeneralReponse> runScheduled(@RequestParam(defaultValue = "true") boolean force) {
        int executed = prospectSearchService.rerunScheduledSearches(force);
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, Map.of("campaignsExecuted", executed)));
    }

    @DeleteMapping("/data")
    @Transactional
    @Operation(summary = "Borra las búsquedas y todo lo que crearon: contactos, evidencia, borradores, turnos y memoria de Apollo")
    public ResponseEntity<GeneralReponse> deleteSearchData() {
        List<Integer> contacts = jdbc.queryForList(
                "SELECT PM_LEAFLETCONTACTID FROM la_contactext WHERE SEARCHRUNID IS NOT NULL", Integer.class);
        List<Integer> leaflets = contacts.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT DISTINCT PM_LEAFLETID FROM pm_leafletcontact WHERE ID IN (" + marks(contacts) + ")",
                Integer.class, contacts.toArray());

        Map<String, Integer> deleted = new LinkedHashMap<>();
        if (!contacts.isEmpty()) {
            String in = "IN (" + marks(contacts) + ")";
            Object[] ids = contacts.toArray();
            String sendsOfContacts = "JOIN la_sendqueue q ON q.ID = %s JOIN la_draft d ON d.ID = q.DRAFTID "
                    + "WHERE d.PM_LEAFLETCONTACTID " + in;
            deleted.put("suppressions", jdbc.update("DELETE s FROM la_suppression s "
                    + sendsOfContacts.formatted("s.SENDQUEUEID"), ids));
            deleted.put("events", jdbc.update("DELETE e FROM la_emailevent e "
                    + sendsOfContacts.formatted("e.SENDQUEUEID"), ids));
            deleted.put("sendQueue", jdbc.update("DELETE q FROM la_sendqueue q JOIN la_draft d ON d.ID = q.DRAFTID "
                    + "WHERE d.PM_LEAFLETCONTACTID " + in, ids));
            deleted.put("drafts", jdbc.update("DELETE FROM la_draft WHERE PM_LEAFLETCONTACTID " + in, ids));
            deleted.put("evidence", jdbc.update("DELETE FROM la_evidence WHERE PM_LEAFLETCONTACTID " + in, ids));
            jdbc.update("UPDATE la_creditledger SET PM_LEAFLETCONTACTID = NULL WHERE PM_LEAFLETCONTACTID " + in, ids);
            deleted.put("contactExt", jdbc.update("DELETE FROM la_contactext WHERE PM_LEAFLETCONTACTID " + in, ids));
            jdbc.update("UPDATE la_apolloseen SET PM_LEAFLETCONTACTID = NULL WHERE PM_LEAFLETCONTACTID " + in, ids);
            deleted.put("contactMediums", jdbc.update(
                    "DELETE FROM pm_leafletcontactmedium WHERE PM_LEAFLETCONTACTID " + in, ids));
            deleted.put("contacts", jdbc.update("DELETE FROM pm_leafletcontact WHERE ID " + in, ids));
        }
        if (!leaflets.isEmpty()) {
            String in = "IN (" + marks(leaflets) + ")";
            Object[] ids = leaflets.toArray();
            deleted.put("leafletExt", jdbc.update("DELETE FROM la_leafletext WHERE PM_LEAFLETID " + in, ids));
            deleted.put("prospects", jdbc.update("DELETE FROM pm_leaflet WHERE ID " + in
                    + " AND NOT EXISTS (SELECT 1 FROM pm_leafletcontact c WHERE c.PM_LEAFLETID = pm_leaflet.ID)", ids));
        }
        deleted.put("apolloSeen", jdbc.update("DELETE FROM la_apolloseen"));
        // El gasto de créditos se conserva (historial); solo se suelta de las búsquedas borradas.
        jdbc.update("UPDATE la_creditledger SET SEARCHRUNID = NULL WHERE SEARCHRUNID IS NOT NULL");
        jdbc.update("UPDATE la_contactext SET SEARCHRUNID = NULL WHERE SEARCHRUNID IS NOT NULL");
        jdbc.update("UPDATE la_searchrun SET PARENT_SEARCHRUNID = NULL");
        deleted.put("searchRuns", jdbc.update("DELETE FROM la_searchrun"));
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, deleted));
    }

    private static String marks(List<?> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }
}
