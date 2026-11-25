package co.com.gtc.prospeccion.common.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.repository.DateTableRepository;

/**
 * Fila de sy_datetable de hoy, en hora de Colombia, para la auditoría (CREATEDDATE, LASTUPDATEDDATE, APPROVEDDATE).
 * Se busca por la columna DATE y no por DATECHAR, porque el CRM usa dos formatos de DATECHAR
 * (ver docs/crm/peticion-completa-y-dominio-de-prospectos.md). Consulta la base del tenant actual.
 */
@Component
public class DateTableResolver {

    private final DateTableRepository dateTableRepository;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public DateTableResolver(DateTableRepository dateTableRepository, ProspeccionProperties properties) {
        this(dateTableRepository, ZoneId.of(properties.getTimezone()), Clock.systemUTC());
    }

    DateTableResolver(DateTableRepository dateTableRepository, ZoneId zone, Clock clock) {
        this.dateTableRepository = dateTableRepository;
        this.zone = zone;
        this.clock = clock;
    }

    public DateTableET today() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return dateTableRepository.findByDate(today).orElseThrow(() -> new IllegalStateException(
                "sy_datetable no tiene la fecha " + today + ": el calendario del tenant debe incluirla"));
    }

    public Integer resolveTodayId() {
        return today().getId();
    }
}
