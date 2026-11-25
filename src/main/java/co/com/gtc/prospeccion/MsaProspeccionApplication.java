package co.com.gtc.prospeccion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Microservicio de automatización de Leads ("Prospección IA") de Sueco CRM.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
@EnableDiscoveryClient
public class MsaProspeccionApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsaProspeccionApplication.class, args);
    }
}
