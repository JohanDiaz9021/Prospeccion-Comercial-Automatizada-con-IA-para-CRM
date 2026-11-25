# Perfil de interés y criterios de puntaje

- **Estado:** v1 aprobada por el equipo comercial
- **Última actualización:** 2026-11-20
- **Aprueba:** gerencia comercial
- **Valor para cargar:** [perfil-de-interes.json](perfil-de-interes.json)

El perfil de interés describe a quién le quiere vender el tenant. Se guarda en `la_tenantconfig.PROFILE_OF_INTEREST` (un perfil por tenant) y lo usa:

- **3.5 Calificación:** Gemini compara cada contacto de Apollo con el perfil y le da un puntaje de 0 a 100.
- **4.3 Redacción:** Gemini toma `company.services` y `company.valueProposition` para elegir qué ofrecerle a cada contacto (junto con el [mensaje base](mensaje-base.md)).

> El primer tenant es GTC, empresa colombiana de servicios tecnológicos. Si se agrega una marca a un supuesto nuevo, el servicio no deja cargar el perfil hasta quitarla.

## Formato

`PROFILE_OF_INTEREST` guarda el JSON de [perfil-de-interes.json](perfil-de-interes.json) como texto (columna `TEXT`, máximo 20.000 caracteres en `TenantConfigDTO`).

| Clave | Contenido | Lo usa |
|---|---|---|
| `version` | Número entero. Sube con cada cambio aprobado. Cada calificación guarda la versión con que se hizo | 3.5 |
| `company` | Nombre, propuesta de valor y servicios (qué problema resuelve cada uno y a quién le sirve) | 3.5 y 4.3 |
| `titles` | Cargos exactos y niveles de Apollo (`seniority`) por prioridad | 3.5 |
| `sectors` | Sectores por prioridad, con su equivalente en `organization.industry` de Apollo | 3.5 |
| `companySizes` | Rangos de empleados en el formato de Apollo (`"51,200"`) por prioridad | 3.5 |
| `locations` | Ciudades por prioridad | 3.5 |
| `positiveSignals` / `negativeSignals` | Señales que suben o bajan el puntaje | 3.5 |
| `scoring` | Puntos por criterio, penalización máxima y categorías | 3.5 |
| `exclusions` | Listas que el **sistema** aplica antes de llamar a Gemini | 3.x (filtro previo) |

Cada lista de prioridades tiene los niveles `high`, `medium`, `low` y `excluded`.

## 1. Qué vendemos

| Servicio | Problema que resuelve | A quién le sirve más |
|---|---|---|
| Desarrollo de software a la medida | Procesos manuales, hojas de cálculo, sistemas que no se hablan entre sí | Gerencias de operaciones y de TI |
| Integración con ERP y CRM (SAP, Salesforce, Microsoft Dynamics) | Datos duplicados, reportes lentos | Direcciones de TI y financieras |
| Automatización e inteligencia artificial | Tareas repetitivas, atención al cliente lenta | Gerencia general, operaciones, servicio al cliente |
| Soporte y mantenimiento de plataformas | Caídas, sistemas sin dueño, equipo interno pequeño | Empresas sin un área de TI grande |

**Propuesta de valor:** "Ayudamos a empresas medianas colombianas a conectar sus sistemas y automatizar procesos manuales para que operen más rápido y con menos errores."

## 2. Cargos

Buscamos a quien **decide o influye directamente** en la compra de servicios de tecnología. Los cargos van en español **y** en inglés, porque muchos perfiles colombianos usan títulos en inglés.

| Prioridad | Niveles de Apollo | Cargos exactos |
|---|---|---|
| **Alta** | `owner`, `founder`, `c_suite`, `partner`, `vp`, `director` | Gerente General, Presidente, Director General, Director Ejecutivo, Fundador, Cofundador, Propietario, Director de Tecnología, Director de TI, Director de Sistemas, Director de Transformación Digital, Director de Operaciones, Director Financiero, Director Administrativo y Financiero, Gerente de Tecnología, Gerente de TI, Gerente de Sistemas, Gerente de Transformación Digital · CEO, General Manager, Managing Director, Founder, Co-Founder, Owner, CIO, CTO, COO, CFO, IT Director, Technology Director, Digital Transformation Director, Operations Director, IT Manager, Technology Manager |
| **Media** | `head`, `manager` | Gerente de Operaciones, Gerente Administrativo, Gerente Financiero, Gerente Administrativo y Financiero, Gerente de Logística, Gerente de Servicio al Cliente, Gerente de Proyectos, Director de Proyectos, Jefe de Tecnología, Jefe de TI, Jefe de Sistemas · Operations Manager, Finance Manager, Logistics Manager, Customer Service Manager, PMO Director, Head of IT, Head of Technology, Head of Operations |
| **Baja** | `senior` | Coordinador de TI, Coordinador de Sistemas, Líder de Tecnología, Líder de Proyectos, Arquitecto de Soluciones, Jefe de Infraestructura · IT Coordinator, IT Lead, Tech Lead, Solutions Architect, Infrastructure Manager, Project Manager |
| **Excluida** | `entry`, `intern` | Analista, Asistente, Auxiliar, Practicante, Pasante, Ejecutivo de Ventas, Asesor Comercial, Reclutador · Analyst, Assistant, Intern, Sales Executive, Account Executive, Recruiter |

