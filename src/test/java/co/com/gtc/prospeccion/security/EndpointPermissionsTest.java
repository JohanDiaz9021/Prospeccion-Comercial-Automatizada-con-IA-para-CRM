package co.com.gtc.prospeccion.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tarea 2.9: ningún endpoint queda sin operación. Si alguien agrega uno sin @PreAuthorize, esta prueba falla. La única
 * excepción son las rutas públicas de los correos, sin JWT: la baja (5.5) y el píxel de apertura (5.4), protegidos
 * por su token firmado.
 */
class EndpointPermissionsTest {

    static final List<String> PUBLIC_CONTROLLERS = List.of("UnsubscribeController", "OpenPixelController");

    private static Map<String, String> endpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, String> result = new LinkedHashMap<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("co.com.gtc.prospeccion")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            // Solo el código del servicio: las pruebas tienen controladores propios (GlobalExceptionHandlerTest).
            boolean fromTests = type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("/test/");
            if (fromTests || PUBLIC_CONTROLLERS.contains(type.getSimpleName())) {
                continue;
            }
            PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            for (Method m : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) {
                    continue;
                }
                PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                PreAuthorize effective = onMethod != null ? onMethod : onClass;
                result.put(type.getSimpleName() + "." + m.getName(), effective == null ? null : effective.value());
            }
        }
        return result;
    }

    @Test
    void todosLosEndpointsExigenUnaOperacion() throws Exception {
        Map<String, String> endpoints = endpoints();
        List<String> unprotected = new ArrayList<>();
        endpoints.forEach((name, rule) -> {
            if (rule == null) {
                unprotected.add(name);
            }
        });

        assertThat(endpoints).hasSizeGreaterThan(20);
        assertThat(unprotected).as("endpoints sin @PreAuthorize").isEmpty();
        assertThat(endpoints.values()).allSatisfy(rule -> assertThat(List.of(ProspeccionOperations.CAN_VIEW,
                ProspeccionOperations.CAN_RUN, ProspeccionOperations.CAN_APPROVE, ProspeccionOperations.CAN_CONFIGURE))
                .contains(rule));
    }

    @Test
    void lasAccionesQueGastanCreditosOEnvianCorreosNoSonDeSoloVer() throws Exception {
        Map<String, String> endpoints = endpoints();

        assertThat(endpoints.get("SearchController.run")).isEqualTo(ProspeccionOperations.CAN_RUN);
        assertThat(endpoints.get("SearchController.schedule")).isEqualTo(ProspeccionOperations.CAN_RUN);
        assertThat(endpoints.get("DraftController.approve")).isEqualTo(ProspeccionOperations.CAN_APPROVE);
        assertThat(endpoints.get("DraftController.discard")).isEqualTo(ProspeccionOperations.CAN_APPROVE);
        assertThat(endpoints.get("ConfigController.update")).isEqualTo(ProspeccionOperations.CAN_CONFIGURE);
        assertThat(endpoints.get("DashboardController.get")).isEqualTo(ProspeccionOperations.CAN_VIEW);
    }
}
