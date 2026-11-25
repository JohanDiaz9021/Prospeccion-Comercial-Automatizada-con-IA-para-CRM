package co.com.gtc.prospeccion.tenantconfig.mapper;

import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigDTO;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-24T15:36:00-0500",
    comments = "version: 1.6.3, compiler: IncrementalProcessingEnvironment from gradle-language-java-8.14.6.jar, environment: Java 21.0.11 (Oracle Corporation)"
)
@Component
public class TenantConfigMapperImpl implements TenantConfigMapper {

    @Override
    public TenantConfigDTO toDTO(TenantConfigET entity) {
        if ( entity == null ) {
            return null;
        }

        TenantConfigDTO tenantConfigDTO = new TenantConfigDTO();

        tenantConfigDTO.setId( entity.getId() );
        tenantConfigDTO.setProfileOfInterest( entity.getProfileOfInterest() );
        tenantConfigDTO.setBaseMessage( entity.getBaseMessage() );
        tenantConfigDTO.setScoreThreshold( entity.getScoreThreshold() );
        tenantConfigDTO.setDailySendLimit( entity.getDailySendLimit() );
        tenantConfigDTO.setMinSpacingSeconds( entity.getMinSpacingSeconds() );
        tenantConfigDTO.setCompanyLinkedinUrl( entity.getCompanyLinkedinUrl() );

        return tenantConfigDTO;
    }
}
