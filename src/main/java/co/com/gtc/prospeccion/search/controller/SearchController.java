package co.com.gtc.prospeccion.search.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.search.dto.request.InterpretSearchRequestDTO;
import co.com.gtc.prospeccion.search.dto.request.RunSearchRequestDTO;
import co.com.gtc.prospeccion.search.service.ProspectSearchService;
import co.com.gtc.prospeccion.search.service.SearchInterpretService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/search")
@Tag(name = "Búsqueda")
public class SearchController {

    private final SearchInterpretService searchInterpretService;
    private final ProspectSearchService prospectSearchService;

    public SearchController(SearchInterpretService searchInterpretService,
            ProspectSearchService prospectSearchService) {
        this.searchInterpretService = searchInterpretService;
        this.prospectSearchService = prospectSearchService;
    }

    /** Activa o pausa una campaña; frequency (DAILY o WEEKLY) es opcional. */
    public record ScheduleRequest(@NotNull Boolean scheduled, @Pattern(regexp = "DAILY|WEEKLY") String frequency) {
    }

    @PreAuthorize(ProspeccionOperations.CAN_RUN)
    @PostMapping("/interpret")
    @Operation(summary = "Convierte la búsqueda en lenguaje natural en filtros de Apollo (Gemini, sin créditos de Apollo)")
    public ResponseEntity<GeneralReponse> interpret(@Valid @RequestBody InterpretSearchRequestDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, searchInterpretService.interpret(request)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_RUN)
    @PostMapping("/preview")
    @Operation(summary = "Total de Apollo y una muestra con los filtros (0 créditos, no crea nada)")
    public ResponseEntity<GeneralReponse> preview(@Valid @RequestBody RunSearchRequestDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, prospectSearchService.preview(request)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_RUN)
    @PostMapping("/run")
    @Operation(summary = "Guarda la búsqueda y la ejecuta en segundo plano (gasta créditos al conseguir correos)")
    public ResponseEntity<GeneralReponse> run(@Valid @RequestBody RunSearchRequestDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, prospectSearchService.run(request)));
    }

    @GetMapping("/runs")
    @Operation(summary = "Historial de búsquedas, la más reciente primero, con su embudo")
    public ResponseEntity<GeneralReponse> runs(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, prospectSearchService.listRuns(pageable)));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Estado y embudo de una búsqueda (para seguir su avance)")
    public ResponseEntity<GeneralReponse> getRun(@PathVariable Integer id) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, prospectSearchService.getRun(id)));
    }

    @GetMapping("/runs/{id}/results")
    @Operation(summary = "Qué pasó con cada persona nueva de la búsqueda")
    public ResponseEntity<GeneralReponse> results(@PathVariable Integer id) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, prospectSearchService.runResults(id)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_RUN)
    @PatchMapping("/runs/{id}/schedule")
    @Operation(summary = "Activa o pausa la campaña de una búsqueda y cambia su frecuencia (diaria o semanal)")
    public ResponseEntity<GeneralReponse> schedule(@PathVariable Integer id,
            @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                prospectSearchService.setScheduled(id, request.scheduled(), request.frequency())));
    }
}
