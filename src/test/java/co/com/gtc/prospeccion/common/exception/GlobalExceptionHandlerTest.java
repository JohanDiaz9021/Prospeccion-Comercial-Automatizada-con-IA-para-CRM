package co.com.gtc.prospeccion.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.com.gtc.prospeccion.multitenancy.TenantNotAllowedException;

/**
 * Los errores propios de Spring MVC conservan su estado (antes terminaban en 500) y todos responden GeneralReponse.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class PruebaController {

        @GetMapping("/parametro")
        String parametro(@RequestParam int n) {
            return "ok";
        }

        @PostMapping("/cuerpo")
        String cuerpo(@RequestBody Map<String, Object> body) {
            return "ok";
        }

        @GetMapping("/sin-implementar")
        String sinImplementar() {
            throw new UnsupportedOperationException("TODO");
        }

        @GetMapping("/tenant")
        String tenant() {
            throw new TenantNotAllowedException("otro");
        }

        @GetMapping("/inesperado")
        String inesperado() {
            throw new IllegalStateException("falla");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PruebaController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void parametroFaltanteEs400() throws Exception {
        mvc.perform(get("/parametro"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.messageKey").value("lblValidationError"));
    }

    @Test
    void jsonMalFormadoEs400() throws Exception {
        mvc.perform(post("/cuerpo").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("lblValidationError"));
    }

    @Test
    void metodoNoPermitidoEs405ConHeaderAllow() throws Exception {
        mvc.perform(post("/parametro"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.messageKey").value("lblValidationError"));
    }

    @Test
    void sinImplementarEs501() throws Exception {
        mvc.perform(get("/sin-implementar"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.messageKey").value("lblNotImplemented"));
    }

    @Test
    void tenantNoHabilitadoEs403() throws Exception {
        mvc.perform(get("/tenant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("lblTenantNotAllowed"));
    }

    @Test
    void errorInesperadoEs500() throws Exception {
        mvc.perform(get("/inesperado"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messageKey").value("lblUnexpectedError"));
    }
}
