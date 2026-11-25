package co.com.gtc.prospeccion.draft.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;

/** Los datos del contacto, los avisos y el turno de envío los completa DraftService. */
@Mapper(componentModel = "spring")
public interface DraftMapper {

    @Mapping(target = "statusCode", source = "status.code")
    @Mapping(target = "approvedById", source = "approvedBy.id")
    @Mapping(target = "createdDate", source = "createdDate.date")
    @Mapping(target = "bodyText", ignore = true)
    @Mapping(target = "words", ignore = true)
    @Mapping(target = "warnings", ignore = true)
    @Mapping(target = "contactName", ignore = true)
    @Mapping(target = "contactTitle", ignore = true)
    @Mapping(target = "contactEmail", ignore = true)
    @Mapping(target = "organizationName", ignore = true)
    @Mapping(target = "scheduledAt", ignore = true)
    @Mapping(target = "versions", ignore = true)
    DraftResponseDTO toResponse(DraftET entity);
}
