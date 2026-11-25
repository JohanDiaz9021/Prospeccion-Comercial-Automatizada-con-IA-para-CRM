package co.com.gtc.prospeccion.draft.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DraftResponseDTO {

    private Integer id;
    private Integer pmLeafletContactId;
    private String subject;
    /** HTML sanitizado. */
    private String body;
    /** Versión en texto plano del cuerpo (la parte text/plain del correo). */
    private String bodyText;
    private Integer version;
    private String statusCode;
    private Boolean editedByHuman;
    private Integer approvedById;
    private String model;
    private String promptVersion;
    /** Palabras del cuerpo (la regla es entre 90 y 160). */
    private Integer words;
    /** Avisos de DraftRules para quien revisa (por ejemplo TOO_LONG o NUMBER_NOT_IN_DATA). */
    private List<String> warnings;

    // ---------- Bandeja (5.1) ----------

    /** Primer nombre del contacto según su evidencia. */
    private String contactName;
    private String contactTitle;
    /** Correo al que se enviará (de la evidencia del contacto). */
    private String contactEmail;
    private String organizationName;
    /** Fecha en que se creó esta versión. */
    private LocalDate createdDate;
    private String discardReason;
    /** Si está aprobado: hora en que sale (hora de Colombia), según la cola de envío (5.3). */
    private LocalDateTime scheduledAt;
    /** Cuántas versiones tiene el borrador del contacto. */
    private Integer versions;
}
