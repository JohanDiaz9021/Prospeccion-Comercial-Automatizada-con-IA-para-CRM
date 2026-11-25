package co.com.gtc.prospeccion.sending.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import co.com.gtc.prospeccion.sending.schedule.SendSlotPlanner;

/**
 * Reglas que deciden cuándo puede salir un correo (tarea 5.3):
 * Ley 2300 (L-V 7:00-19:00, sáb 8:00-15:00, hora de Colombia, sin domingos ni festivos),
 * límite diario y espaciado mínimo entre envíos (la_tenantconfig) y lista de supresión.
 * Un buzón por tenant: los límites son los de la configuración vigente.
 */
public interface SendGuardService {

    /** Mientras la configuración no lo defina: arranque del calentamiento del buzón (1.12). */
    int DEFAULT_DAILY_LIMIT = 20;
    int DEFAULT_SPACING_SECONDS = 120;

    boolean isWithinLey2300Window(ZonedDateTime now);

    boolean isHoliday(LocalDate date);

    /** DAILY_SEND_LIMIT y MIN_SPACING_SECONDS vigentes, o los valores de arranque si no están. */
    SendSlotPlanner.Limits currentLimits();

    /** Si hoy todavía caben envíos. Con DAILY_SEND_LIMIT = 0 el envío está pausado. */
    boolean hasDailyQuota(ZonedDateTime now);

    boolean isSuppressed(String email);

    /** Correos en cola o enviados en esa fecha (hora de Colombia). */
    int scheduledOn(LocalDate date);

    /** Próximo turno libre para un correo nuevo (UTC), respetando ventana, espaciado y límite diario. */
    LocalDateTime nextAllowedSlotUtc(ZonedDateTime now);

    /** Turnos que tendrían {@code count} correos nuevos aprobados desde {@code from}, sin guardar nada. */
    List<ZonedDateTime> previewSlots(ZonedDateTime from, int count);
}
