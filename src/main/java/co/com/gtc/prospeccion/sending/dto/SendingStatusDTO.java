package co.com.gtc.prospeccion.sending.dto;

import java.time.LocalDateTime;

/**
 * Estado de la cola de envío para la pantalla (tarea 5.3). Fechas en hora de Colombia.
 *
 * @param now            ahora
 * @param withinWindow   si ahora la Ley 2300 permite enviar
 * @param nextWindowOpen próxima apertura de la ventana (ahora mismo si está abierta)
 * @param holidayToday   nombre del festivo de hoy, o null
 * @param dailyLimit     DAILY_SEND_LIMIT vigente (0 = envío pausado)
 * @param spacingSeconds MIN_SPACING_SECONDS vigente
 * @param limitsFromConfig false si se usan los valores de arranque porque la configuración no los define
 * @param scheduledToday correos en cola o enviados hoy
 * @param queued         en cola
 * @param sent           enviados
 * @param failed         fallidos
 * @param cancelled      cancelados
 * @param nextScheduled  hora del próximo correo en cola, o null
 * @param mailboxProvider proveedor del buzón (gmail, graph o none mientras TI no lo defina)
 */
public record SendingStatusDTO(
        LocalDateTime now,
        boolean withinWindow,
        LocalDateTime nextWindowOpen,
        String holidayToday,
        int dailyLimit,
        int spacingSeconds,
        boolean limitsFromConfig,
        int scheduledToday,
        long queued,
        long sent,
        long failed,
        long cancelled,
        LocalDateTime nextScheduled,
        String mailboxProvider) {
}
