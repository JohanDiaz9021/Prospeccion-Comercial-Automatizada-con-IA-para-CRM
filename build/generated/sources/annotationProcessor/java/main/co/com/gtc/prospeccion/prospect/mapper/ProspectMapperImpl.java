package co.com.gtc.prospeccion.prospect.mapper;

import co.com.gtc.prospeccion.prospect.dto.response.ProspectCardResponseDTO;
import co.com.gtc.prospeccion.prospect.entities.ContactExtET;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-24T15:36:00-0500",
    comments = "version: 1.6.3, compiler: IncrementalProcessingEnvironment from gradle-language-java-8.14.6.jar, environment: Java 21.0.11 (Oracle Corporation)"
)
@Component
public class ProspectMapperImpl implements ProspectMapper {

    @Override
    public ProspectCardResponseDTO toCard(ContactExtET entity) {
        if ( entity == null ) {
            return null;
        }

        ProspectCardResponseDTO prospectCardResponseDTO = new ProspectCardResponseDTO();

        prospectCardResponseDTO.setApolloPersonId( entity.getProviderId() );
        prospectCardResponseDTO.setPmLeafletContactId( entity.getPmLeafletContactId() );
        prospectCardResponseDTO.setTitle( entity.getTitle() );
        prospectCardResponseDTO.setSeniority( entity.getSeniority() );
        prospectCardResponseDTO.setLinkedinUrl( entity.getLinkedinUrl() );
        prospectCardResponseDTO.setEmailVerification( entity.getEmailVerification() );
        prospectCardResponseDTO.setScore( entity.getScore() );
        prospectCardResponseDTO.setScoreReason( entity.getScoreReason() );
        prospectCardResponseDTO.setSearchRunId( entity.getSearchRunId() );

        return prospectCardResponseDTO;
    }
}
