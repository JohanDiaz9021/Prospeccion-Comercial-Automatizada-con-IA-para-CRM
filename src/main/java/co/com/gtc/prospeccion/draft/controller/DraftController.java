package co.com.gtc.prospeccion.draft.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.draft.dto.request.DiscardDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.DraftPreviewRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.UpdateDraftRequestDTO;
import co.com.gtc.prospeccion.draft.service.ApprovalService;
import co.com.gtc.prospeccion.draft.service.DraftService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/drafts")
@Tag(name = "Borradores")
public class DraftController {

    private final DraftService draftService;
    private final ApprovalService approvalService;

    public DraftController(DraftService draftService, ApprovalService approvalService) {
        this.draftService = draftService;
        this.approvalService = approvalService;
    }

    @GetMapping
    public ResponseEntity<GeneralReponse> list(@RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.list(status, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GeneralReponse> get(@PathVariable Integer id) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.get(id)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_APPROVE)
    @PutMapping("/{id}")
    public ResponseEntity<GeneralReponse> update(@PathVariable Integer id,
            @Valid @RequestBody UpdateDraftRequestDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.update(id, request)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_APPROVE)
    @PostMapping("/{id}/approve")
    @Operation(summary = "Aprueba la última versión y la pone en la cola de envío con su turno (Ley 2300)")
    public ResponseEntity<GeneralReponse> approve(@PathVariable Integer id) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, approvalService.approve(id)));
    }

    @GetMapping("/contacts/{pmLeafletContactId}/versions")
    @Operation(summary = "Historial: todas las versiones del borrador de un contacto, la más nueva primero")
    public ResponseEntity<GeneralReponse> versions(@PathVariable Integer pmLeafletContactId) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.versions(pmLeafletContactId)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_APPROVE)
    @PostMapping("/contacts/{pmLeafletContactId}/generate")
    @Operation(summary = "Redacta con Gemini una versión nueva del borrador del contacto (queda pendiente de revisión)")
    public ResponseEntity<GeneralReponse> generate(@PathVariable Integer pmLeafletContactId) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                draftService.generate(pmLeafletContactId, null)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
    @PostMapping("/preview")
    @Operation(summary = "Prueba de redacción con un contacto inventado. No guarda nada.")
    public ResponseEntity<GeneralReponse> preview(@Valid @RequestBody DraftPreviewRequestDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.preview(request)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_APPROVE)
    @PostMapping("/{id}/discard")
    public ResponseEntity<GeneralReponse> discard(@PathVariable Integer id,
            @Valid @RequestBody DiscardDraftRequestDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, draftService.discard(id, request)));
    }
}
