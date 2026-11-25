package co.com.gtc.prospeccion.prospect.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import co.com.gtc.prospeccion.prospect.dto.response.ProspectCardResponseDTO;
import co.com.gtc.prospeccion.prospect.entities.ContactExtET;

@Mapper(componentModel = "spring")
public interface ProspectMapper {

    @Mapping(target = "apolloPersonId", source = "providerId")
    ProspectCardResponseDTO toCard(ContactExtET entity);
}
