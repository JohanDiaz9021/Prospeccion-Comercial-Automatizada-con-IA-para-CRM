package co.com.gtc.prospeccion.search.mapper;

import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.search.dto.response.SearchRunResponseDTO;
import co.com.gtc.prospeccion.search.entities.SearchRunET;
import java.time.LocalDate;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-24T15:36:00-0500",
    comments = "version: 1.6.3, compiler: IncrementalProcessingEnvironment from gradle-language-java-8.14.6.jar, environment: Java 21.0.11 (Oracle Corporation)"
)
@Component
public class SearchMapperImpl implements SearchMapper {

    @Override
    public SearchRunResponseDTO toResponse(SearchRunET entity) {
        if ( entity == null ) {
            return null;
        }

        SearchRunResponseDTO searchRunResponseDTO = new SearchRunResponseDTO();

        searchRunResponseDTO.setStatusCode( entityStatusCode( entity ) );
        searchRunResponseDTO.setStatus( entityStatusValue( entity ) );
        searchRunResponseDTO.setScheduled( entity.getFlgScheduled() );
        searchRunResponseDTO.setFrequency( entity.getScheduleFrequency() );
        searchRunResponseDTO.setCreatedDate( entityCreatedDateDate( entity ) );
        searchRunResponseDTO.setId( entity.getId() );
        searchRunResponseDTO.setPmListContactId( entity.getPmListContactId() );
        searchRunResponseDTO.setNaturalQuery( entity.getNaturalQuery() );
        searchRunResponseDTO.setFiltersJson( entity.getFiltersJson() );
        searchRunResponseDTO.setTenantConfigId( entity.getTenantConfigId() );
        searchRunResponseDTO.setFoundCount( entity.getFoundCount() );
        searchRunResponseDTO.setNewCount( entity.getNewCount() );
        searchRunResponseDTO.setQualifiedCount( entity.getQualifiedCount() );
        searchRunResponseDTO.setErrorDetail( entity.getErrorDetail() );
        searchRunResponseDTO.setParentSearchRunId( entity.getParentSearchRunId() );

        return searchRunResponseDTO;
    }

    private String entityStatusCode(SearchRunET searchRunET) {
        MasterTableET status = searchRunET.getStatus();
        if ( status == null ) {
            return null;
        }
        return status.getCode();
    }

    private String entityStatusValue(SearchRunET searchRunET) {
        MasterTableET status = searchRunET.getStatus();
        if ( status == null ) {
            return null;
        }
        return status.getValue();
    }

    private LocalDate entityCreatedDateDate(SearchRunET searchRunET) {
        DateTableET createdDate = searchRunET.getCreatedDate();
        if ( createdDate == null ) {
            return null;
        }
        return createdDate.getDate();
    }
}
