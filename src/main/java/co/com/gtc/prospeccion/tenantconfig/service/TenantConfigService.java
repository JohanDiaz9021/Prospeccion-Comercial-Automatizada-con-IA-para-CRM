package co.com.gtc.prospeccion.tenantconfig.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigDTO;
import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigVersionDTO;

public interface TenantConfigService {

    /** Umbral inicial aprobado en docs/negocio/perfil-de-interes.md (categorías A y B). */
    int DEFAULT_SCORE_THRESHOLD = 60;

    TenantConfigDTO get();

    TenantConfigDTO update(TenantConfigDTO request);

    /** Historial de versiones, la más nueva primero (la vigente incluida). */
    Page<TenantConfigVersionDTO> versions(Pageable pageable);

    /** Contenido completo de una versión, vigente o del historial. 404 si no existe. */
    TenantConfigDTO version(Integer id);
}
