package co.com.gtc.prospeccion.crm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sobre de respuesta de los microservicios del CRM (su GeneralReponse).
 * No es igual al GeneralReponse de este servicio: el del CRM usa errorsMessage y message.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrmResponseDTO<T> {

    private boolean success;
    private String errorsMessage;
    /** Clave lbl* del CRM. */
    private String message;
    private T data;
}
