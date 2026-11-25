package co.com.gtc.prospeccion.suppression.dto;

import java.time.LocalDate;

/**
 * Un correo de la lista de bloqueo.
 *
 * @param reasonCode código del motivo (604: 1 baja, 2 rebote duro, 3 queja, 4 manual)
 * @param reason     nombre del motivo en sy_mastertable
 */
public record SuppressionResponseDTO(Integer id, String email, String domain, String reasonCode, String reason,
        LocalDate createdDate, Integer sendQueueId) {
}
