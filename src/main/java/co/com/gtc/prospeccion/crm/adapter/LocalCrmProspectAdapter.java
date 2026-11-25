package co.com.gtc.prospeccion.crm.adapter;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.CrmTask;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.dto.CreateTaskRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullContactPersonRequestDTO;
import co.com.gtc.prospeccion.crm.dto.FullProspectRequestDTO;
import co.com.gtc.prospeccion.crm.dto.LeafletContactMediumRequestDTO;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort;
import co.com.gtc.prospeccion.crm.repository.DateTableRepository;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;

/**
 * <b>Solo perfil local.</b> Crea el prospecto directo en las tablas pm_* de la base LOCAL (Docker), en lugar de
 * llamar a msa-cotizaciones, que en desarrollo apunta a la base compartida. Así la búsqueda (3.2) se prueba de punta a
 * punta sin tocar datos de nadie. Escribe lo mismo que register-complete-prospect: pm_leaflet, pm_leafletcontact y
 * pm_leafletcontactmedium (sin listas de contactos). Para la 5.6 crea la tarea como task/create-by-user-id: pm_task
 * pendiente y pm_taskleaflet con el prospecto.
 * <p>
 * Se niega a escribir si la base del tenant local no está en localhost.
 */
@Profile("local")
@Primary
@Component
public class LocalCrmProspectAdapter implements CrmProspectPort {

    private final JdbcTemplate jdbc;
    private final DateTableResolver dateTableResolver;
    private final DateTableRepository dateTableRepository;
    private final MasterTableRepository masterTableRepository;
    private final String localUrl;

    public LocalCrmProspectAdapter(JdbcTemplate jdbc, DateTableResolver dateTableResolver,
            DateTableRepository dateTableRepository, MasterTableRepository masterTableRepository,
            @Value("${prospeccion.local-tenant.url:}") String localUrl) {
        this.jdbc = jdbc;
        this.dateTableResolver = dateTableResolver;
        this.dateTableRepository = dateTableRepository;
        this.masterTableRepository = masterTableRepository;
        this.localUrl = localUrl;
    }

    @Override
    public CreatedProspect createProspect(FullProspectRequestDTO request, Integer userId) {
        requireLocalDatabase();
        Integer today = dateTableResolver.resolveTodayId();
        var leaflet = request.getLeaflet();
        var contact = request.getContact();
        int leafletId = insert("INSERT INTO pm_leaflet (FIRSTNAME, MT_STATUSID, INTEREST, CREATEDBY, CREATEDDATE) "
                + "VALUES (?, ?, ?, ?, ?)", leaflet.getFirstName(), leaflet.getMtStatusId(), leaflet.getInterest(),
                userId, today);
        int contactId = insert("INSERT INTO pm_leafletcontact (PM_LEAFLETID, NAME, SURNAME, POSITION, USERFLG, "
                + "CREATEDBY, CREATEDDATE) VALUES (?, ?, ?, ?, ?, ?, ?)", leafletId, contact.getName(),
                contact.getSurname(), contact.getPosition(), contact.getUserFlg(), userId, today);
        if (request.getContactMediumList() != null) {
            for (LeafletContactMediumRequestDTO medium : request.getContactMediumList()) {
                insert("INSERT INTO pm_leafletcontactmedium (VALUE, PM_LEAFLETCONTACTID, PM_LEAFLETID, "
                        + "MT_CONTACTMEDIUMID, CREATEDBY, CREATEDDATE) VALUES (?, ?, ?, ?, ?, ?)", medium.getValue(),
                        contactId, leafletId, medium.getMtContactProviderETId(), userId, today);
            }
        }
        return new CreatedProspect(leafletId, contactId);
    }

    @Override
    public Integer createContact(FullContactPersonRequestDTO request, String userLogin) {
        throw new UnsupportedOperationException("En local la búsqueda solo crea prospectos completos");
    }

    @Override
    public Long createTask(CreateTaskRequestDTO request, Integer userId) {
        requireLocalDatabase();
        Integer today = dateTableResolver.resolveTodayId();
        // Igual que msa-cotizaciones: la fecha llega como yyyy-MM-dd y la hora como HH:mm; la tarea nace pendiente.
        Integer expiration = dateTableRepository.findByDate(LocalDate.parse(request.getExpirationDate()))
                .map(DateTableET::getId)
                .orElseThrow(() -> new IllegalStateException("sy_datetable no tiene la fecha " + request.getExpirationDate()));
        Integer pending = masterTableRepository.findFirstByTableCodeAndCode(CrmTask.STATUS_TABLECODE, CrmTask.STATUS_PENDING)
                .map(MasterTableET::getId)
                .orElseThrow(() -> new IllegalStateException("Falta el estado de tarea 251/1 en sy_mastertable"));
        int taskId = insert("INSERT INTO pm_task (NAME, MT_TASKTYPEID, EXPIRATIONDATE, EXPIRATIONHOUR, MT_PRIORITYID, "
                + "FLGREMEMBER, MT_PERIODICITYID, DURATION, OBSERVATION, MT_STATUSID, CREATEDBY, CREATEDDATE) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", request.getName(), request.getMtTaskTypeId(), expiration,
                LocalTime.parse(request.getExpirationHour()), request.getMtPriority(), request.getFlgRemember(),
                request.getMtPeriodicity(), request.getDuration(), request.getObservation(), pending, userId, today);
        if (request.getLeafletId() != null) {
            insert("INSERT INTO pm_taskleaflet (PM_TASKID, PM_LEAFLETID, CREATEDBY, CREATEDDATE) VALUES (?, ?, ?, ?)",
                    taskId, request.getLeafletId(), userId, today);
        }
        return (long) taskId;
    }

    private void requireLocalDatabase() {
        if (!localUrl.contains("//localhost:") && !localUrl.contains("//127.0.0.1:")) {
            throw new IllegalStateException(
                    "El adaptador local del CRM solo escribe en una base en localhost (prospeccion.local-tenant.url)");
        }
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
}
