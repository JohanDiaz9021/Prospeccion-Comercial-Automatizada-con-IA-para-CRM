package co.com.gtc.prospeccion.sending.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.sending.schedule.ColombianHolidays;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.sending.service.SendQueueService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/sending")
@Tag(name = "Cola de envío")
public class SendingController {

    private static final Locale ES = Locale.forLanguageTag("es-CO");

    private final SendQueueService queueService;
    private final SendGuardService guard;
    private final Ley2300Calendar calendar;

    public SendingController(SendQueueService queueService, SendGuardService guard, Ley2300Calendar calendar) {
        this.queueService = queueService;
        this.guard = guard;
        this.calendar = calendar;
    }

    /** Un turno de envío en hora de Colombia. */
    public record SlotDTO(int position, LocalDateTime at, String dayOfWeek) {
    }

    public record HolidayDTO(LocalDate date, String name, String dayOfWeek) {
    }

    @GetMapping("/status")
    @Operation(summary = "Estado de la cola y de la ventana de la Ley 2300")
    public ResponseEntity<GeneralReponse> status() {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, queueService.status()));
    }

    @GetMapping("/preview")
    @Operation(summary = "Turnos que tendrían N correos aprobados ahora (o desde 'from', hora de Colombia). No guarda nada.")
    public ResponseEntity<GeneralReponse> preview(@RequestParam(defaultValue = "10") @Min(1) @Max(200) int count,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from) {
        ZonedDateTime desde = from == null ? ZonedDateTime.now(calendar.zone()) : from.atZone(calendar.zone());
        List<SlotDTO> slots = new ArrayList<>();
        List<ZonedDateTime> turnos = guard.previewSlots(desde, count);
        for (int i = 0; i < turnos.size(); i++) {
            ZonedDateTime t = turnos.get(i);
            slots.add(new SlotDTO(i + 1, t.toLocalDateTime(), t.getDayOfWeek().getDisplayName(TextStyle.FULL, ES)));
        }
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, slots));
    }

    @GetMapping("/holidays")
    @Operation(summary = "Festivos de Colombia del año (no se envía en ellos)")
    public ResponseEntity<GeneralReponse> holidays(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        int anio = year == null ? LocalDate.now(calendar.zone()).getYear() : year;
        List<HolidayDTO> dias = ColombianHolidays.forYear(anio).entrySet().stream()
                .map(e -> new HolidayDTO(e.getKey(), e.getValue(),
                        e.getKey().getDayOfWeek().getDisplayName(TextStyle.FULL, ES)))
                .toList();
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, dias));
    }
}
