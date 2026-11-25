package co.com.gtc.prospeccion.integration.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader();

    @Test
    void cargaElPromptDeCalificacionConSuVersionYSinLaCabecera() {
        PromptLoader.Prompt prompt = loader.load(PromptLoader.QUALIFY_CONTACT);

        assertThat(prompt.version()).isEqualTo("qualify-v1");
        assertThat(prompt.text()).doesNotStartWith("#").contains("\"ref\"").doesNotContain("# version");
        // Se guarda en memoria: la segunda vez devuelve el mismo objeto.
        assertThat(loader.load(PromptLoader.QUALIFY_CONTACT)).isSameAs(prompt);
    }

    @Test
    void laVersionCabeEnLaColumnaPromptVersion() {
        assertThat(loader.load(PromptLoader.QUALIFY_CONTACT).version()).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    void fallaSiElPromptNoExisteONoTieneVersion() {
        assertThatThrownBy(() -> loader.load("prompts/no-existe.txt"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no-existe");
        assertThatThrownBy(() -> loader.load("prompts-test/sin-version.txt"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
    }
}
