package co.com.gtc.prospeccion.search.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import co.com.gtc.prospeccion.search.dto.response.SearchRunResponseDTO;
import co.com.gtc.prospeccion.search.entities.SearchRunET;

@Mapper(componentModel = "spring")
public interface SearchMapper {

    @Mapping(target = "statusCode", source = "status.code")
    @Mapping(target = "status", source = "status.value")
    @Mapping(target = "scheduled", source = "flgScheduled")
    @Mapping(target = "frequency", source = "scheduleFrequency")
    @Mapping(target = "createdDate", source = "createdDate.date")
    @Mapping(target = "results", ignore = true)
    SearchRunResponseDTO toResponse(SearchRunET entity);
}
