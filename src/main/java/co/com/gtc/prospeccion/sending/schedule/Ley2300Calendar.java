package co.com.gtc.prospeccion.sending.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.config.PropertiesConfig.Ley2300;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.Window;

/**
 * Horario en que la Ley 2300 de 2023 permite contactar: lunes a viernes de 7:00 a 19:00 y sábados de 8:00 a 15:00,
 * hora de Colombia, sin domingos ni festivos. Las ventanas salen de prospeccion.ley2300 y la zona de
 * prospeccion.timezone. El inicio está incluido y el fin no (a las 19:00 en punto ya no se envía).
 */
@Component
public class Ley2300Calendar {

    /** Hasta dónde se busca el siguiente día hábil (más que suficiente: el puente más largo es de 4 días). */
    static final int MAX_DAYS_AHEAD = 30;

    /** Horario de un día en que se puede enviar. */
    public record DayWindow(LocalDate date, LocalTime start, LocalTime end) {
    }

    private final ZoneId zone;
    private final DayWindow weekdays;
    private final DayWindow saturday;
    private final boolean sundaysAllowed;
    private final boolean holidaysAllowed;
    private final Set<LocalDate> extraHolidays;

    public Ley2300Calendar(ProspeccionProperties properties) {
        Ley2300 ley = properties.getLey2300();
        this.zone = ZoneId.of(properties.getTimezone());
        this.weekdays = window(ley.getWeekdays(), "07:00", "19:00");
        this.saturday = window(ley.getSaturday(), "08:00", "15:00");
        this.sundaysAllowed = ley.isSundaysAllowed();
        this.holidaysAllowed = ley.isHolidaysAllowed();
        this.extraHolidays = new HashSet<>(ley.getExtraHolidays());
    }

    public ZoneId zone() {
        return zone;
    }

    /** Festivo de ley o día no hábil extraordinario. */
    public boolean isHoliday(LocalDate date) {
        return ColombianHolidays.isHoliday(date) || extraHolidays.contains(date);
    }

    /** Horario de ese día, o vacío si ese día no se puede enviar (domingo o festivo). */
    public Optional<DayWindow> windowOn(LocalDate date) {
        if (!holidaysAllowed && isHoliday(date)) {
            return Optional.empty();
        }
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SUNDAY) {
            return sundaysAllowed ? Optional.of(new DayWindow(date, saturday.start(), saturday.end())) : Optional.empty();
        }
        DayWindow base = day == DayOfWeek.SATURDAY ? saturday : weekdays;
        return Optional.of(new DayWindow(date, base.start(), base.end()));
    }

    /** Si en ese instante se puede enviar. */
    public boolean isAllowed(ZonedDateTime instant) {
        ZonedDateTime local = instant.withZoneSameInstant(zone);
        LocalTime time = local.toLocalTime();
        return windowOn(local.toLocalDate())
                .filter(w -> !time.isBefore(w.start()) && time.isBefore(w.end()))
                .isPresent();
    }

    /** El mismo instante si está permitido; si no, la apertura de la siguiente ventana. En hora de Colombia. */
    public ZonedDateTime nextAllowed(ZonedDateTime from) {
        ZonedDateTime local = from.withZoneSameInstant(zone);
        if (isAllowed(local)) {
            return local;
        }
        LocalDate date = local.toLocalDate();
        Optional<DayWindow> today = windowOn(date);
        if (today.isPresent() && local.toLocalTime().isBefore(today.get().start())) {
            return date.atTime(today.get().start()).atZone(zone);
        }
        for (int i = 1; i <= MAX_DAYS_AHEAD; i++) {
            Optional<DayWindow> window = windowOn(date.plusDays(i));
            if (window.isPresent()) {
                return window.get().date().atTime(window.get().start()).atZone(zone);
            }
        }
        throw new IllegalStateException("No hay ningún día hábil para enviar en los próximos " + MAX_DAYS_AHEAD
                + " días: revise prospeccion.ley2300");
    }

    private static DayWindow window(Window window, String defaultStart, String defaultEnd) {
        LocalTime start = LocalTime.parse(window == null || window.getStart() == null ? defaultStart : window.getStart());
        LocalTime end = LocalTime.parse(window == null || window.getEnd() == null ? defaultEnd : window.getEnd());
        if (!end.isAfter(start)) {
            throw new IllegalStateException("prospeccion.ley2300: el fin (" + end + ") debe ser después del inicio (" + start + ")");
        }
        return new DayWindow(null, start, end);
    }
}