Reglas para Gemini:

- La lista es de referencia: un cargo equivalente (por ejemplo "Gerente de Tecnologías de la Información") recibe la prioridad del cargo al que más se parece.
- Si el cargo y el nivel de Apollo no coinciden, manda el **cargo** (un "Gerente de TI" con nivel `manager` sigue siendo de prioridad alta).
- "Gerente de TI" y "Gerente de Sistemas" van en alta porque en las empresas medianas de Colombia suelen ser el cargo más alto del área.

## 3. Sectores

| Prioridad | Sector | Industria en Apollo (se revisa con respuestas reales de Apollo) |
|---|---|---|
| **Alta** | Salud (clínicas, IPS, laboratorios) | hospital & health care, medical practice, medical devices |
| **Alta** | Retail y comercio | retail, consumer goods, supermarkets, wholesale |
| **Alta** | Logística y transporte | logistics & supply chain, transportation/trucking/railroad, warehousing, package/freight delivery |
| Media | Manufactura | machinery, mechanical or industrial engineering, food production, food & beverages, plastics, textiles |
| Media | Servicios financieros no bancarios (cooperativas, fintech, seguros) | financial services, insurance |
| Media | Educación privada | education management, higher education |
| Media | Construcción | construction, building materials, real estate |
| Media | Agroindustria | farming, dairy |
| Baja | Servicios profesionales, medios y turismo | professional training & coaching, accounting, law practice, marketing & advertising, hospitality, leisure, travel & tourism |
| Excluida | Software y consultoras de TI (competencia) | computer software, information technology & services |
| Excluida | Sector público | government administration |
| Excluida | Bancos grandes con TI propia | banking |

## 4. Tamaño de empresa

| Prioridad | Empleados (formato de Apollo) | Por qué |
|---|---|---|
| **Alta** | 51–200 (`"51,200"`), 201–500 (`"201,500"`) | Necesitan tecnología y no siempre tienen un equipo interno grande |
| Media | 501–1.000 (`"501,1000"`) | Buen presupuesto, ciclo de venta más largo |
| Baja | 11–50 (`"11,50"`), 1.001–5.000 (`"1001,5000"`) | Las pequeñas tienen poco presupuesto; las grandes suelen comprar por licitación |
| Excluida | 1–10, más de 5.000 | |

Apollo da un número aproximado (`estimated_num_employees`): Gemini lo ubica en el rango que corresponda.

## 5. Ubicación

| Prioridad | Ciudades |
|---|---|
| **Alta** | Bogotá, Medellín |
| Media | Cali, Barranquilla, Bucaramanga |
| Baja | Resto de Colombia |
| Excluida | Fuera de Colombia |

Se toma la ciudad de la persona; si falta, la de la empresa.

## 6. Señales

**Positivas** (suben el criterio "Señales de necesidad"):

- La empresa usa SAP, Salesforce, Microsoft Dynamics u Oracle.
- Crecimiento reciente: nuevas sedes, expansión a otras ciudades, contratación de personal de TI.
- La persona lleva menos de 12 meses en el cargo (suele revisar proveedores y procesos).
- El cargo tiene responsabilidad explícita sobre tecnología, transformación digital u operaciones.

**Negativas** (restan hasta 15 puntos en total):

- Datos incompletos: sin empresa, sin cargo claro o sin ubicación.
- Cargo ambiguo o sin relación con decisiones de tecnología.
- Filial de un grupo extranjero que decide la tecnología desde el exterior.
- Sector o tamaño de prioridad baja.

