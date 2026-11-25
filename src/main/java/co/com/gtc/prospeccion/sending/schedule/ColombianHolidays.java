package co.com.gtc.prospeccion.sending.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Festivos de Colombia calculados para cualquier año (Ley 51 de 1983, "Ley Emiliani"):
 * <ul>
 * <li>Fijos: 1 de enero, 1 de mayo, 20 de julio, 7 de agosto, 8 y 25 de diciembre.</li>
 * <li>Trasladables al lunes siguiente: Reyes, San José, San Pedro y San Pablo, Asunción, Día de la Raza, Todos los
 * Santos e Independencia de Cartagena.</li>
 * <li>Según la Pascua: Jueves y Viernes Santo (fijos), Ascensión, Corpus Christi y Sagrado Corazón (en lunes).</li>
 * </ul>
 * Un festivo extraordinario decretado por el Gobierno se agrega con prospeccion.ley2300.extra-holidays.
 */
public final class ColombianHolidays {

    private static final Map<Integer, Map<LocalDate, String>> CACHE = new ConcurrentHashMap<>();

    private ColombianHolidays() {
    }

    /** Festivos del año con su nombre, en orden de fecha. */
    public static Map<LocalDate, String> forYear(int year) {
        return CACHE.computeIfAbsent(year, ColombianHolidays::calculate);
    }

    public static boolean isHoliday(LocalDate date) {
        return forYear(date.getYear()).containsKey(date);
    }

    private static Map<LocalDate, String> calculate(int year) {
        Map<LocalDate, String> dias = new TreeMap<>();
        fijo(dias, year, 1, 1, "Año Nuevo");
        fijo(dias, year, 5, 1, "Día del Trabajo");
        fijo(dias, year, 7, 20, "Día de la Independencia");
        fijo(dias, year, 8, 7, "Batalla de Boyacá");
        fijo(dias, year, 12, 8, "Inmaculada Concepción");
        fijo(dias, year, 12, 25, "Navidad");

        lunes(dias, LocalDate.of(year, 1, 6), "Reyes Magos");
        lunes(dias, LocalDate.of(year, 3, 19), "San José");
        lunes(dias, LocalDate.of(year, 6, 29), "San Pedro y San Pablo");
        lunes(dias, LocalDate.of(year, 8, 15), "Asunción de la Virgen");
        lunes(dias, LocalDate.of(year, 10, 12), "Día de la Raza");
        lunes(dias, LocalDate.of(year, 11, 1), "Todos los Santos");
        lunes(dias, LocalDate.of(year, 11, 11), "Independencia de Cartagena");

        LocalDate pascua = easterSunday(year);
        dias.put(pascua.minusDays(3), "Jueves Santo");
        dias.put(pascua.minusDays(2), "Viernes Santo");
        dias.put(pascua.plusDays(43), "Ascensión del Señor");
        dias.put(pascua.plusDays(64), "Corpus Christi");
        dias.put(pascua.plusDays(71), "Sagrado Corazón");
        return Collections.unmodifiableMap(dias);
    }

    private static void fijo(Map<LocalDate, String> dias, int year, int month, int day, String nombre) {
        dias.put(MonthDay.of(month, day).atYear(year), nombre);
    }

    /** Si no cae en lunes, se corre al lunes siguiente. */
    private static void lunes(Map<LocalDate, String> dias, LocalDate fecha, String nombre) {
        dias.put(fecha.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)), nombre);
    }

    /** Domingo de Pascua (algoritmo anónimo gregoriano de Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
