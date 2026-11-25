package co.com.gtc.prospeccion.search.dto.response;

import java.time.LocalDate;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una ejecución de búsqueda con su embudo: encontrados en Apollo, nuevos (no vistos antes), calificados por la IA y
 * qué pasó con cada persona nueva ({@link #results}).
 */
@Data
@NoArgsConstructor
public class SearchRunResponseDTO {

    private Integer id;
    private Integer pmListContactId;
    private String naturalQuery;
    private String filtersJson;
    private Integer tenantConfigId;
    /** Total que reporta Apollo para los filtros. */
    private Integer foundCount;
    /** Personas que no se habían visto en búsquedas anteriores y se procesaron en esta. */
    private Integer newCount;
    /** Personas que pasaron el umbral de la calificación. */
    private Integer qualifiedCount;
    /** 1 Pendiente, 2 En curso, 3 Terminada, 4 Fallida (sy_mastertable 600). */
    private String statusCode;
    private String status;
    private String errorDetail;
    /** Es una campaña activa: se repite sola. */
    private Boolean scheduled;
    /** DAILY o WEEKLY (se conserva al pausar la campaña). */
    private String frequency;
    /** Campaña que originó esta ejecución automática. */
    private Integer parentSearchRunId;
    private LocalDate createdDate;
    /**
     * Qué pasó con las personas nuevas, por resultado de la_apolloseen: CREADO (contacto con borrador en la bandeja),
     * DESCARTADO_IA (no pasó el umbral), SIN_CORREO y EXCLUIDO (baja, cliente, colaborador o empresa ya contactada).
     */
    private Map<String, Long> results;
}
