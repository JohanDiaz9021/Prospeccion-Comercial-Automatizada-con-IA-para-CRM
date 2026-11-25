package co.com.gtc.prospeccion.tenantconfig.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.response.GeneralReponse;
import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigDTO;
import co.com.gtc.prospeccion.tenantconfig.service.TenantConfigService;
import co.com.gtc.prospeccion.security.ProspeccionOperations;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@PreAuthorize(ProspeccionOperations.CAN_VIEW)
@RequestMapping("/api/config")
@Tag(name = "Configuración")
public class ConfigController {

    private final TenantConfigService tenantConfigService;

    public ConfigController(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    @GetMapping
    public ResponseEntity<GeneralReponse> get() {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, tenantConfigService.get()));
    }

    /** Historial de versiones (la más nueva primero) con quién y cuándo guardó cada una. */
    @GetMapping("/versions")
    public ResponseEntity<GeneralReponse> versions(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, tenantConfigService.versions(pageable)));
    }

    /** Contenido completo de una versión, para verla o cargarla en el formulario. */
    @GetMapping("/versions/{id}")
    public ResponseEntity<GeneralReponse> version(@PathVariable Integer id) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, tenantConfigService.version(id)));
    }

    @PreAuthorize(ProspeccionOperations.CAN_CONFIGURE)
    @PutMapping
    public ResponseEntity<GeneralReponse> update(@Valid @RequestBody TenantConfigDTO request) {
        return ResponseEntity.ok(GeneralReponse.ok(MessageKeys.SUCCESS, tenantConfigService.update(request)));
    }
}
