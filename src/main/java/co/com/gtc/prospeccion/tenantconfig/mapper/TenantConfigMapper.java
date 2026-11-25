package co.com.gtc.prospeccion.tenantconfig.mapper;

import org.mapstruct.Mapper;

import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigDTO;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;

@Mapper(componentModel = "spring")
public interface TenantConfigMapper {

    TenantConfigDTO toDTO(TenantConfigET entity);
}
