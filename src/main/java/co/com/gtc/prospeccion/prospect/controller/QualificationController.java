package co.com.gtc.prospeccion.prospect.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.prospect.dto.request.QualificationPreviewRequest;
import co.com.gtc.prospeccion.prospect.service.QualificationService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
@RequestMapping("/api/qualification")
@Tag(name = "Calificación")
public class QualificationController {

    private final QualificationService qualificationService;

    public QualificationController(QualificationService qualificationService) {
        this.qualificationService = qualificationService;
    }

    @PostMapping("/preview")
    @Operation(summary = "Prueba de calificación con contactos inventados (máximo 10). No guarda nada.")
    public ResponseEntity<GeneralReponse> preview(@Valid @RequestBody QualificationPreviewRequest request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, qualificationService.preview(request)));
    }
}