Gemini solo usa las señales que **aparecen en los datos recibidos**. Hoy `ApolloPersonDTO` y `ApolloOrganizationDTO` no traen tecnologías ni antigüedad en el cargo, así que esas señales casi siempre irán como dato faltante hasta que se confirmen los campos de Apollo.

## 7. Criterios de puntaje

El puntaje es la suma de cinco criterios menos la penalización por señales negativas. El total queda entre 0 y 100.

| Criterio (`key`) | Máx. | Alta | Media | Baja | Excluida | Dato faltante |
|---|---:|---:|---:|---:|---:|---:|
| Cargo y nivel de decisión (`title`) | 30 | 25–30 | 15–24 | 5–14 | 0 | 7 |
| Sector (`sector`) | 20 | 17–20 | 10–16 | 3–9 | 0 | 5 |
| Tamaño de empresa (`companySize`) | 20 | 17–20 | 10–16 | 3–9 | 0 | 5 |
| Ubicación (`location`) | 10 | 9–10 | 5–8 | 1–4 | 0 | 2 |
| Señales de necesidad (`signals`) | 20 | 15–20 | 8–14 | 1–7 | 0 | 4 |
| Penalización por señales negativas | −15 | | | | | |
| **Total** | **100** | | | | | |

Reglas:

- **Dato faltante:** si no se conoce el dato de un criterio, recibe los puntos de la columna "Dato faltante" (la mitad del mínimo de "Media", redondeada hacia abajo), y se nombra en la respuesta. Gemini no inventa datos.
- **Cargo excluido = 0 en el criterio**, no 0 en el total: el contacto queda en categoría D de forma natural. Las exclusiones que deben impedir el contacto las aplica el sistema (sección 8).
- La posición dentro de cada rango (por ejemplo 25 o 30 en un cargo alto) depende de qué tan claro es el encaje.

### Categorías

| Rango | Categoría | Qué pasa |
|---|---|---|
| 80–100 | **A** Excelente | Se consigue el correo y se redacta. Sale primero en la bandeja de revisión |
| 60–79 | **B** Bueno | Se consigue el correo y se redacta |
| 40–59 | **C** Dudoso | No se gastan créditos. Queda guardado por si se baja el umbral |
| 0–39 | **D** No encaja | Se descarta (`la_apolloseen` = DESCARTADO_IA) |

**Umbral para gastar créditos y redactar:** `la_tenantconfig.SCORE_THRESHOLD`, valor inicial **60** (A y B). No va dentro del JSON del perfil porque ya tiene su propia columna. Conviene revisarlo después de 2 a 4 semanas comparando los puntajes con las respuestas reales.

### Cómo califica el sistema (tarea 3.5)

1. **Qué ve Gemini de cada contacto:** cargo, nombre de la empresa y los filtros de la búsqueda (ubicaciones, sectores, rangos de empleados, niveles y palabras clave). People API Search no devuelve sector, tamaño ni ubicación, así que los filtros son la evidencia: el contacto cumple **al menos uno** de esos valores. No se envían nombres, apellidos, correos ni IDs.
2. **Lotes:** se califican 10 contactos por llamada (`GEMINI_QUALIFICATION_BATCH_SIZE`). El perfil viaja una vez por lote.
3. **Qué responde Gemini:** solo los puntos de cada criterio, la penalización, la razón y los datos faltantes:

```json
{
  "results": [
    {
      "ref": "1",
      "criteria": { "title": 28, "sector": 18, "companySize": 17, "location": 10, "signals": 4 },
      "penalty": 0,
      "missingData": ["signals"],
      "reason": "Director de TI de una IPS de 201 a 500 empleados en Bogotá; sector y tamaño prioritarios. Sin datos de tecnologías."
    }
  ]
}
```

4. **Qué hace el código (no la IA):** recorta cada criterio a su máximo, da los puntos de "dato faltante" al criterio que no venga, limita la penalización a `maxPenalty`, suma el total (0 a 100), asigna la categoría con `scoring.categories` y compara con el umbral del tenant. Así un error de Gemini nunca da más de 100 ni se salta las reglas del perfil.
5. **Qué se guarda:** `la_contactext.SCORE`, `SCORE_REASON` (máximo 300 caracteres), `MODEL` y `PROMPT_VERSION` (por ejemplo `qualify-v1`). La categoría se recalcula desde el puntaje.
6. **Si Gemini falla:** si bloquea o responde mal un lote, se reintenta contacto por contacto; el que siga fallando queda sin puntaje (`error`) y no gasta créditos. Si falla la llave, la cuota o la red, la búsqueda completa queda en error y se puede reintentar.

