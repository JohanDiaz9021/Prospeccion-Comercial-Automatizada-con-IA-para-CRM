package co.com.gtc.prospeccion.credits.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.credits.service.CreditLedgerService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/credits")
@Tag(name = "Créditos")
public class CreditController {

    private final CreditLedgerService creditLedgerService;

    public CreditController(CreditLedgerService creditLedgerService) {
        this.creditLedgerService = creditLedgerService;
    }

    @GetMapping
    public ResponseEntity<GeneralReponse> summary() {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, creditLedgerService.getSummary()));
    }
}
