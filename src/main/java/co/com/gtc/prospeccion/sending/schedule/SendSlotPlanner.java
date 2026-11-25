package co.com.gtc.prospeccion.sending.schedule;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Calcula a qué hora sale cada correo de la cola (tarea 5.3):
 * <ol>
 * <li>Nunca antes de {@code último programado + espaciado} (MIN_SPACING_SECONDS).</li>
 * <li>Siempre dentro de la ventana de la Ley 2300 ({@link Ley2300Calendar}); si cae fuera, pasa a la siguiente
 * apertura.</li>
 * <li>Si ese día ya tiene DAILY_SEND_LIMIT correos programados, pasa al siguiente día hábil.</li>
 * </ol>
 * No guarda nada: con los programados de cada día que le pasan, devuelve el siguiente turno libre.
 */
public final class SendSlotPlanner {

    /** Seguro contra ciclos infinitos (con el límite diario, cada vuelta salta al menos un día). */
    static final int MAX_ITERATIONS = 400;

    /**
     * @param dailyLimit     correos por día; 0 o menos = sin límite al programar (el despachador no envía con 0)
     * @param spacingSeconds segundos mínimos entre dos correos
     */
    public record Limits(int dailyLimit, int spacingSeconds) {
    }

    private SendSlotPlanner() {
    }

    /**
     * @param from           desde cuándo (normalmente ahora)
     * @param lastScheduled  último correo programado o enviado, o null
     * @param scheduledOn    cuántos correos hay programados o enviados en cada fecha (hora de Colombia)
     */
    public static ZonedDateTime next(Ley2300Calendar calendar, ZonedDateTime from, ZonedDateTime lastScheduled,
            Limits limits, ToIntFunction<LocalDate> scheduledOn) {
        ZonedDateTime candidate = from;
        if (lastScheduled != null) {
            ZonedDateTime spaced = lastScheduled.plusSeconds(Math.max(0, limits.spacingSeconds()));
            if (spaced.isAfter(candidate)) {
                candidate = spaced;
            }
        }
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            candidate = calendar.nextAllowed(candidate);
            LocalDate date = candidate.toLocalDate();
            if (limits.dailyLimit() <= 0 || scheduledOn.applyAsInt(date) < limits.dailyLimit()) {
                return candidate;
            }
            candidate = date.plusDays(1).atStartOfDay(calendar.zone());
        }
        throw new IllegalStateException("No se encontró un turno de envío en " + MAX_ITERATIONS + " intentos");
    }

    /** Los próximos {@code count} turnos si se encolaran {@code count} correos uno tras otro desde {@code from}. */
    public static List<ZonedDateTime> plan(Ley2300Calendar calendar, ZonedDateTime from, ZonedDateTime lastScheduled,
            Limits limits, ToIntFunction<LocalDate> scheduledOn, int count) {
        Map<LocalDate, Integer> extra = new HashMap<>();
        List<ZonedDateTime> slots = new ArrayList<>(count);
        ZonedDateTime last = lastScheduled;
        for (int i = 0; i < count; i++) {
            ZonedDateTime slot = next(calendar, from, last,
                    limits, date -> scheduledOn.applyAsInt(date) + extra.getOrDefault(date, 0));
            extra.merge(slot.toLocalDate(), 1, Integer::sum);
            slots.add(slot);
            last = slot;
        }
        return slots;
    }
}