El prompt está en [qualify-contact.txt](../../src/main/resources/prompts/qualify-contact.txt). Si cambia la forma de calificar, se sube su `# version`.

### Ejemplos calificados

Personas y empresas **ficticias**. Sirven para validar los criterios con el equipo comercial y como casos de prueba del prompt de 3.5.

| # | Empresa | Cargo de la persona | Pts. cargo | Sector | Tamaño | Ubic. | Señales | Pen. | Total | Cat. |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | Clínica, Bogotá, 250 empl., usa SAP | Director de Tecnología | 29 | 19 | 18 | 10 | 14 | 0 | **90** | A |
| 2 | Cadena de tiendas, Medellín, 400 empl. | Gerente de Operaciones | 22 | 19 | 17 | 10 | 4 | 0 | **72** | B |
| 3 | Transportadora, Barranquilla, 120 empl. | Gerente General | 28 | 18 | 18 | 6 | 4 | 0 | **74** | B |
| 4 | Constructora, Cali, 60 empl. | Jefe de Sistemas | 18 | 12 | 18 | 6 | 4 | 0 | **58** | C |
| 5 | Agencia de publicidad, Bogotá, 15 empl. | Director Creativo | 0 | 5 | 5 | 10 | 1 | −10 | **11** | D |
| 6 | Empresa de software, Bogotá, 300 empl. | CTO | — | — | — | — | — | — | — | Excluido antes de Gemini (competidor) |

En los ejemplos 2 a 4 no hay datos de señales: reciben los 4 puntos de "Dato faltante". En el 5, el cargo no tiene relación con tecnología (0 puntos) y la penalización suma "cargo sin relación" y "tamaño de prioridad baja".

## 8. Exclusiones que aplica el sistema (antes de Gemini)

No se califican ni se contactan, y no gastan créditos de Gemini:

1. Correos y dominios en `la_suppression` (bajas y rebotes duros).
2. Clientes, proveedores y prospectos que ya existen en el CRM, por correo o por dominio (`CrmExclusionService`, tarea 3.4).
3. Dominios de competidores (`exclusions.competitorDomains`) y empresas excluidas a mano (`exclusions.excludedCompanyDomains`). Hoy están vacías; el equipo comercial las completa cuando las tenga.
4. Empresas contactadas en los últimos `exclusions.recontactAfterDays` días (90).
5. Correos personales (`exclusions.freeEmailDomains`): se descartan al conseguir el correo (paso 5).
6. Colaboradores de la propia empresa (`exclusions.ownDomains`, opcional; si no viene, la lista queda vacía).

Cómo las aplica la búsqueda (3.2): competidores y empresas excluidas ni se buscan en Apollo; el resto se revisa con el correo ya conseguido. Detalle en [busqueda-y-campanas.md](busqueda-y-campanas.md).

## Cómo se cambia

1. El equipo comercial propone el cambio.
2. Se actualizan este documento y [perfil-de-interes.json](perfil-de-interes.json), y se sube `version`.
3. Se carga en el CRM: **Prospección IA > Configuración** (tarea 6.2), por secciones o pegando el JSON en Avanzado. Al
   guardar se crea una versión nueva de `la_tenantconfig` y `version` sube sola si el perfil cambió. Por API:
   `PUT /msa-prospeccion/api/config`, campo `profileOfInterest`.
4. Si cambia la **forma** de calificar (no solo los valores), también se versiona el prompt `qualify-contact.txt`.

## Decisiones y pendientes

- [x] Servicios y propuesta de valor de la empresa.
- [x] Listas de cargos ("Gerente de TI" va en alta).
- [x] Sectores excluidos: sector público y bancos grandes.
- [x] Tamaños excluidos (1–10 y más de 5.000).
- [x] Umbral inicial (60) y plazo para volver a contactar (90 días).
- [ ] Equivalencias de industria de Apollo: revisarlas con respuestas reales (después de la prueba 1.7).
- [ ] Listas de dominios de competidores y de empresas excluidas: hoy vacías, las entrega el equipo comercial.

## Registro de cambios

| Versión | Fecha | Cambio | Aprobó |
|---|---|---|---|
| v1 | 2026-11-11 | Borrador a partir del perfil v1 del 2026-10-13, adaptado a `la_tenantconfig` | — |
| v1 | 2026-11-20 | Aprobado sin cambios de fondo; se quitaron las marcas [validar] | Equipo comercial |
