package co.com.gtc.prospeccion.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Hilos para las búsquedas lanzadas desde la pantalla (tarea 3.2): tardan minutos (Apollo, Gemini, correo y
 * borradores), así que la petición responde de inmediato y la búsqueda sigue en segundo plano. Pocas a la vez para no
 * agotar los cupos de Apollo y Gemini.
 */
@Configuration
public class SearchExecutorConfig {

    public static final String SEARCH_EXECUTOR = "searchExecutor";

    @Bean(name = SEARCH_EXECUTOR)
    public Executor searchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("busqueda-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
