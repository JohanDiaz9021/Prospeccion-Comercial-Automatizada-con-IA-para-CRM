package co.com.gtc.prospeccion.prospect.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tarjeta de prospecto para la pantalla de resultados.
 */
@Data
@NoArgsConstructor
public class ProspectCardResponseDTO {

    private Integer pmLeafletContactId;
    private String apolloPersonId;
    private String title;
    private String seniority;
    private String linkedinUrl;
    private String emailVerification;
    private Integer score;
    private String scoreReason;
    private Integer searchRunId;
    // TODO: datos de la empresa (LeafletExtET) y del contacto en el CRM (nombre, correo enmascarado).
}
