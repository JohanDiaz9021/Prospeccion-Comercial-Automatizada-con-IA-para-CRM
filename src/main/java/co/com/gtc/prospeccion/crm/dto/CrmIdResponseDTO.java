package co.com.gtc.prospeccion.crm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta del CRM de la que solo se usa el ID creado (ContactDTO de personContact, ITaskProjection de task).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrmIdResponseDTO {

    private Long id;
}
