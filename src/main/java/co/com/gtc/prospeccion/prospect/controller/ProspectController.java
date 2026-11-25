package co.com.gtc.prospeccion.prospect.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.prospect.service.ProspectQueryService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/prospects")
@Tag(name = "Prospectos")
public class ProspectController {

    private final ProspectQueryService prospectQueryService;

    public ProspectController(ProspectQueryService prospectQueryService) {
        this.prospectQueryService = prospectQueryService;
    }

    @GetMapping
    public ResponseEntity<GeneralReponse> list(
            @RequestParam(required = false) Integer searchRunId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minScore,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS,
                prospectQueryService.list(searchRunId, status, minScore, pageable)));
    }
}
