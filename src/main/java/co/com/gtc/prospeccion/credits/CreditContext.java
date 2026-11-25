package co.com.gtc.prospeccion.credits;

/**
 * A qué se cargan los créditos de una operación: la campaña (para el tope por campaña), el contacto y el usuario que
 * queda en la auditoría. En los jobs no hay usuario en la petición: se pasa el que creó la búsqueda.
 *
 * @param searchRunId        la_searchrun.ID, o null si el gasto no es de una campaña
 * @param pmLeafletContactId pm_leafletcontact.ID, si el contacto ya existe en el CRM
 * @param userId             sc_user.ID para CREATEDBY; null = usuario de la petición actual
 */
public record CreditContext(Integer searchRunId, Integer pmLeafletContactId, Integer userId) {

    /** Sin campaña ni contacto, con el usuario de la petición (por ejemplo, una prueba desde la pantalla). */
    public static final CreditContext NONE = new CreditContext(null, null, null);

    public static CreditContext forSearchRun(Integer searchRunId, Integer userId) {
        return new CreditContext(searchRunId, null, userId);
    }
}
