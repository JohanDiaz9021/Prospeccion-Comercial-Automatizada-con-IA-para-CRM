package co.com.gtc.prospeccion.draft.mapper;

import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import java.time.LocalDate;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-11-24T15:36:00-0500",
    comments = "version: 1.6.3, compiler: IncrementalProcessingEnvironment from gradle-language-java-8.14.6.jar, environment: Java 21.0.11 (Oracle Corporation)"
)
@Component
public class DraftMapperImpl implements DraftMapper {

    @Override
    public DraftResponseDTO toResponse(DraftET entity) {
        if ( entity == null ) {
            return null;
        }

        DraftResponseDTO draftResponseDTO = new DraftResponseDTO();

        draftResponseDTO.setStatusCode( entityStatusCode( entity ) );
        draftResponseDTO.setApprovedById( entityApprovedById( entity ) );
        draftResponseDTO.setCreatedDate( entityCreatedDateDate( entity ) );
        draftResponseDTO.setId( entity.getId() );
        draftResponseDTO.setPmLeafletContactId( entity.getPmLeafletContactId() );
        draftResponseDTO.setSubject( entity.getSubject() );
        draftResponseDTO.setBody( entity.getBody() );
        draftResponseDTO.setVersion( entity.getVersion() );
        draftResponseDTO.setEditedByHuman( entity.getEditedByHuman() );
        draftResponseDTO.setModel( entity.getModel() );
        draftResponseDTO.setPromptVersion( entity.getPromptVersion() );
        draftResponseDTO.setDiscardReason( entity.getDiscardReason() );

        return draftResponseDTO;
    }

    private String entityStatusCode(DraftET draftET) {
        MasterTableET status = draftET.getStatus();
        if ( status == null ) {
            return null;
        }
        return status.getCode();
    }

    private Integer entityApprovedById(DraftET draftET) {
        UserET approvedBy = draftET.getApprovedBy();
        if ( approvedBy == null ) {
            return null;
        }
        return approvedBy.getId();
    }

    private LocalDate entityCreatedDateDate(DraftET draftET) {
        DateTableET createdDate = draftET.getCreatedDate();
        if ( createdDate == null ) {
            return null;
        }
        return createdDate.getDate();
    }
}
