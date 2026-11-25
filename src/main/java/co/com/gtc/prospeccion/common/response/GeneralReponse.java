package co.com.gtc.prospeccion.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta estándar del CRM (el nombre conserva la ortografía de los demás servicios).
 * messageKey siempre es una clave lbl*, nunca texto fijo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralReponse {

    private Boolean success;
    private String messageKey;
    private String detail;
    private Object data;

    public static GeneralReponse ok(String messageKey, Object data) {
        return GeneralReponse.builder().success(true).messageKey(messageKey).data(data).build();
    }

    public static GeneralReponse error(String messageKey, String detail) {
        return GeneralReponse.builder().success(false).messageKey(messageKey).detail(detail).build();
    }
}
