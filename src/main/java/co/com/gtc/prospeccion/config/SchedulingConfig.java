package co.com.gtc.prospeccion.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuración de los jobs programados (@EnableScheduling está en la clase principal).
 * El tamaño del pool se ajusta con spring.task.scheduling.pool.size.
 */
@Configuration
public class SchedulingConfig {

    // TODO: con varias instancias del servicio, evitar que un mismo job corra dos veces a la vez
    //       (bloqueo en BD o una librería tipo ShedLock: consultar antes de agregar dependencias).
}
