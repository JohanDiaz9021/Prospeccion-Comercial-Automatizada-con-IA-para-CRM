package co.com.gtc.prospeccion.integration;

import java.time.Duration;

/** Espera entre reintentos de los clientes de proveedores externos. Existe para que las pruebas no esperen de verdad. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = duration -> Thread.sleep(duration.toMillis());
}
