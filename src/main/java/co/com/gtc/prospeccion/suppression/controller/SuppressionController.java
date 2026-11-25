package co.com.gtc.prospeccion.suppression.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/suppressions")
@Tag(name = "Lista de bloqueo")
public class SuppressionController {

    private final SuppressionService suppressionService;

    public SuppressionController(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    @GetMapping
    @Operation(summary = "Correos a los que nunca se vuelve a escribir (bajas, rebotes duros, quejas), el más reciente primero")
    public ResponseEntity<GeneralReponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, suppressionService.list(pageable)));
    }
}
