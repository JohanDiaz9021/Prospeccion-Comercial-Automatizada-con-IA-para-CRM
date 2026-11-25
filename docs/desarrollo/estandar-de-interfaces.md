# Estándar de interfaces para las pantallas de Prospección IA

- **Fuente:** ES-GS-004 "Estándares de Diseño de Interfaces Gráficas", versión 5 (2026-11-04), y el archivo de Figma
  *Design system GTC*, que es la fuente de verdad visual.
- **Aplica a:** la pantalla real del módulo en `mf-cotizaciones` (tareas 3.2, 3.3, 5.1, 6.1 y 6.2). El
  [mini front](../../tools/mini-front/README.md) ya lo sigue, para que sirva de vista previa.

## Lo que más afecta a nuestras pantallas

| Tema | Regla |
|---|---|
| Tipografía | Poppins para títulos, Inter para el cuerpo (13 px / 18 px por defecto), Ubuntu Sans Mono para códigos e IDs |
| Íconos | Material Symbols Outlined, 18 px. Mismo ícono para la misma acción: `save`, `close`, `edit`, `visibility`, `delete_outline`, `search`, `cleaning_services`, `grid_on`, `description`, `more_vert` |
| Color | Solo tokens: Primary/500 `#25007A`, Navy `#1D288D`, Aqua `#39D4CC`, Lime `#6EF2AA`, grises Black/100 a 700 y los estados Success, Info, Warning y Error con su fondo |
| Capitalización | Mayúscula inicial en cada palabra para títulos, pestañas, etiquetas, botones y encabezados de columna (conectores en minúscula). Descripciones, mensajes y placeholders en oración normal |
| Campos | Etiqueta siempre arriba, obligatorio con `*`, ancho según la longitud máxima de la HU (no ancho completo), 8 px entre filas. Ayudas de más de 40 caracteres en Field Info (ⓘ) |
| Contenedor del formulario | Menos de 5 campos: modal. De 5 a 15: drawer de 520 px. Más de 15: pantalla completa |
| Botones | Un solo Primary por sección, tamaño Medium, todos con ícono. Cancelar es Secondary con `close`. Estado Cargando si tarda más de ~1 s |
| Tablas | 20 filas por página, filas de 31 px, encabezado de 32 px gris claro `#F4F3F7` con títulos centrados. Texto a la izquierda, números a la derecha, estados centrados. Texto largo truncado con "…" y `title`. Acciones de fila siempre visibles, con tooltip y columna sticky |
| Estados obligatorios | Inicial, Cargando (skeleton), Con datos, Vacío, Sin resultados de filtro, Error de validación, Conflicto 409, Sin permisos 403 y Solo lectura |
| Mensajes | Alert en línea (con título y descripción) para lo que hay que leer; Toast para confirmar una acción. Textos desde las etiquetas del CRM (`lbl*`), nunca escritos en el código |

## Cómo se ve cada pantalla del módulo

| Pantalla | Patrón |
|---|---|
| Búsqueda y resultados (3.2, 3.3) | Patrón A: filtros, barra de acciones, tabla paginada de prospectos con puntaje y estado |
| Bandeja de borradores (5.1) | Patrón A. Editar el borrador en drawer (asunto, cuerpo y avisos). Aprobar es la acción Primary del drawer; Descartar pide motivo |
| Configuración (6.2) | Formulario de más de 15 datos si se cuentan los del perfil: pantalla completa, por secciones |
| Panel y créditos (6.1, 4.2) | KPI Card por proveedor (consumo, presupuesto, estado) |

## Diferencia con el CRM actual (decidido)

El shell del CRM (`gtc-crm-root`) y `mf-cotizaciones` todavía no siguen la v5: usan Montserrat, Material Icons,
PrimeNG, Angular Material y Bootstrap, y el encabezado de tabla en aqua `#39D4CC`.

**Decisión (2026-11-24, tarea 3.3):** las pantallas de Prospección IA siguen la v5 **solo dentro de su área de
contenido**. Los estilos van encapsulados en `.pia` y no tocan el shell ni las demás pantallas. Detalle en
[pantallas-en-el-crm.md](pantallas-en-el-crm.md).

## Lista de verificación antes de entregar (sección 6.13)

1. Ancho de cada campo según la longitud máxima de la HU.
2. Acciones de fila sin checkbox previo y con tooltip.
3. Cancelar es Secondary con `close` y se ve distinto del Primary.
4. La grilla pagina de a 20.
5. El formulario usa el contenedor correcto para su cantidad de campos.
6. Espaciado vertical de 8 px entre filas de campos.
7. Barra de progreso real en procesos largos (por ejemplo, una búsqueda en curso: "3 de 7").
8. Estados de sin permisos, solo lectura y conflicto cubiertos.
9. Probado con textos largos y con la opción más larga de cada lista.
10. Celdas largas truncadas sin romper la fila de 31 px, con la acción Ver si es probable.
11. Tildes revisadas en todos los textos nuevos.
