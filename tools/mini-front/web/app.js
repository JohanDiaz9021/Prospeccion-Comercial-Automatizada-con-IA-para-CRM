"use strict";

// Mini front local de Prospección IA. Las llamadas van a /msa-prospeccion/api/... y el servidor local
// (tools/mini-front/servidor.py) las reenvía al servicio con un token de prueba.
// Todo lo que llega del servicio o de Gemini se pinta con textContent, nunca como HTML. La única excepción es la
// vista del borrador (4.3): el HTML ya viene limpio del servicio y además se muestra en un iframe con sandbox vacío
// (sin scripts, sin formularios y sin acceso a esta página).

const API = "/msa-prospeccion/api";
const MAX_CONTACTOS = 10;
const MAX_MENSAJE = 5000;

// Textos de las claves lbl* (en el CRM salen de msa-params; aquí se copian de V2__catalogos_y_etiquetas.sql).
const TEXTOS = {
  lblValidationError: "Revise los datos enviados.",
  lblNotFound: "No se encontró el registro.",
  lblAccessDenied: "No tiene permiso para realizar esta operación.",
  lblExternalServiceError: "Un servicio externo no respondió. Intente de nuevo en unos minutos.",
  lblNotImplemented: "Esta función aún no está disponible.",
  lblUnexpectedError: "Ocurrió un error inesperado. Intente de nuevo.",
  lblUnauthorized: "El token no es válido. Revise JWT_SECRET_KEY en el .env y reinicie el servicio.",
  lblTenantNotAllowed: "El tenant del token no está habilitado (PROSPECCION_TENANTS_ENABLED en el .env).",
  lblUserNotFound: "El usuario del token no existe en sc_user de la base local.",
  lblConflict: "Otra persona cambió estos datos mientras usted los editaba. Recargue la página e intente de nuevo.",
  lblProfileInvalid: "El perfil de interés no tiene un formato JSON válido.",
  lblPendingValidation: "El perfil de interés o el mensaje base todavía tienen puntos marcados como [validar].",
  lblProfileMissing: "Configure el perfil de interés antes de calificar contactos.",
  lblBaseMessageMissing: "Configure el mensaje base antes de redactar correos.",
  lblDraftNotValid: "Gemini no logró un borrador que cumpla las reglas del mensaje base (dos intentos). Intente de nuevo o revise el mensaje base.",
  lblCreditLimit: "Se alcanzó el tope de créditos de la campaña o del mes. Revise la pestaña Créditos.",
  lblServiceDown: "El servicio no está corriendo.",
  lblMiniFrontToken: "No se pudo crear el token de prueba.",
};

const CRITERIOS = [
  { clave: "title", nombre: "Cargo", max: 30 },
  { clave: "sector", nombre: "Sector", max: 20 },
  { clave: "companySize", nombre: "Tamaño", max: 20 },
  { clave: "location", nombre: "Ubicación", max: 10 },
  { clave: "signals", nombre: "Señales", max: 20 },
];

const ERRORES_GEMINI = {
  BLOCKED: "Gemini bloqueó este contacto por sus filtros de seguridad.",
  INVALID_RESPONSE: "Gemini respondió algo que no se pudo leer.",
};

// Ejemplos calificados de docs/negocio/perfil-de-interes.md (personas y empresas ficticias).
const EJEMPLOS = [
  { title: "Director de Tecnología", organizationName: "Clínica Ficticia del Norte", personLocations: "Bogota, Colombia",
    industries: "hospital & health care", employeeRanges: "201,500", signals: "La empresa usa SAP" },
  { title: "Gerente de Operaciones", organizationName: "Tiendas Ficticias Paisa", personLocations: "Medellin, Colombia",
    industries: "retail", employeeRanges: "201,500", signals: "" },
  { title: "Gerente General", organizationName: "Transportes Ficticios del Caribe", personLocations: "Barranquilla, Colombia",
    industries: "logistics & supply chain", employeeRanges: "51,200", signals: "" },
  { title: "Jefe de Sistemas", organizationName: "Constructora Ficticia del Valle", personLocations: "Cali, Colombia",
    industries: "construction", employeeRanges: "51,200", signals: "" },
  { title: "Director Creativo", organizationName: "Agencia Ficticia Creativa", personLocations: "Bogota, Colombia",
    industries: "marketing & advertising", employeeRanges: "11,50", signals: "" },
];

// Contacto ficticio del ejemplo de docs/negocio/mensaje-base.md.
const EJEMPLO_REDACTAR = { firstName: "Laura", title: "Directora de Tecnología", organizationName: "Clínica Los Andes",
  industry: "hospital & health care", companySize: "250", city: "Bogota" };

// Avisos de DraftRules: el borrador se guarda, pero quien lo revisa debe mirar ese punto.
const AVISOS_BORRADOR = {
  SUBJECT_TOO_LONG: "El asunto pasa de 60 caracteres.",
  TOO_SHORT: "El cuerpo tiene menos de 90 palabras.",
  TOO_LONG: "El cuerpo pasa de 160 palabras.",
  NUMBER_NOT_IN_DATA: "Tiene un número que no está en el mensaje base ni en los datos: puede ser inventado.",
  EXCLAMATIONS: "Tiene signos de exclamación seguidos.",
  EMOJI: "Tiene emojis.",
  SHOUTING: "Tiene palabras seguidas en mayúsculas.",
};

const $ = (id) => document.getElementById(id);
let configActual = null;
let hayCambios = false;
let infoDev = {}; // /dev/info: qué llaves hay en el .env (sin sus valores)

// ---------------------------------------------------------------- utilidades

function el(etiqueta, atributos = {}, hijos = []) {
  const nodo = document.createElement(etiqueta);
  for (const [clave, valor] of Object.entries(atributos)) {
    if (clave === "texto") nodo.textContent = valor;
    else if (clave === "clase") nodo.className = valor;
    else nodo.setAttribute(clave, valor);
  }
  for (const hijo of [].concat(hijos)) {
    if (hijo != null) nodo.append(hijo);
  }
  return nodo;
}

async function llamar(metodo, ruta, cuerpo) {
  let respuesta;
  try {
    respuesta = await fetch(ruta, {
      method: metodo,
      headers: cuerpo === undefined ? {} : { "Content-Type": "application/json" },
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    });
  } catch (e) {
    return { estado: 0, ok: false, datos: { messageKey: "lblMiniFrontDown" } };
  }
  let datos = null;
  try { datos = await respuesta.json(); } catch (e) { /* sin cuerpo JSON */ }
  return { estado: respuesta.status, ok: respuesta.ok && (datos == null || datos.success !== false), datos };
}

function textoError(res) {
  const clave = res.datos && res.datos.messageKey;
  if (clave === "lblMiniFrontDown") return "El servidor del mini front no responde. ¿Sigue abierta su ventana?";
  let texto = TEXTOS[clave] || `Error ${res.estado}${clave ? ` (${clave})` : ""}.`;
  if (res.datos && res.datos.detail && (clave === "lblServiceDown" || clave === "lblMiniFrontToken")) {
    texto += ` ${res.datos.detail}`;
  }
  return texto;
}

// Alert del estándar: ícono, título corto (mayúscula inicial) y descripción en oración normal.
const ALERTAS = {
  ok: { clase: "success", icono: "check_circle", titulo: "Listo" },
  info: { clase: "info", icono: "info", titulo: "Información" },
  alerta: { clase: "warning", icono: "warning", titulo: "Atención" },
  error: { clase: "error", icono: "error", titulo: "Error" },
};

function pintarAlerta(caja, tipo, texto, titulo) {
  const a = ALERTAS[tipo] || ALERTAS.info;
  caja.className = `alert ${a.clase}`;
  caja.replaceChildren(
    el("span", { clase: "icono", texto: a.icono, "aria-hidden": "true" }),
    el("div", {}, [el("strong", { texto: titulo || a.titulo }), el("p", { texto })]));
}

function avisar(id, tipo, texto, titulo) {
  pintarAlerta($(id), tipo, texto, titulo);
}

function ocultar(id) {
  $(id).className = "alert oculto";
}

// Estado Cargando del botón (más de ~1 s): spinner y "Cargando…", deshabilitado mientras dura.
async function conCarga(boton, accion) {
  const contenido = [...boton.childNodes];
  boton.disabled = true;
  boton.classList.add("cargando");
  boton.replaceChildren(el("span", { clase: "icono", texto: "progress_activity" }), "Cargando…");
  try {
    return await accion();
  } finally {
    boton.replaceChildren(...contenido);
    boton.classList.remove("cargando");
    boton.disabled = false;
  }
}

// Estado Cargando de un área: skeleton, nunca una pantalla en blanco.
function skeleton(filas = 4) {
  return el("div", { clase: "tarjeta skeleton", "aria-label": "Cargando" }, Array.from({ length: filas }, () => el("i")));
}

// Field Info: ayuda de más de 40 caracteres en un ícono ⓘ que abre un recuadro al hacer clic.
function iniciarFieldInfo() {
  document.querySelectorAll(".field-info[data-info]").forEach((zona) => {
    const recuadro = el("span", { clase: "recuadro oculto", role: "tooltip", texto: zona.dataset.info });
    const boton = el("button", { type: "button", "aria-label": "Más información" },
      [el("span", { clase: "icono", texto: "info" })]);
    boton.addEventListener("click", (evento) => {
      evento.preventDefault();
      evento.stopPropagation();
      recuadro.classList.toggle("oculto");
    });
    zona.replaceChildren(boton, recuadro);
  });
  document.addEventListener("click", () =>
    document.querySelectorAll(".field-info .recuadro").forEach((r) => r.classList.add("oculto")));
}

function numeroONulo(valor) {
  return valor === "" || valor == null ? null : Number(valor);
}

function leerPerfil(texto) {
  if (!texto.trim()) return { vacio: true };
  try {
    const json = JSON.parse(texto);
    if (json === null || typeof json !== "object" || Array.isArray(json)) {
      return { error: "Debe ser un objeto JSON: { ... }" };
    }
    return { json };
  } catch (e) {
    return { error: e.message };
  }
}

// ---------------------------------------------------------------- estado de arriba

async function mostrarEstado() {
  const zona = $("estado");
  zona.replaceChildren(el("span", { clase: "label", texto: "Comprobando…" }));
  const [info, salud] = await Promise.all([
    llamar("GET", "/dev/info"),
    llamar("GET", "/msa-prospeccion/actuator/health"),
  ]);
  const chips = [];
  const arriba = salud.ok && salud.datos && salud.datos.status === "UP";
  chips.push(el("span", { clase: `label ${arriba ? "ok" : "mal"}`, texto: arriba ? "Servicio Arriba" : "Servicio Apagado" }));
  if (info.ok) {
    chips.push(el("span", { clase: "label", texto: `Tenant ${info.datos.tenant}` }));
    chips.push(el("span", { clase: "label", texto: `Usuario ${info.datos.usuario}` }));
    const gemini = estadoGemini(info.datos);
    chips.push(el("span", { clase: `label ${gemini.clase}`, texto: gemini.texto, title: gemini.ayuda }));
    for (const [llave, nombre] of [["llaveApollo", "Apollo"], ["llaveHunter", "Hunter"]]) {
      chips.push(el("span", {
        clase: `label ${info.datos[llave] ? "ok" : "medio"}`,
        texto: info.datos[llave] ? `Llave de ${nombre}` : `Sin Llave de ${nombre}`,
      }));
    }
    infoDev = info.datos;
    pintarModoGemini(info.datos);
    pintarLlavesCorreo();
  }
  zona.replaceChildren(...chips);
  return arriba;
}

function estadoGemini(info) {
  if (info.llaveGemini) {
    return { clase: "ok", texto: "Gemini Real", ayuda: "GEMINI_API_KEY en el .env (el servicio la lee al arrancar)" };
  }
  if (info.geminiSimulado) {
    return { clase: "medio", texto: "Gemini Simulado",
      ayuda: "Sin GEMINI_API_KEY: el servicio usa tools/mini-front/gemini_simulado.py (reglas fijas, no IA)" };
  }
  return { clase: "mal", texto: "Sin Gemini", ayuda: "Ni GEMINI_API_KEY ni el Gemini simulado (tarea Prospección: Gemini simulado)" };
}

// Deja claro en cada pantalla si lo que se ve sale de Gemini real o del simulador.
function pintarModoGemini(info) {
  const gemini = estadoGemini(info);
  document.querySelectorAll("[data-gemini]").forEach((chip) => {
    chip.className = `label ${gemini.clase}`;
    chip.textContent = gemini.texto;
  });
  const texto = info.geminiSimulado
    ? "No hay GEMINI_API_KEY, así que la calificación y la redacción salen de reglas fijas "
      + "(tools/mini-front/gemini_simulado.py). Sirven para ver el flujo completo, no la calidad de la IA. "
      + "Con la llave en el .env y el servicio reiniciado, todo pasa a Gemini real."
    : "";
  document.querySelectorAll("[data-aviso-simulado]").forEach((caja) => {
    if (texto) pintarAlerta(caja, "alerta", texto, "Gemini Simulado");
    else caja.className = "alert oculto";
  });
  pintarModoApollo(info);
  const correo = $("llaves-correo");
  const listas = info.llaveApollo && info.llaveHunter;
  correo.className = `label ${listas ? "ok" : "medio"}`;
  correo.textContent = listas ? "Llaves de Apollo y Hunter" : "Faltan Llaves de Apollo y Hunter";
}

// ---------------------------------------------------------------- configuración (6.2)

async function cargarConfig() {
  ocultar("aviso-config");
  $("version").textContent = "Cargando…";
  const res = await llamar("GET", `${API}/config`);
  if (!res.ok) {
    $("version").textContent = "Sin Datos";
    avisar("aviso-config", "error", textoError(res));
    return;
  }
  configActual = res.datos.data;
  pintarConfig(configActual);
}

function pintarConfig(c) {
  $("version").textContent = c.id ? `Versión Vigente #${c.id}` : "Sin Guardar (Valores por Defecto)";
  $("version").className = c.id ? "label ok" : "label medio";
  $("umbral").value = c.scoreThreshold ?? "";
  $("limite-diario").value = c.dailySendLimit ?? "";
  $("espaciado").value = c.minSpacingSeconds ?? "";
  $("linkedin").value = c.companyLinkedinUrl ?? "";
  $("perfil").value = c.profileOfInterest ? formatearJson(c.profileOfInterest) : "";
  $("mensaje").value = c.baseMessage ?? "";
  $("umbral-prueba").placeholder = String(c.scoreThreshold ?? 60); // vacío = el del tenant
  revisarPerfil();
  revisarMensaje();
  marcarCambios(false);
}

function formatearJson(texto) {
  try { return JSON.stringify(JSON.parse(texto), null, 2); } catch (e) { return texto; }
}

function revisarPerfil() {
  const texto = $("perfil").value;
  const estado = $("perfil-estado");
  const resumen = $("perfil-resumen");
  const leido = leerPerfil(texto);
  resumen.textContent = "";
  if (leido.vacio) {
    estado.className = "mal";
    estado.textContent = "Vacío: el sistema no podrá calificar.";
  } else if (leido.error) {
    estado.className = "mal";
    estado.textContent = `JSON con error: ${leido.error}`;
  } else if (/\[validar\]/i.test(texto)) {
    estado.className = "mal";
    estado.textContent = "Tiene marcas [validar]: no se podrá guardar.";
  } else {
    estado.className = "bien";
    estado.textContent = "JSON válido";
  }
  if (leido.json) {
    const p = leido.json;
    const servicios = (p.company && p.company.services || []).length;
    const sectores = Object.values(p.sectors || {}).flat().length;
    const excluidos = ((p.sectors && p.sectors.excluded) || []).length;
    resumen.textContent = `${(p.company && p.company.name) || "Empresa sin nombre"} · ${servicios} servicios · `
      + `${sectores} sectores (${excluidos} excluidos)`;
    llenarSectores(p);
  }
}

function llenarSectores(perfil) {
  const industrias = new Set();
  for (const nivel of Object.values(perfil.sectors || {})) {
    for (const sector of nivel || []) (sector.apolloIndustries || []).forEach((i) => industrias.add(i));
  }
  $("sectores").replaceChildren(...[...industrias].sort().map((i) => el("option", { value: i })));
}

function revisarMensaje() {
  const texto = $("mensaje").value;
  $("mensaje-largo").textContent = `${texto.length} / ${MAX_MENSAJE}`;
  const estado = $("mensaje-estado");
  if (texto.length > MAX_MENSAJE) {
    estado.className = "mal";
    estado.textContent = "Demasiado largo.";
  } else if (/\[validar\]/i.test(texto)) {
    estado.className = "mal";
    estado.textContent = "Tiene marcas [validar]: no se podrá guardar.";
  } else {
    estado.className = "";
    estado.textContent = texto.trim() ? "" : "Vacío: no se podrán redactar correos (4.3).";
  }
}

function marcarCambios(valor) {
  hayCambios = valor;
  $("guardar").querySelector(".texto").textContent = valor ? "Guardar Versión Nueva •" : "Guardar Versión Nueva";
}

async function guardar() {
  const leido = leerPerfil($("perfil").value);
  if (leido.error) {
    avisar("aviso-config", "error", "Corrija el JSON del perfil antes de guardar.");
    return;
  }
  const cuerpo = {
    id: configActual ? configActual.id ?? null : null,
    profileOfInterest: $("perfil").value.trim() || null,
    baseMessage: $("mensaje").value.trim() ? $("mensaje").value : null,
    scoreThreshold: numeroONulo($("umbral").value),
    dailySendLimit: numeroONulo($("limite-diario").value),
    minSpacingSeconds: numeroONulo($("espaciado").value),
    companyLinkedinUrl: $("linkedin").value.trim() || null,
  };
  const res = await conCarga($("guardar"), () => llamar("PUT", `${API}/config`, cuerpo));
  if (!res.ok) {
    avisar("aviso-config", "error", textoError(res));
    return;
  }
  const anterior = cuerpo.id;
  configActual = res.datos.data;
  pintarConfig(configActual);
  avisar("aviso-config", "ok",
    `Se guardó como versión #${configActual.id}${anterior ? ` (la #${anterior} quedó en el historial)` : ""}.`,
    "Cambios Guardados");
}

async function cargarDocumento(cual) {
  const res = await llamar("GET", "/dev/documentos");
  if (!res.ok || !res.datos[cual]) {
    avisar("aviso-config", "error", "No se encontraron los documentos en docs/negocio.");
    return;
  }
  if (cual === "perfil") {
    $("perfil").value = formatearJson(res.datos.perfil);
    revisarPerfil();
  } else {
    $("mensaje").value = res.datos.mensaje.trimEnd();
    revisarMensaje();
  }
  marcarCambios(true);
  avisar("aviso-config", "alerta", "Cargado desde docs/negocio. Aún no está guardado: use \"Guardar Versión Nueva\".", "Sin Guardar");
}

// ---------------------------------------------------------------- contactos de prueba (3.5)

function agregarContacto(datos = {}) {
  const lista = $("contactos");
  if (lista.children.length >= MAX_CONTACTOS) return;
  const tarjeta = $("plantilla-contacto").content.firstElementChild.cloneNode(true);
  for (const campo of tarjeta.querySelectorAll("[data-campo]")) {
    campo.value = datos[campo.dataset.campo] ?? "";
  }
  tarjeta.querySelector(".quitar").addEventListener("click", () => {
    tarjeta.remove();
    renumerar();
  });
  lista.append(tarjeta);
  renumerar();
}

function renumerar() {
  const tarjetas = [...$("contactos").children];
  tarjetas.forEach((t, i) => { t.querySelector(".numero").textContent = `Contacto ${i + 1}`; });
  $("cuenta-contactos").textContent = `(${tarjetas.length} de ${MAX_CONTACTOS})`;
  $("agregar").disabled = tarjetas.length >= MAX_CONTACTOS;
}

function lista(valor) {
  const limpio = (valor || "").trim();
  return limpio ? [limpio] : null;
}

function leerContactos() {
  return [...$("contactos").children].map((tarjeta) => {
    const v = (campo) => tarjeta.querySelector(`[data-campo="${campo}"]`).value;
    const senales = v("signals").split("\n").map((s) => s.trim()).filter(Boolean);
    return {
      title: v("title").trim() || null,
      organizationName: v("organizationName").trim() || null,
      personLocations: lista(v("personLocations")),
      industries: lista(v("industries")),
      employeeRanges: lista(v("employeeRanges")),
      signals: senales.length ? senales : null,
    };
  });
}

function maximosDelPerfil() {
  const texto = document.querySelector('input[name="fuente"]:checked').value === "formulario"
    ? $("perfil").value
    : (configActual && configActual.profileOfInterest) || "";
  const leido = leerPerfil(texto);
  const maximos = Object.fromEntries(CRITERIOS.map((c) => [c.clave, c.max]));
  const criterios = leido.json && leido.json.scoring && leido.json.scoring.criteria;
  for (const c of criterios || []) {
    if (c && c.key in maximos && Number.isInteger(c.maxPoints)) maximos[c.key] = c.maxPoints;
  }
  return maximos;
}

async function calificar() {
  ocultar("aviso-calificar");
  const contactos = leerContactos();
  const conDatos = contactos.filter((c) => Object.values(c).some((valor) => valor != null));
  if (!conDatos.length) {
    avisar("aviso-calificar", "error", "Escriba al menos un contacto (o cargue los ejemplos).");
    return;
  }
  const cuerpo = { contacts: conDatos, scoreThreshold: numeroONulo($("umbral-prueba").value) };
  if (document.querySelector('input[name="fuente"]:checked').value === "formulario") {
    const leido = leerPerfil($("perfil").value);
    if (!leido.json) {
      avisar("aviso-calificar", "error", "El perfil de la pestaña Configuración está vacío o no es JSON válido.");
      return;
    }
    cuerpo.profileOfInterest = $("perfil").value;
  }

  $("resultados").replaceChildren(skeleton(Math.min(6, conDatos.length * 2)));
  const res = await conCarga($("calificar"), () => llamar("POST", `${API}/qualification/preview`, cuerpo));

  if (!res.ok) {
    $("resultados").replaceChildren();
    let texto = textoError(res);
    if (res.estado === 502) {
      texto = "Gemini no respondió. Revise que GEMINI_API_KEY esté en el .env y reinicie el servicio (la lee al arrancar). "
        + "Si ya está, puede ser el límite de la capa gratuita: espere un minuto.";
    }
    avisar("aviso-calificar", "error", texto);
    return;
  }
  pintarResultados(conDatos, res.datos.data, maximosDelPerfil());
}

function pintarResultados(contactos, resultados, maximos) {
  const tarjetas = resultados.map((r, i) => tarjetaResultado(contactos[i], r, maximos));
  const pasan = resultados.filter((r) => r.qualified).length;
  avisar("aviso-calificar", "ok", `${pasan} de ${resultados.length} pasan el umbral: a esos se les buscaría el correo (gasta créditos).`, "Calificación Lista");
  $("resultados").replaceChildren(...tarjetas);
}

function tarjetaResultado(contacto, r, maximos) {
  const titulo = [contacto.title || "Sin cargo", contacto.organizationName].filter(Boolean).join(" · ");
  if (r.error) {
    return el("article", { clase: "resultado" }, [
      el("div", { clase: "puntaje" }, [el("div", { clase: "valor", texto: "—" })]),
      el("div", {}, [
        el("h3", { texto: titulo }),
        el("p", { clase: "razon", texto: ERRORES_GEMINI[r.error] || `No se pudo calificar (${r.error}).` }),
        el("p", { clase: "ayuda", texto: "No gasta créditos; se puede reintentar más tarde." }),
      ]),
    ]);
  }

  const faltantes = new Set(r.missingData || []);
  const criterios = CRITERIOS.map((c) => {
    const puntos = (r.criteria || {})[c.clave] ?? 0;
    const maximo = maximos[c.clave] || c.max;
    const ancho = Math.round((100 * puntos) / Math.max(1, maximo));
    return el("div", { clase: `criterio${faltantes.has(c.clave) ? " faltante" : ""}` }, [
      el("div", { clase: "fila" }, [
        el("span", { texto: faltantes.has(c.clave) ? `${c.nombre} (sin dato)` : c.nombre }),
        el("b", { texto: `${puntos} / ${maximo}` }),
      ]),
      el("div", { clase: "barra-puntos" }, [el("i", { style: `width:${ancho}%` })]),
    ]);
  });
  if (r.penalty) {
    criterios.push(el("div", { clase: "criterio" }, [
      el("div", { clase: "fila" }, [el("span", { texto: "Penalización" }), el("b", { texto: `−${r.penalty}` })]),
    ]));
  }

  const categoria = r.category || "";
  return el("article", { clase: `resultado ${categoria}` }, [
    el("div", { clase: "puntaje" }, [
      el("div", { clase: "valor", texto: String(r.score) }),
      el("div", { clase: "de", texto: "de 100" }),
      categoria ? el("span", { clase: `categoria ${categoria}`, texto: categoria }) : null,
    ]),
    el("div", {}, [
      el("h3", { texto: titulo }),
      el("p", { clase: "razon", texto: r.reason || "Gemini no dio una razón." }),
      el("div", { clase: "criterios" }, criterios),
      el("div", { clase: "meta" }, [
        el("span", { clase: `label ${r.qualified ? "ok" : "mal"}`, texto: r.qualified ? "Pasa el Umbral" : "No Pasa el Umbral" }),
        el("span", { clase: "label", texto: r.model || "Modelo ?" }),
        el("span", { clase: "label", texto: `Prompt ${r.promptVersion || "?"}` }),
      ]),
    ]),
  ]);
}

// ---------------------------------------------------------------- redacción (4.3)

function camposRedactar() {
  return [...$("contacto-redactar").querySelectorAll("[data-campo]")];
}

async function redactar() {
  ocultar("aviso-redactar");
  const contact = Object.fromEntries(camposRedactar().map((c) => [c.dataset.campo, c.value.trim() || null]));
  const cuerpo = { contact };
  if (document.querySelector('input[name="fuente-mensaje"]:checked').value === "formulario") {
    if (!$("mensaje").value.trim()) {
      avisar("aviso-redactar", "error", "El mensaje base de la pestaña Configuración está vacío.");
      return;
    }
    cuerpo.baseMessage = $("mensaje").value;
  }

  $("borrador").replaceChildren(skeleton(8));
  const res = await conCarga($("redactar"), () => llamar("POST", `${API}/drafts/preview`, cuerpo));

  if (!res.ok) {
    $("borrador").replaceChildren();
    let texto = textoError(res);
    if (res.estado === 502) {
      texto = "Gemini no respondió. Revise que GEMINI_API_KEY esté en el .env y reinicie el servicio (la lee al arrancar). "
        + "Si ya está, puede ser el límite de la capa gratuita: espere un minuto.";
    }
    avisar("aviso-redactar", "error", texto);
    return;
  }
  pintarBorrador(res.datos.data);
}

function pintarBorrador(d) {
  const avisos = d.warnings || [];
  if (avisos.length) {
    avisar("aviso-redactar", "alerta", `Borrador listo con ${avisos.length} aviso(s) para quien lo revise.`, "Borrador con Avisos");
  } else {
    avisar("aviso-redactar", "ok", "Borrador listo y sin avisos. En el sistema real quedaría pendiente de aprobación.", "Borrador Listo");
  }

  const vista = el("iframe", { clase: "vista-correo", sandbox: "", title: "Vista del correo" });
  vista.srcdoc = "<!doctype html><meta charset=\"utf-8\"><body style=\"font-family:Arial,Helvetica,sans-serif;"
    + "font-size:15px;line-height:1.5;color:#1f2937;margin:16px;\">" + (d.body || "") + "</body>";

  const largo = (d.subject || "").length;
  $("borrador").replaceChildren(el("article", { clase: "borrador" }, [
    el("div", { clase: "asunto" }, [
      el("span", { clase: "ayuda", texto: "ASUNTO" }),
      el("h3", { texto: d.subject || "(sin asunto)" }),
    ]),
    el("div", { clase: "meta" }, [
      el("span", { clase: `label ${largo > 60 ? "medio" : "ok"}`, texto: `${largo} Caracteres` }),
      el("span", { clase: `label ${d.words >= 90 && d.words <= 160 ? "ok" : "medio"}`, texto: `${d.words} Palabras (90 a 160)` }),
      el("span", { clase: "label", texto: d.model || "Modelo ?" }),
      el("span", { clase: "label", texto: `Prompt ${d.promptVersion || "?"}` }),
    ]),
    avisos.length
      ? el("ul", { clase: "avisos" }, avisos.map((a) => el("li", { texto: AVISOS_BORRADOR[a] || a })))
      : null,
    vista,
    el("p", { clase: "ayuda", texto: "Al enviar, el sistema agrega el enlace a LinkedIn, el pie legal y el enlace para darse de baja (no los escribe Gemini)." }),
    el("details", {}, [
      el("summary", { texto: "Versión en Texto Plano" }),
      el("pre", { clase: "texto-plano", texto: d.bodyText || "" }),
    ]),
  ]));
}

// ---------------------------------------------------------------- arranque

// ---------------------------------------------------------------- créditos (4.2)

const ESTADOS_CREDITO = {
  OK: { clase: "ok", texto: "OK" },
  WARNING: { clase: "medio", texto: "Alerta" },
  EXCEEDED: { clase: "mal", texto: "Sin Cupo" },
};

function numero(valor, decimales = 0) {
  if (valor == null) return "—";
  return Number(valor).toLocaleString("es-CO", { maximumFractionDigits: decimales });
}

async function cargarCreditos() {
  ocultar("aviso-creditos");
  $("creditos").replaceChildren(skeleton(5), skeleton(5));
  const res = await conCarga($("recargar-creditos"), () => llamar("GET", `${API}/credits`));
  if (!res.ok) {
    $("creditos").replaceChildren();
    avisar("aviso-creditos", "error", textoError(res));
    return;
  }
  const lista = res.datos.data || [];
  $("creditos").replaceChildren(...lista.map(tarjetaCredito));
  const alertas = lista.filter((c) => c.status !== "OK").length;
  if (alertas) avisar("aviso-creditos", "alerta", `${alertas} proveedor(es) en alerta. El job diario lo deja en el log.`, "Créditos en Alerta");
}

function tarjetaCredito(c) {
  const estado = ESTADOS_CREDITO[c.status] || { clase: "", texto: c.status };
  const porcentaje = Math.min(100, c.percentUsed ?? 0);
  const nombre = { APOLLO: "Apollo", HUNTER: "Hunter" }[c.provider] || c.provider;
  return el("article", { clase: `kpi ${estado.clase}` }, [
    el("div", { clase: "fila" }, [
      el("h3", { texto: nombre }),
      el("span", { clase: `label ${estado.clase}`, texto: estado.texto }),
    ]),
    el("div", { clase: "cifra" }, [
      el("b", { texto: numero(c.credits, 1) }),
      el("span", { texto: c.monthlyBudget > 0
        ? ` de ${numero(c.monthlyBudget)} créditos en ${c.period}`
        : ` créditos en ${c.period} (sin tope)` }),
    ]),
    el("div", { clase: "barra-puntos" }, [el("i", { style: `width:${porcentaje}%` })]),
    el("dl", {}, [
      el("dt", { texto: "Usado" }), el("dd", { texto: c.percentUsed == null ? "—" : `${c.percentUsed} %` }),
      el("dt", { texto: "Tope por Campaña" }),
      el("dd", { texto: c.perSearchRunLimit > 0 ? numero(c.perSearchRunLimit) : "Sin tope" }),
      el("dt", { texto: "Costo Estimado" }),
      el("dd", { texto: c.costUsd == null ? "Sin precio configurado" : `US$ ${numero(c.costUsd, 2)}` }),
      el("dt", { texto: "Saldo de la Cuenta" }),
      el("dd", { texto: c.providerRemaining != null ? numero(c.providerRemaining, 1)
        : c.provider === "HUNTER" ? (infoDev.llaveHunter ? "No se pudo leer" : "Falta la llave") : "Apollo no lo informa" }),
    ]),
    c.providerNote
      ? el("p", { clase: "ayuda", style: "margin-top: var(--s); color: var(--warning)", title: c.providerNote, texto: `Hunter: ${c.providerNote}` })
      : null,
  ]);
}

// ---------------------------------------------------------------- probar correo (4.1 y 4.2)

const RESULTADOS_CORREO = {
  FOUND: { clase: "success", titulo: "Correo Encontrado", texto: "Hay un correo de trabajo aceptado: con él se redactaría el borrador (4.3)." },
  NO_MATCH: { clase: "info", titulo: "Apollo No la Reconoce", texto: "El ID no corresponde a una persona en Apollo. No se gastaron créditos de Hunter." },
  NOT_FOUND: { clase: "warning", titulo: "Sin Correo", texto: "Ni Apollo ni Hunter tienen su correo de trabajo (o falta el dominio o el apellido para buscarlo)." },
  DISCARDED: { clase: "error", titulo: "Correo Descartado", texto: "Hay un correo, pero no sirve según las reglas de la 4.1 (inválido, personal, desechable o sin verificar)." },
  OPTED_OUT: { clase: "error", titulo: "Pidió No Ser Contactada", texto: "Hunter respondió que la persona pidió no ser procesada. Irá a la lista de bloqueo (5.5)." },
};

const VERIFICACIONES = {
  APOLLO_VERIFIED: "Verificado por Apollo",
  PERSONAL_DOMAIN: "Dominio personal (gmail, hotmail…)",
  valid: "Válido (Hunter)",
  accept_all: "El dominio acepta todo (Hunter)",
  invalid: "Inválido (Hunter)",
  webmail: "Correo personal (Hunter)",
  disposable: "Desechable (Hunter)",
  unknown: "Sin poder verificar (Hunter)",
};

function camposApollo() {
  return [...$("filtros-apollo").querySelectorAll("[data-campo]")];
}

// llaves: las que usa la operación, por ejemplo [["llaveHunter", "HUNTER_API_KEY"]].
function errorProveedor(res, llaves) {
  if (res.estado !== 502) return textoError(res);
  const faltan = llaves.filter(([llave]) => !infoDev[llave]).map(([, nombre]) => nombre);
  if (faltan.length) return `Falta ${faltan.join(" y ")} en el .env. Agréguela y reinicie la tarea CRM: msa-prospeccion.`;
  const apollo = llaves.some(([llave]) => llave === "llaveApollo");
  return "El proveedor no respondió o rechazó la llave. Revise la terminal de msa-prospeccion."
    + (apollo ? " Con el plan gratis de Apollo estas APIs no están incluidas." : "");
}

async function buscarApollo(pagina = 1) {
  ocultar("aviso-apollo");
  const cuerpo = Object.fromEntries(camposApollo().map((c) => [c.dataset.campo, c.value.trim() || null]));
  if (!Object.values(cuerpo).some(Boolean)) {
    avisar("aviso-apollo", "error", "Escriba al menos un filtro (por ejemplo el cargo o la ubicación).", "Faltan Filtros");
    return;
  }
  cuerpo.page = pagina;
  $("resultados-apollo").replaceChildren(skeleton(4));
  const res = await conCarga($("buscar-apollo"), () => llamar("POST", `${API}/dev/apollo/search`, cuerpo));
  if (!res.ok) {
    $("resultados-apollo").replaceChildren();
    avisar("aviso-apollo", "error", errorProveedor(res, [["llaveApollo", "APOLLO_API_KEY"]]));
    return;
  }
  pintarPersonasApollo(res.datos.data, pagina);
}

function pintarPersonasApollo(d, pagina) {
  if (!d.people.length) {
    $("resultados-apollo").replaceChildren(el("div", { clase: "vacio" }, [
      el("span", { clase: "icono", texto: "person_search" }),
      el("strong", { texto: "Sin Resultados" }),
      el("p", { clase: "ayuda", texto: "No hay personas con esos filtros. Pruebe con menos filtros." }),
    ]));
    return;
  }
  const filas = d.people.map((p) => {
    const nombre = [p.firstName, p.lastNameObfuscated].filter(Boolean).join(" ") || "—";
    const accion = el("button", { type: "button", clase: "btn-icono accion", title: "Conseguir el correo de esta persona",
      "aria-label": "Conseguir correo" }, [el("span", { clase: "icono", texto: "alternate_email" })]);
    accion.addEventListener("click", () => {
      $("apollo-id").value = p.id;
      conseguirCorreo();
    });
    return el("tr", {}, [
      el("td", { title: nombre, texto: nombre }),
      el("td", { title: p.title || "", texto: p.title || "—" }),
      el("td", { title: p.organizationName || "", texto: p.organizationName || "—" }),
      el("td", { clase: "centro" }, [el("span", { clase: `label ${p.hasEmail ? "success" : ""}`, texto: p.hasEmail ? "Sí" : "No" })]),
      el("td", { clase: "centro codigo", title: p.id, texto: p.id }),
      el("td", { clase: "centro" }, [accion]),
    ]);
  });
  const desde = (pagina - 1) * 10 + 1;
  const anterior = el("button", { type: "button", clase: "btn-icono", title: "Página anterior", "aria-label": "Página anterior" },
    [el("span", { clase: "icono", texto: "chevron_left" })]);
  const siguiente = el("button", { type: "button", clase: "btn-icono", title: "Página siguiente", "aria-label": "Página siguiente" },
    [el("span", { clase: "icono", texto: "chevron_right" })]);
  anterior.disabled = pagina <= 1;
  siguiente.disabled = !d.hasMore || pagina >= 5;
  anterior.addEventListener("click", () => buscarApollo(pagina - 1));
  siguiente.addEventListener("click", () => buscarApollo(pagina + 1));

  $("resultados-apollo").replaceChildren(
    el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
      el("colgroup", {}, [el("col"), el("col"), el("col"), el("col", { style: "width:110px" }),
        el("col", { style: "width:200px" }), el("col", { style: "width:80px" })]),
      el("thead", {}, [el("tr", {}, ["Nombre", "Cargo", "Empresa", "¿Tiene Correo?", "ID de Apollo", "Acciones"]
        .map((t) => el("th", { texto: t })))]),
      el("tbody", {}, filas),
    ])]),
    el("div", { clase: "paginacion" }, [
      el("span", { texto: `${desde}–${desde + d.people.length - 1} de ${numero(d.total)} personas` }),
      el("span", {}, [anterior, siguiente]),
    ]));
}

function confirmarGasto(texto) {
  const dialogo = $("confirmar-correo");
  dialogo.querySelector("p").textContent = texto;
  return new Promise((resolver) => {
    const responder = (evento) => {
      const boton = evento.target.closest("button[value]");
      if (!boton) return;
      dialogo.removeEventListener("click", responder);
      dialogo.close();
      resolver(boton.value === "si");
    };
    dialogo.addEventListener("click", responder);
    dialogo.addEventListener("cancel", () => resolver(false), { once: true });
    dialogo.showModal();
  });
}

async function conseguirCorreo() {
  ocultar("aviso-correo");
  const id = $("apollo-id").value.trim();
  $("apollo-id").removeAttribute("aria-invalid");
  if (!id) {
    $("apollo-id").setAttribute("aria-invalid", "true");
    avisar("aviso-correo", "error", "Escriba el ID de Apollo de la persona o elíjala en la búsqueda de arriba.", "Falta el ID de Apollo");
    $("apollo-id").focus();
    return;
  }
  if (!(await confirmarGasto("Puede gastar hasta 1 crédito de Apollo y 1,5 de Hunter. Lo gastado queda en la pestaña Créditos y no se puede deshacer."))) return;

  $("resultado-correo").replaceChildren(skeleton(4));
  $("resultado-correo").scrollIntoView({ behavior: "smooth", block: "nearest" });
  const res = await conCarga($("conseguir-correo"), () => llamar("POST", `${API}/dev/email-discovery`, { apolloPersonId: id }));
  if (!res.ok) {
    $("resultado-correo").replaceChildren();
    const titulo = res.datos && res.datos.messageKey === "lblCreditLimit" ? "Tope de Créditos" : undefined;
    avisar("aviso-correo", "error", errorProveedor(res, [["llaveApollo", "APOLLO_API_KEY"], ["llaveHunter", "HUNTER_API_KEY"]]), titulo);
    return;
  }
  pintarCorreo(res.datos.data, "aviso-correo", "resultado-correo");
}

// Igual que el servicio: "https://www.Empresa.co/x", "@empresa.co" o "ana@empresa.co" → "empresa.co".
function limpiarDominio(valor) {
  let d = (valor || "").trim().toLowerCase().replace(/^[a-z]+:\/\//, "");
  d = d.slice(d.lastIndexOf("@") + 1).split("/")[0];
  return d.replace(/^www\./, "").replace(/^\.+|\.+$/g, "").trim();
}

// Solo Hunter: nombre, apellido y dominio ya conocidos (mientras no hay plan de Apollo con API).
async function buscarConHunter() {
  ocultar("aviso-hunter");
  const campos = [...$("filtros-hunter").querySelectorAll("[data-campo]")];
  let faltan = false;
  campos.forEach((c) => {
    const vacio = !c.value.trim();
    if (vacio) c.setAttribute("aria-invalid", "true");
    else c.removeAttribute("aria-invalid");
    faltan = faltan || vacio;
  });
  if (faltan) {
    avisar("aviso-hunter", "error", "Escriba el nombre, el apellido y el dominio de la empresa.", "Faltan Datos");
    campos.find((c) => !c.value.trim()).focus();
    return;
  }
  const cuerpo = Object.fromEntries(campos.map((c) => [c.dataset.campo, c.value.trim()]));
  cuerpo.domain = limpiarDominio(cuerpo.domain);
  $("filtros-hunter").querySelector('[data-campo="domain"]').value = cuerpo.domain;
  if (!cuerpo.domain) {
    avisar("aviso-hunter", "error", "Escriba un dominio válido, por ejemplo empresa.com.", "Dominio Inválido");
    return;
  }
  if (!(await confirmarGasto("Gasta 1 búsqueda de Hunter si encuentra el correo y 0,5 si hay que verificarlo. Lo gastado queda en la pestaña Créditos y no se puede deshacer."))) return;

  $("resultado-hunter").replaceChildren(skeleton(4));
  const res = await conCarga($("buscar-hunter"), () => llamar("POST", `${API}/dev/email-discovery/hunter`, cuerpo));
  if (!res.ok) {
    $("resultado-hunter").replaceChildren();
    const titulo = res.datos && res.datos.messageKey === "lblCreditLimit" ? "Tope de Créditos" : undefined;
    avisar("aviso-hunter", "error", errorProveedor(res, [["llaveHunter", "HUNTER_API_KEY"]]), titulo);
    return;
  }
  pintarCorreo(res.datos.data, "aviso-hunter", "resultado-hunter",
    { firstName: cuerpo.firstName, lastName: cuerpo.lastName, domain: cuerpo.domain });
  if (res.datos.data.outcome === "NOT_FOUND") {
    avisar("aviso-hunter", "alerta", "Hunter no tiene el correo de trabajo de esta persona en ese dominio. "
      + "No se gastaron créditos: Hunter solo cobra cuando lo encuentra.", "Sin Correo");
  }
}

function pintarCorreo(d, idAviso, idResultado, escrito = {}) {
  const r = RESULTADOS_CORREO[d.outcome] || { clase: "info", titulo: d.outcome, texto: "" };
  avisar(idAviso, { success: "ok", info: "info", warning: "alerta", error: "error" }[r.clase], r.texto, r.titulo);
  const p = { ...escrito, ...(d.person || {}) };
  const gastoHunter = (d.hunterSearchCredits || 0) + (d.hunterVerificationCredits || 0);
  const dato = (etiqueta, valor, mono) => [el("dt", { texto: etiqueta }), el("dd", { clase: mono ? "mono" : "", texto: valor ?? "—" })];
  $(idResultado).replaceChildren(el("div", { clase: "kpi " + (d.outcome === "FOUND" ? "ok" : "medio") }, [
    el("div", { clase: "fila" }, [
      el("h3", { texto: [p.firstName, p.lastName].filter(Boolean).join(" ") || "Persona" }),
      el("span", { clase: `label ${r.clase}`, texto: r.titulo }),
    ]),
    el("dl", {}, [
      ...dato("Correo", d.email, true),
      ...dato("Fuente", d.source),
      ...dato("Verificación", VERIFICACIONES[d.verification] || d.verification),
      ...dato("Puntaje de Hunter", d.score == null ? null : `${d.score} / 100`),
      ...dato("Cargo", p.title),
      ...dato("Empresa", [p.organizationName, p.domain].filter(Boolean).join(" · ") || null),
      ...dato("Créditos de Apollo", numero(d.apolloCredits, 1)),
      ...dato("Créditos de Hunter", numero(gastoHunter, 1)),
    ]),
    el("p", { clase: "ayuda", style: "margin-top: var(--m)" }, [
      "Lo gastado ya está en la pestaña ",
      Object.assign(el("a", { href: "#", texto: "Créditos" }), { onclick: (e) => { e.preventDefault(); irA("panel-creditos"); } }),
      ".",
    ]),
  ]));
}

function pintarLlavesCorreo() {
  const faltan = [["llaveApollo", "APOLLO_API_KEY"], ["llaveHunter", "HUNTER_API_KEY"]]
    .filter(([llave]) => !infoDev[llave]).map(([, nombre]) => nombre);
  if (faltan.length) {
    avisar("aviso-llaves-correo", "info",
      `Falta ${faltan.join(" y ")} en el .env. Sin Apollo (o con su plan gratis, que no trae estas APIs) use "Probar Solo con Hunter". `
      + "Después de agregar una llave, reinicie la tarea CRM: msa-prospeccion.", "Faltan Llaves");
  } else {
    ocultar("aviso-llaves-correo");
  }
}

// ---------------------------------------------------------------- bandeja de borradores (5.1)

const ESTADOS_BORRADOR = {
  "1": { clase: "warning", texto: "Por Revisar" },
  "2": { clase: "success", texto: "Aprobado" },
  "3": { clase: "error", texto: "Descartado" },
  "4": { clase: "", texto: "Vencido" },
};

// Además de los avisos de la redacción, en la bandeja se ven los que en una edición humana no bloquean.
const AVISOS_REVISION = {
  ...AVISOS_BORRADOR,
  SIGNATURE_MISSING: "No está la firma del mensaje base.",
  MARKS_LEFT: "Quedó una marca {{...}} o [[...]] sin llenar.",
  EMPTY_SUBJECT: "El asunto está vacío.",
  EMPTY_BODY: "El cuerpo está vacío.",
  SUBJECT_UNUSABLE: "El asunto pasa de 150 caracteres.",
};

const EJEMPLO_DEMO = { firstName: "Laura", title: "Directora de Tecnología", organizationName: "Clínica Ficticia del Norte",
  industry: "hospital & health care", companySize: "250", city: "Bogota", email: "laura@clinica-ficticia.example" };

let paginaBandeja = 0;
let drawerConCambios = false;

// ---------- diálogo y drawer genéricos ----------

// Confirmation Dialog: { titulo, texto, boton, icono, destructivo, conMotivo } → Promise<null | { motivo }>.
function confirmar(o) {
  const d = $("dialogo");
  $("dialogo-icono").textContent = o.destructivo ? "error" : "info";
  $("dialogo-icono").style.color = o.destructivo ? "var(--error)" : "var(--info)";
  $("dialogo-titulo").textContent = o.titulo;
  $("dialogo-texto").textContent = o.texto;
  $("dialogo-motivo-campo").classList.toggle("oculto", !o.conMotivo);
  $("dialogo-motivo").value = "";
  $("dialogo-motivo-error").classList.add("oculto");
  const aceptar = $("dialogo-aceptar");
  aceptar.className = `btn ${o.destructivo ? "destructive" : "primary"}`;
  aceptar.replaceChildren(el("span", { clase: "icono", texto: o.icono || "check" }), o.boton);
  return new Promise((resolver) => {
    const cerrar = (valor) => {
      aceptar.onclick = null;
      $("dialogo-cancelar").onclick = null;
      d.close();
      resolver(valor);
    };
    aceptar.onclick = () => {
      const motivo = $("dialogo-motivo").value.trim();
      if (o.conMotivo && !motivo) {
        $("dialogo-motivo-error").classList.remove("oculto");
        $("dialogo-motivo").focus();
        return;
      }
      cerrar({ motivo });
    };
    $("dialogo-cancelar").onclick = () => cerrar(null);
    d.oncancel = (e) => { e.preventDefault(); cerrar(null); };
    d.showModal();
    (o.conMotivo ? $("dialogo-motivo") : $("dialogo-cancelar")).focus();
  });
}

function abrirDrawer(titulo, cuerpo, pie) {
  $("drawer-titulo").textContent = titulo;
  $("drawer-cuerpo").replaceChildren(...cuerpo);
  $("drawer-pie").replaceChildren(...pie);
  drawerConCambios = false;
  if (!$("drawer").open) $("drawer").showModal();
}

async function cerrarDrawer() {
  if (drawerConCambios) {
    const ok = await confirmar({ titulo: "¿Salir sin Guardar?", texto: "Hay cambios en el borrador que no se han guardado.",
      boton: "Salir sin Guardar", icono: "logout", destructivo: true });
    if (!ok) return;
  }
  drawerConCambios = false;
  $("drawer").close();
}

function botonCancelar(texto = "Cancelar") {
  const b = el("button", { type: "button", clase: "btn secondary" }, [el("span", { clase: "icono", texto: "close" }), texto]);
  b.addEventListener("click", cerrarDrawer);
  return b;
}

function etiquetaEstado(codigo) {
  const e = ESTADOS_BORRADOR[codigo] || { clase: "", texto: codigo };
  return el("span", { clase: `label ${e.clase}`, texto: e.texto });
}

function textoFechaHora(iso) {
  const p = partesFecha(iso);
  return `${p.dia} ${p.fecha} a las ${p.hora}`;
}

// ---------- listado ----------

async function cargarBandeja(pagina = 0) {
  paginaBandeja = pagina;
  ocultar("aviso-bandeja");
  const estado = $("filtro-estado").value;
  const consulta = new URLSearchParams({ page: String(pagina), size: "20" });
  if (estado) consulta.set("status", estado);
  $("tabla-bandeja").replaceChildren(skeleton(6));
  const res = await conCarga($("buscar-bandeja"), () => llamar("GET", `${API}/drafts?${consulta}`));
  if (!res.ok) {
    $("tabla-bandeja").replaceChildren();
    avisar("aviso-bandeja", "error", textoError(res));
    return;
  }
  pintarBandeja(res.datos.data);
}

function pintarBandeja(pagina) {
  const filas = pagina.content || [];
  if (!filas.length) {
    const filtrado = Boolean($("filtro-estado").value);
    const nuevo = el("button", { type: "button", clase: "btn primary" }, [el("span", { clase: "icono", texto: "add" }), "Nuevo Borrador de Prueba"]);
    nuevo.addEventListener("click", abrirNuevoDemo);
    const limpiar = el("button", { type: "button", clase: "btn secondary" }, [el("span", { clase: "icono", texto: "cleaning_services" }), "Limpiar Filtros"]);
    limpiar.addEventListener("click", limpiarFiltrosBandeja);
    $("tabla-bandeja").replaceChildren(el("div", { clase: "tarjeta vacio" }, [
      el("span", { clase: "icono", texto: filtrado ? "search_off" : "inbox" }),
      el("strong", { texto: filtrado ? "Sin Resultados" : "No Hay Borradores" }),
      el("p", { clase: "ayuda", texto: filtrado
        ? "No hay borradores con ese estado."
        : "Los borradores llegan cuando la búsqueda (3.2) encuentra contactos con correo. Mientras tanto, cree uno de prueba." }),
      filtrado ? limpiar : nuevo,
    ]));
    return;
  }
  const cuerpo = filas.map((d) => {
    const revisar = el("button", { type: "button", clase: "btn-icono revisar", title: d.statusCode === "1" ? "Revisar y aprobar" : "Ver",
      "aria-label": "Revisar" }, [el("span", { clase: "icono", texto: d.statusCode === "1" ? "rate_review" : "visibility" })]);
    revisar.addEventListener("click", () => abrirRevision(d.id));
    const contacto = [d.contactName, d.contactTitle].filter(Boolean).join(" · ") || `Contacto ${d.pmLeafletContactId}`;
    const avisos = (d.warnings || []).length;
    return el("tr", {}, [
      el("td", { title: contacto, texto: contacto }),
      el("td", { title: d.organizationName || "", texto: d.organizationName || "—" }),
      el("td", { title: d.subject, texto: d.subject }),
      el("td", { clase: "centro", texto: `${d.version}${d.editedByHuman ? " ✎" : ""}`, title: d.editedByHuman ? "Editada por una persona" : "" }),
      el("td", { style: "text-align:right", texto: String(d.words ?? "—") }),
      el("td", { clase: "centro" }, [el("span", { clase: `label ${avisos ? "warning" : "success"}`, texto: avisos ? `${avisos}` : "0" })]),
      el("td", { clase: "centro" }, [etiquetaEstado(d.statusCode)]),
      el("td", { texto: d.createdDate ? partesFecha(d.createdDate).fecha : "—" }),
      el("td", { clase: "centro" }, [revisar]),
    ]);
  });
  const desde = pagina.number * pagina.size + 1;
  const anterior = el("button", { type: "button", clase: "btn-icono", title: "Página anterior", "aria-label": "Página anterior" },
    [el("span", { clase: "icono", texto: "chevron_left" })]);
  const siguiente = el("button", { type: "button", clase: "btn-icono", title: "Página siguiente", "aria-label": "Página siguiente" },
    [el("span", { clase: "icono", texto: "chevron_right" })]);
  anterior.disabled = pagina.first;
  siguiente.disabled = pagina.last;
  anterior.addEventListener("click", () => cargarBandeja(pagina.number - 1));
  siguiente.addEventListener("click", () => cargarBandeja(pagina.number + 1));
  $("tabla-bandeja").replaceChildren(
    el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
      el("colgroup", {}, [el("col", { style: "width:20%" }), el("col", { style: "width:16%" }), el("col"),
        el("col", { style: "width:70px" }), el("col", { style: "width:80px" }), el("col", { style: "width:70px" }),
        el("col", { style: "width:110px" }), el("col", { style: "width:100px" }), el("col", { style: "width:80px" })]),
      el("thead", {}, [el("tr", {}, ["Contacto", "Empresa", "Asunto", "Versión", "Palabras", "Avisos", "Estado", "Fecha", "Acciones"]
        .map((t) => el("th", { texto: t })))]),
      el("tbody", {}, cuerpo),
    ])]),
    el("div", { clase: "paginacion" }, [
      el("span", { texto: `Filas por página: 20 · ${desde}–${desde + filas.length - 1} de ${numero(pagina.totalElements)}` }),
      el("span", {}, [anterior, siguiente]),
    ]));
}

function limpiarFiltrosBandeja() {
  $("filtro-estado").value = "";
  $("filtro-estado").focus();
  cargarBandeja(0);
}

// ---------- revisión en el drawer ----------

async function abrirRevision(id) {
  abrirDrawer("Revisar Borrador", [skeleton(8)], [botonCancelar("Cerrar")]);
  const res = await llamar("GET", `${API}/drafts/${id}`);
  if (!res.ok) {
    $("drawer-cuerpo").replaceChildren(el("div", { clase: "alert error" }, [
      el("span", { clase: "icono", texto: "error" }), el("div", {}, [el("strong", { texto: "Error" }), el("p", { texto: textoError(res) })])]));
    return;
  }
  pintarRevision(res.datos.data);
}

function pintarRevision(d) {
  const pendiente = d.statusCode === "1";
  const cuerpo = [];
  const aviso = el("div", { clase: "alert oculto", id: "aviso-drawer", role: "status" });
  cuerpo.push(aviso);

  if (d.statusCode === "2") {
    cuerpo.push(alertaFija("success", "check_circle", "Aprobado",
      d.scheduledAt ? `Está en la cola de envío: sale el ${textoFechaHora(d.scheduledAt)} (hora de Colombia).` : "Está en la cola de envío."));
    const probarBaja = el("button", { type: "button", clase: "btn secondary" }, [el("span", { clase: "icono", texto: "unsubscribe" }), "Probar Enlace de Baja"]);
    probarBaja.addEventListener("click", () => abrirEnlaceBaja(d, probarBaja));
    cuerpo.push(el("div", { clase: "acciones-borrador", style: "margin: 0 0 var(--m)" }, [probarBaja,
      el("span", { clase: "ayuda", texto: "Abre la página que verá el contacto al hacer clic en \"darse de baja\" del correo." })]));
    cuerpo.push(seccionSeguimiento(d));
  } else if (d.statusCode === "3") {
    cuerpo.push(alertaFija("warning", "warning", "Descartado", `Motivo: ${d.discardReason || "sin motivo"}. Solo lectura.`));
  } else if (d.statusCode === "4") {
    cuerpo.push(alertaFija("info", "info", "Vencido", "Este borrador venció sin revisarse. Solo lectura."));
  }

  cuerpo.push(el("dl", { clase: "solo-lectura" }, [
    ...rof("Contacto", d.contactName || "—"),
    ...rof("Cargo", d.contactTitle || "—"),
    ...rof("Empresa", d.organizationName || "—"),
    ...rof("Correo", d.contactEmail || "Sin correo (no se podrá aprobar)"),
    ...rof("Versión", `${d.version} de ${d.versions || d.version}${d.editedByHuman ? " · editada por una persona" : ""}`),
    ...rof("Modelo", d.model || "—"),
    ...rof("Prompt", d.promptVersion || "—"),
  ]));

  const avisos = d.warnings || [];
  if (avisos.length) {
    cuerpo.push(el("ul", { clase: "avisos-revision" }, avisos.map((a) => el("li", { texto: AVISOS_REVISION[a] || a }))));
  }

  const asunto = el("input", { id: "drawer-asunto", maxlength: "255", value: d.subject || "" });
  asunto.value = d.subject || "";
  const largo = el("small", { texto: `${(d.subject || "").length} caracteres (máximo recomendado 60)` });
  const html = el("textarea", { id: "drawer-html", clase: "codigo", rows: "10", maxlength: "20000", spellcheck: "false" });
  html.value = d.body || "";
  const vista = el("iframe", { clase: "vista-correo", sandbox: "", title: "Vista del correo" });
  const pintarVista = () => {
    vista.srcdoc = "<!doctype html><meta charset=\"utf-8\"><body style=\"font-family:Arial,Helvetica,sans-serif;"
      + "font-size:15px;line-height:1.5;color:#1f2937;margin:16px;\">" + html.value + "</body>";
  };
  pintarVista();
  [asunto, html].forEach((c) => {
    c.disabled = !pendiente;
    c.addEventListener("input", () => {
      drawerConCambios = asunto.value !== d.subject || html.value !== d.body;
      largo.textContent = `${asunto.value.length} caracteres (máximo recomendado 60)`;
      if (c === html) pintarVista();
    });
  });

  cuerpo.push(el("div", { clase: "seccion-drawer" }, [
    el("label", { clase: "campo" }, [el("span", { clase: "campo-etiqueta" }, ["Asunto ", el("span", { clase: "obligatorio", texto: "*" })]), asunto, largo]),
  ]));
  cuerpo.push(el("div", { clase: "seccion-drawer" }, [el("h3", { texto: "Vista del Correo" }), vista,
    el("p", { clase: "ayuda", texto: "Al enviar, el sistema agrega el enlace a LinkedIn, el pie legal y el enlace para darse de baja." })]));
  cuerpo.push(el("details", { clase: "seccion-drawer" }, [
    el("summary", { texto: pendiente ? "Editar el HTML del Cuerpo" : "Ver el HTML del Cuerpo" }),
    el("label", { clase: "campo", style: "margin-top: var(--s)" }, [html,
      el("small", { texto: "Solo párrafos, listas, negrita y enlaces https: lo demás se limpia al guardar." })]),
  ]));

  if (pendiente) {
    const guardar = el("button", { type: "button", clase: "btn secondary" }, [el("span", { clase: "icono", texto: "save" }), "Guardar Versión"]);
    const redactar = el("button", { type: "button", clase: "btn tertiary" }, [el("span", { clase: "icono", texto: "edit_note" }), "Redactar de Nuevo"]);
    const descartar = el("button", { type: "button", clase: "btn destructive" }, [el("span", { clase: "icono", texto: "delete_outline" }), "Descartar"]);
    guardar.addEventListener("click", () => guardarVersion(d, asunto.value, html.value, guardar));
    redactar.addEventListener("click", () => redactarDeNuevo(d, redactar));
    descartar.addEventListener("click", () => descartarBorrador(d));
    cuerpo.push(el("div", { clase: "acciones-borrador" }, [guardar, redactar, descartar]));
  }

  const historial = el("details", { clase: "seccion-drawer" }, [el("summary", { texto: `Historial de Versiones (${d.versions || 1})` })]);
  historial.addEventListener("toggle", () => { if (historial.open && historial.children.length === 1) cargarHistorial(d, historial); });
  cuerpo.push(historial);

  const pie = [botonCancelar(pendiente ? "Cancelar" : "Cerrar")];
  if (pendiente) {
    const aprobar = el("button", { type: "button", clase: "btn primary" }, [el("span", { clase: "icono", texto: "schedule_send" }), "Aprobar y Encolar"]);
    aprobar.addEventListener("click", () => aprobarBorrador(d, aprobar));
    pie.push(aprobar);
  }
  abrirDrawer(pendiente ? "Revisar Borrador" : "Ver Borrador", cuerpo, pie);
}

// ---------- seguimiento (5.4), solo local ----------

const SIMULACIONES = [
  ["REPLY", "Respuesta", "reply"],
  ["AUTO_REPLY", "Fuera de Oficina", "event_busy"],
  ["UNSUBSCRIBE_REQUEST", "Pide la Baja", "unsubscribe"],
  ["SOFT_BOUNCE", "Rebote Temporal", "schedule"],
  ["HARD_BOUNCE", "Rebote Duro", "block"],
  ["COMPLAINT", "Queja (Spam)", "report"],
];

/** Las herramientas locales devuelven el seguimiento (5.4) y quién pasó al vendedor (5.6). */
function textoLectura(d) {
  const v = d.vendedor || {};
  let texto = textoSeguimiento(d.seguimiento || {});
  if (v.handedOff) texto += ` Pasó al vendedor: se creó la tarea en el CRM sobre su prospecto (${v.handedOff}).`;
  if (v.failed) texto += ` No se pudo crear la tarea del vendedor (${v.failed}): se reintenta en la siguiente lectura.${v.note ? " " + v.note : ""}`;
  return texto;
}

function textoSeguimiento(r) {
  const partes = [];
  if (r.replies) partes.push("Se registró la respuesta: cuenta en el Panel como \"Respondieron\".");
  if (r.unsubscribes) partes.push("Pidió la baja: el correo quedó en la lista de bloqueo y se cancelaron sus envíos en cola.");
  if (r.hardBounces) partes.push("Rebote duro: el correo quedó en la lista de bloqueo para siempre.");
  if (r.softBounces) partes.push("Rebote temporal: se registró; al tercero a la misma dirección se bloquea.");
  if (r.complaints) partes.push("Queja: el correo quedó bloqueado para siempre.");
  if (r.autoReplies) partes.push("Respuesta automática (fuera de la oficina): no cuenta como respuesta.");
  if (r.duplicates) partes.push("Ya se había procesado: no se repite nada.");
  if (r.unmatched) partes.push("No se encontró a qué envío pertenece.");
  return partes.join(" ") || "No hubo cambios.";
}

function seccionSeguimiento(d) {
  const enviar = el("button", { type: "button", clase: "btn secondary" }, [el("span", { clase: "icono", texto: "send" }), "Marcar como Enviado"]);
  enviar.addEventListener("click", async () => {
    const res = await conCarga(enviar, () => llamar("POST", `${API}/dev/mailbox/drafts/${d.id}/mark-sent`));
    if (res.ok) avisoDrawer("ok", "Ahora simule lo que llega al buzón con los botones de abajo.", "Marcado como Enviado");
    else avisoDrawer("error", res.estado === 409 ? "Ya estaba enviado (o no está en la cola): puede simular directamente." : textoError(res));
  });
  const porBuzon = el("input", { type: "checkbox" });
  const botones = SIMULACIONES.map(([tipo, texto, icono]) => {
    const b = el("button", { type: "button", clase: "btn tertiary" }, [el("span", { clase: "icono", texto: icono }), texto]);
    b.addEventListener("click", async () => {
      const res = await conCarga(b, () => llamar("POST", `${API}/dev/mailbox/drafts/${d.id}/simulate`,
        { kind: tipo, porBuzon: porBuzon.checked }));
      if (!res.ok) {
        avisoDrawer("error", res.estado === 409 && !porBuzon.checked ? "Primero envíelo (Cola de Envío > Enviar Ya) o márquelo como enviado." : textoError(res));
        return;
      }
      avisoDrawer("ok", textoLectura(res.datos.data), `Simulado: ${texto}${porBuzon.checked ? " (por Mailpit)" : ""}`);
    });
    return b;
  });
  return el("div", { clase: "seccion-drawer" }, [
    el("h3", { texto: "Probar el Seguimiento (Solo Local)" }),
    el("p", { clase: "ayuda", style: "margin-bottom: var(--s)", texto: "Envíe el correo (Cola de Envío > Enviar Ya) o márquelo como enviado sin enviarlo, y simule lo que llegaría al buzón. "
      + "\"Por Mailpit\" manda el mensaje como correo real a Mailpit y lo lee de vuelta, como hará con el buzón de verdad." }),
    el("div", { clase: "acciones-borrador", style: "margin-top: 0" }, [enviar,
      el("label", { clase: "campo", style: "flex-direction: row; align-items: center; gap: var(--xs)" }, [porBuzon, "Por Mailpit (MAILBOX_PROVIDER=smtp)"])]),
    el("div", { clase: "acciones-borrador" }, botones),
  ]);
}

async function abrirEnlaceBaja(d, boton) {
  const res = await conCarga(boton, () => llamar("GET", `${API}/dev/drafts/${d.id}/unsubscribe-link`));
  if (!res.ok) {
    avisoDrawer("error", textoError(res));
    return;
  }
  window.open(res.datos.data.url, "_blank", "noopener");
}

function alertaFija(clase, icono, titulo, texto) {
  return el("div", { clase: `alert ${clase}` }, [el("span", { clase: "icono", texto: icono }),
    el("div", {}, [el("strong", { texto: titulo }), el("p", { texto })])]);
}

function rof(etiqueta, valor) {
  return [el("dt", { texto: etiqueta }), el("dd", { texto: valor, title: valor })];
}

function avisoDrawer(tipo, texto, titulo) {
  pintarAlerta($("aviso-drawer"), tipo, texto, titulo);
  $("drawer-cuerpo").scrollTop = 0;
}

async function cargarHistorial(d, contenedor) {
  const res = await llamar("GET", `${API}/drafts/contacts/${d.pmLeafletContactId}/versions`);
  if (!res.ok) {
    contenedor.append(el("p", { clase: "ayuda", texto: textoError(res) }));
    return;
  }
  contenedor.append(el("ul", { clase: "versiones" }, res.datos.data.map((v) => el("li", {}, [
    el("span", { texto: `Versión ${v.version}${v.editedByHuman ? " · editada por una persona" : ""}` }),
    el("span", {}, [etiquetaEstado(v.statusCode)]),
  ]))));
}

async function guardarVersion(d, asunto, html, boton) {
  if (!asunto.trim() || !html.trim()) {
    avisoDrawer("error", "El asunto y el cuerpo son obligatorios.", "Faltan Datos");
    return;
  }
  const res = await conCarga(boton, () => llamar("PUT", `${API}/drafts/${d.id}`, { subject: asunto, body: html, version: d.version }));
  if (!res.ok) {
    if (res.estado === 409) {
      avisoDrawer("alerta", "Otra persona guardó una versión de este borrador. Lo que escribió sigue aquí: cópielo y recargue la bandeja.", "Conflicto de Edición");
    } else {
      avisoDrawer("error", textoError(res));
    }
    return;
  }
  const nueva = res.datos.data;
  pintarRevision(nueva);
  avisoDrawer("ok", nueva.version === d.version ? "No había cambios: no se creó otra versión."
    : `Se guardó como versión ${nueva.version}; la ${d.version} queda en el historial.`, "Cambios Guardados");
  cargarBandeja(paginaBandeja);
}

async function redactarDeNuevo(d, boton) {
  if (drawerConCambios && !(await confirmar({ titulo: "¿Redactar de Nuevo?", texto: "Se perderán los cambios que no ha guardado.",
    boton: "Redactar de Nuevo", icono: "edit_note" }))) return;
  const res = await conCarga(boton, () => llamar("POST", `${API}/drafts/contacts/${d.pmLeafletContactId}/generate`));
  if (!res.ok) {
    avisoDrawer("error", res.estado === 502 ? "Gemini no respondió. Intente de nuevo en un minuto." : textoError(res));
    return;
  }
  await abrirRevision(res.datos.data.id); // el detalle completo trae el contacto y el historial
  avisoDrawer("ok", `Gemini redactó la versión ${res.datos.data.version}.`, "Borrador Nuevo");
  cargarBandeja(paginaBandeja);
}

async function descartarBorrador(d) {
  const r = await confirmar({ titulo: "¿Descartar Este Borrador?", destructivo: true, conMotivo: true, icono: "delete_outline",
    texto: `No se enviará el correo a ${d.contactName || "este contacto"}. El motivo ayuda a mejorar el mensaje base.`, boton: "Descartar Borrador" });
  if (!r) return;
  const res = await llamar("POST", `${API}/drafts/${d.id}/discard`, { reason: r.motivo });
  if (!res.ok) {
    avisoDrawer(res.estado === 409 ? "alerta" : "error", textoError(res));
    return;
  }
  drawerConCambios = false;
  $("drawer").close();
  avisar("aviso-bandeja", "ok", `Se descartó el borrador de ${d.contactName || "el contacto"}.`, "Borrador Descartado");
  cargarBandeja(paginaBandeja);
}

async function aprobarBorrador(d, boton) {
  if (drawerConCambios) {
    avisoDrawer("alerta", "Guarde los cambios (Guardar Versión) antes de aprobar, o cancélelos.", "Cambios sin Guardar");
    return;
  }
  const ok = await confirmar({ titulo: "¿Aprobar Este Borrador?", icono: "schedule_send", boton: "Aprobar y Encolar",
    texto: "Entra a la cola de envío con el siguiente turno permitido por la Ley 2300. Hoy no sale porque aún no hay buzón (5.2)." });
  if (!ok) return;
  const res = await conCarga(boton, () => llamar("POST", `${API}/drafts/${d.id}/approve`));
  if (!res.ok) {
    avisoDrawer(res.estado === 409 ? "alerta" : "error", textoError(res));
    return;
  }
  const a = res.datos.data;
  $("drawer").close();
  avisar("aviso-bandeja", "ok", a.scheduledAt
    ? `Sale el ${textoFechaHora(a.scheduledAt)}. Véalo en la pestaña Cola de Envío.`
    : "Quedó en la cola de envío.", "Borrador Aprobado");
  cargarBandeja(paginaBandeja);
}

// ---------- datos de prueba ----------

function abrirNuevoDemo() {
  const campos = [
    ["firstName", "Primer Nombre", "ancho-20", 100, true],
    ["title", "Cargo", "ancho-40", 200, false],
    ["organizationName", "Empresa", "ancho-40", 200, true],
    ["industry", "Sector (como en Apollo)", "ancho-40", 150, false],
    ["companySize", "Empleados", "ancho-6", 30, false],
    ["city", "Ciudad", "ancho-20", 150, false],
    ["email", "Correo (Inventado)", "ancho-40", 320, true],
  ];
  const fila = el("div", { clase: "fila-campos" }, campos.map(([campo, etiqueta, ancho, max, obligatorio]) => {
    const input = el("input", { "data-campo": campo, clase: ancho, maxlength: String(max) });
    input.value = EJEMPLO_DEMO[campo] || "";
    input.addEventListener("input", () => { drawerConCambios = true; });
    return el("label", { clase: "campo" }, [el("span", { clase: "campo-etiqueta" },
      [etiqueta, obligatorio ? el("span", { clase: "obligatorio", texto: " *" }) : null]), input]);
  }));
  const crear = el("button", { type: "button", clase: "btn primary" }, [el("span", { clase: "icono", texto: "save" }), "Crear y Redactar"]);
  crear.addEventListener("click", () => crearDemo(fila, crear));
  abrirDrawer("Nuevo Borrador de Prueba", [
    el("div", { clase: "alert warning" }, [el("span", { clase: "icono", texto: "warning" }), el("div", {}, [
      el("strong", { texto: "Solo Datos Inventados" }),
      el("p", { texto: "Crea un contacto [DEMO] en la base local y Gemini le redacta el borrador. Se borra con \"Borrar Datos de Prueba\"." })])]),
    el("div", { clase: "alert oculto", id: "aviso-drawer", role: "status" }),
    fila,
  ], [botonCancelar(), crear]);
  fila.querySelector("input").focus();
}

async function crearDemo(fila, boton) {
  const cuerpo = Object.fromEntries([...fila.querySelectorAll("[data-campo]")].map((c) => [c.dataset.campo, c.value.trim() || null]));
  if (!cuerpo.firstName || !cuerpo.organizationName || !cuerpo.email) {
    avisoDrawer("error", "Escriba al menos el primer nombre, la empresa y el correo (inventado, por ejemplo terminado en .example).", "Faltan Datos");
    return;
  }
  const res = await conCarga(boton, () => llamar("POST", `${API}/dev/drafts/demo`, cuerpo));
  if (!res.ok) {
    avisoDrawer("error", res.estado === 502 ? "Gemini no respondió. Intente de nuevo en un minuto." : textoError(res));
    return;
  }
  drawerConCambios = false;
  $("filtro-estado").value = "1";
  await cargarBandeja(0);
  await abrirRevision(res.datos.data.id); // el detalle completo trae el contacto y el historial
  avisoDrawer("ok", "Gemini redactó el borrador. Revíselo y apruébelo o descártelo.", "Borrador Creado");
}

async function borrarDemo() {
  const ok = await confirmar({ titulo: "¿Borrar los Datos de Prueba?", destructivo: true, icono: "delete_outline",
    boton: "Borrar Datos de Prueba", texto: "Se borran los contactos [DEMO] con sus borradores y turnos de envío. No toca nada más." });
  if (!ok) return;
  const res = await conCarga($("borrar-demo"), () => llamar("DELETE", `${API}/dev/drafts/demo`));
  if (!res.ok) {
    avisar("aviso-bandeja", "error", textoError(res));
    return;
  }
  const b = res.datos.data;
  avisar("aviso-bandeja", "ok", `Se borraron ${b.contacts} contacto(s), ${b.drafts} borrador(es) y ${b.sendQueue} turno(s) de envío de prueba.`, "Datos Borrados");
  cargarBandeja(0);
}

function iniciarBandeja() {
  $("buscar-bandeja").addEventListener("click", () => cargarBandeja(0));
  $("limpiar-bandeja").addEventListener("click", limpiarFiltrosBandeja);
  $("nuevo-demo").addEventListener("click", abrirNuevoDemo);
  $("borrar-demo").addEventListener("click", borrarDemo);
  $("drawer-cerrar").addEventListener("click", cerrarDrawer);
  $("drawer").addEventListener("cancel", (e) => { e.preventDefault(); cerrarDrawer(); });
}

// ---------------------------------------------------------------- cola de envío (5.3)

const DIAS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];

// "2026-11-24T07:00:00" → { fecha: "24/11/2026", hora: "07:00", dia: "martes" } (DD/MM/AAAA, estándar 6.5).
function partesFecha(iso) {
  if (!iso) return { fecha: "—", hora: "—", dia: "" };
  const [f, h = "00:00"] = iso.split("T");
  const [a, m, d] = f.split("-");
  return { fecha: `${d}/${m}/${a}`, hora: h.slice(0, 5), dia: DIAS[new Date(`${f}T12:00:00`).getDay()] };
}

function kpi(clase, titulo, cifra, detalle, filas = []) {
  return el("article", { clase: `kpi ${clase}` }, [
    el("div", { clase: "fila" }, [el("h3", { texto: titulo })]),
    el("div", { clase: "cifra" }, [el("b", { texto: cifra }), el("span", { texto: detalle ? ` ${detalle}` : "" })]),
    filas.length ? el("dl", {}, filas.flatMap(([dt, dd]) => [el("dt", { texto: dt }), el("dd", { texto: dd })])) : null,
  ]);
}

async function cargarCola() {
  ocultar("aviso-cola");
  $("estado-cola").replaceChildren(skeleton(4), skeleton(4), skeleton(4));
  const res = await conCarga($("recargar-cola"), () => llamar("GET", `${API}/sending/status`));
  if (!res.ok) {
    $("estado-cola").replaceChildren();
    avisar("aviso-cola", "error", textoError(res));
    return;
  }
  const s = res.datos.data;
  const ahora = partesFecha(s.now);
  const abre = partesFecha(s.nextWindowOpen);
  const proximo = partesFecha(s.nextScheduled);
  const pausado = s.dailyLimit === 0;
  $("estado-cola").replaceChildren(
    kpi(s.withinWindow ? "ok" : "medio", "Ahora", s.withinWindow ? "Se Puede Enviar" : "Fuera de Horario",
      "", [
        ["Hora de Colombia", `${ahora.dia} ${ahora.fecha} ${ahora.hora}`],
        ["Festivo", s.holidayToday || "No"],
        ["Próxima Ventana", s.withinWindow ? "Abierta ahora" : `${abre.dia} ${abre.fecha} ${abre.hora}`],
      ]),
    kpi(pausado ? "mal" : "ok", "Hoy", `${s.scheduledToday}`, pausado ? "envío pausado (límite 0)" : `de ${s.dailyLimit} correos`, [
      ["Correos por Día", `${s.dailyLimit}${s.limitsFromConfig ? "" : " (de arranque)"}`],
      ["Segundos entre Correos", `${s.spacingSeconds}${s.limitsFromConfig ? "" : " (de arranque)"}`],
    ]),
    kpi(s.queued ? "medio" : "ok", "En Cola", `${s.queued}`, "correos", [
      ["Próximo Turno", s.nextScheduled ? `${proximo.dia} ${proximo.fecha} ${proximo.hora}` : "—"],
      ["Enviados", numero(s.sent)],
      ["Fallidos · Cancelados", `${s.failed} · ${s.cancelled}`],
      ["Buzón", { none: "Sin buzón", smtp: "SMTP (Mailpit)", gmail: "Gmail", graph: "Microsoft 365" }[s.mailboxProvider] ?? s.mailboxProvider],
    ]));
  if (!s.limitsFromConfig) {
    avisar("aviso-cola", "info", "La configuración no define Correos por Día y Segundos entre Correos: se usan los valores de arranque "
      + `(${s.dailyLimit} por día, ${s.spacingSeconds} s). Se cambian en la pestaña Configuración.`, "Límites de Arranque");
  }
}

async function simularCola() {
  const cantidad = Number($("sim-cantidad").value);
  $("sim-cantidad").removeAttribute("aria-invalid");
  if (!Number.isInteger(cantidad) || cantidad < 1 || cantidad > 200) {
    $("sim-cantidad").setAttribute("aria-invalid", "true");
    $("resultado-simulacion").replaceChildren(el("p", { clase: "ayuda", style: "color: var(--error)", texto: "Escriba un número entre 1 y 200." }));
    return;
  }
  const desde = $("sim-desde").value;
  const consulta = new URLSearchParams({ count: String(cantidad) });
  if (desde) consulta.set("from", desde.length === 16 ? `${desde}:00` : desde);
  $("resultado-simulacion").replaceChildren(skeleton(5));
  const res = await conCarga($("simular-cola"), () => llamar("GET", `${API}/sending/preview?${consulta}`));
  if (!res.ok) {
    $("resultado-simulacion").replaceChildren(el("p", { clase: "ayuda", texto: textoError(res) }));
    return;
  }
  const filas = res.datos.data.map((t) => {
    const p = partesFecha(t.at);
    return el("tr", {}, [
      el("td", { clase: "centro", texto: String(t.position) }),
      el("td", { texto: p.fecha }),
      el("td", { texto: t.dayOfWeek }),
      el("td", { clase: "codigo", texto: p.hora }),
    ]);
  });
  const dias = new Set(res.datos.data.map((t) => t.at.slice(0, 10))).size;
  $("resultado-simulacion").replaceChildren(
    el("p", { clase: "ayuda", style: "margin-bottom: var(--s)", texto: `${cantidad} correo(s) saldrían en ${dias} día(s) hábil(es). Se tienen en cuenta los que ya están en cola.` }),
    el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
      el("colgroup", {}, [el("col", { style: "width:70px" }), el("col"), el("col"), el("col")]),
      el("thead", {}, [el("tr", {}, ["#", "Fecha", "Día", "Hora"].map((t) => el("th", { texto: t })))]),
      el("tbody", {}, filas),
    ])]));
}

async function cargarFestivos() {
  const anio = $("anio-festivos").value;
  $("tabla-festivos").replaceChildren(skeleton(4));
  const res = await llamar("GET", `${API}/sending/holidays?year=${anio}`);
  if (!res.ok) {
    $("tabla-festivos").replaceChildren(el("p", { clase: "ayuda", texto: textoError(res) }));
    return;
  }
  const hoy = new Date().toISOString().slice(0, 10);
  const filas = res.datos.data.map((f) => el("tr", {}, [
    el("td", { texto: partesFecha(f.date).fecha }),
    el("td", { texto: f.dayOfWeek }),
    el("td", { title: f.name, texto: f.name }),
    el("td", { clase: "centro" }, [el("span", { clase: `label ${f.date < hoy ? "" : "info"}`, texto: f.date < hoy ? "Pasó" : "Próximo" })]),
  ]));
  $("tabla-festivos").replaceChildren(el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
    el("colgroup", {}, [el("col", { style: "width:130px" }), el("col", { style: "width:130px" }), el("col"), el("col", { style: "width:120px" })]),
    el("thead", {}, [el("tr", {}, ["Fecha", "Día", "Festivo", "Estado"].map((t) => el("th", { texto: t })))]),
    el("tbody", {}, filas),
  ])]));
}

const MOTIVOS_BLOQUEO = { "1": "Baja Voluntaria", "2": "Rebote Duro", "3": "Queja", "4": "Exclusión Manual" };

async function cargarBloqueos() {
  $("tabla-bloqueos").replaceChildren(skeleton(3));
  const res = await llamar("GET", `${API}/suppressions?page=0&size=20`);
  if (!res.ok) {
    $("tabla-bloqueos").replaceChildren(el("p", { clase: "ayuda", texto: textoError(res) }));
    return;
  }
  const filas = res.datos.data.content || [];
  if (!filas.length) {
    $("tabla-bloqueos").replaceChildren(el("div", { clase: "vacio" }, [
      el("span", { clase: "icono", texto: "verified_user" }),
      el("strong", { texto: "Nadie se ha Dado de Baja" }),
      el("p", { clase: "ayuda", texto: "Aquí aparecen los correos a los que nunca se vuelve a escribir." })]));
    return;
  }
  $("tabla-bloqueos").replaceChildren(el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
    el("colgroup", {}, [el("col"), el("col", { style: "width:26%" }), el("col", { style: "width:160px" }), el("col", { style: "width:120px" })]),
    el("thead", {}, [el("tr", {}, ["Correo", "Dominio", "Motivo", "Fecha"].map((t) => el("th", { texto: t })))]),
    el("tbody", {}, filas.map((b) => el("tr", {}, [
      el("td", { clase: "codigo", title: b.email, texto: b.email }),
      el("td", { title: b.domain, texto: b.domain }),
      el("td", { clase: "centro" }, [el("span", { clase: `label ${b.reasonCode === "1" ? "warning" : "error"}`, texto: MOTIVOS_BLOQUEO[b.reasonCode] || b.reason })]),
      el("td", { texto: b.createdDate ? partesFecha(b.createdDate).fecha : "—" }),
    ]))),
  ])]));
}

function iniciarCola() {
  const anio = new Date().getFullYear();
  $("anio-festivos").replaceChildren(...[anio, anio + 1].map((a) => el("option", { value: String(a), texto: String(a) })));
  $("anio-festivos").addEventListener("change", cargarFestivos);
  $("recargar-cola").addEventListener("click", () => { cargarCola(); cargarBloqueos(); });
  $("simular-cola").addEventListener("click", simularCola);
  $("enviar-ya").addEventListener("click", enviarYa);
  $("leer-buzon").addEventListener("click", leerBuzon);
}

/** 5.2 (solo local): envía lo que está en cola sin esperar el turno. Con smtp va a Mailpit. */
async function enviarYa() {
  ocultar("aviso-envio");
  const res = await conCarga($("enviar-ya"), () => llamar("POST", `${API}/dev/mailbox/dispatch`));
  if (!res.ok) {
    avisar("aviso-envio", "error", textoError(res));
    return;
  }
  const r = res.datos.data;
  const total = r.sent + r.retried + r.failed + r.cancelled;
  const partes = [`${r.sent} enviados`];
  if (r.retried) partes.push(`${r.retried} para reintentar`);
  if (r.failed) partes.push(`${r.failed} fallidos`);
  if (r.cancelled) partes.push(`${r.cancelled} cancelados (baja o borrador ya no aprobado)`);
  const tipo = r.failed || r.retried ? "alerta" : total ? "ok" : "info";
  avisar("aviso-envio", tipo, `${partes.join(", ")}. Paró por: ${r.stoppedBy}.${r.sent ? " Véalos en Mailpit." : ""}`,
    total ? "Envío Hecho" : "Nada que Enviar");
  cargarCola();
}

/** 5.4 (solo local): lee el buzón ya (con smtp, desde Mailpit) y procesa respuestas, rebotes y quejas. */
async function leerBuzon() {
  ocultar("aviso-envio");
  const res = await conCarga($("leer-buzon"), () => llamar("POST", `${API}/dev/mailbox/sync`));
  if (!res.ok) {
    avisar("aviso-envio", "error", textoError(res));
    return;
  }
  const d = res.datos.data;
  const r = d.seguimiento;
  const v = d.vendedor || {};
  avisar("aviso-envio", r.read || v.handedOff ? "ok" : "info",
    r.read || v.handedOff || v.failed ? `${r.read} mensajes leídos. ${textoLectura(d)}` : "No llegó nada nuevo al buzón.", "Buzón Leído");
  cargarCola();
  cargarBloqueos();
}

// ---------------------------------------------------------------- búsqueda y campañas (3.2)

const NIVELES_APOLLO = [["owner", "Dueño"], ["founder", "Fundador"], ["c_suite", "Alta Dirección"], ["partner", "Socio"],
  ["vp", "Vicepresidente"], ["head", "Jefe de Área"], ["director", "Director"], ["manager", "Gerente"],
  ["senior", "Profesional Sénior"], ["entry", "Nivel Inicial"], ["intern", "Practicante"]];
const TAMANOS_APOLLO = [["1,10", "1 a 10"], ["11,50", "11 a 50"], ["51,200", "51 a 200"], ["201,500", "201 a 500"],
  ["501,1000", "501 a 1.000"], ["1001,5000", "1.001 a 5.000"], ["5001,10000", "5.001 a 10.000"], ["10001,", "Más de 10.000"]];
const EJEMPLO_BUSQUEDA = "gerentes de TI de clínicas medianas en Bogotá";
const ESTADOS_BUSQUEDA = {
  1: { clase: "info", texto: "Pendiente" },
  2: { clase: "warning", texto: "En Curso" },
  3: { clase: "success", texto: "Terminada" },
  4: { clase: "error", texto: "Fallida" },
};
const RESULTADOS_BUSQUEDA = {
  CREADO: { clase: "success", texto: "Contacto con Borrador" },
  DESCARTADO_IA: { clase: "", texto: "Descartado por la IA" },
  SIN_CORREO: { clase: "warning", texto: "Sin Correo" },
  EXCLUIDO: { clase: "error", texto: "Excluido" },
};
let seguimiento = null;

function opcionesFiltro(contenedor, valores) {
  $(contenedor).replaceChildren(...valores.map(([valor, texto]) =>
    el("label", { clase: "opcion" }, [el("input", { type: "checkbox", value: valor }), texto])));
}

function lineas(texto) {
  return texto.split("\n").map((v) => v.trim()).filter(Boolean);
}

function marcados(contenedor) {
  return [...$(contenedor).querySelectorAll("input:checked")].map((c) => c.value);
}

function leerFiltros() {
  return {
    personTitles: lineas($("f-cargos").value),
    personSeniorities: marcados("f-niveles"),
    organizationLocations: lineas($("f-ubicaciones").value),
    organizationIndustries: lineas($("f-sectores").value),
    organizationNumEmployeesRanges: marcados("f-tamanos"),
    keywords: $("f-claves").value.split(",").map((v) => v.trim()).filter(Boolean),
  };
}

function pintarFiltros(f) {
  $("f-cargos").value = (f.personTitles || []).join("\n");
  $("f-ubicaciones").value = (f.organizationLocations || []).join("\n");
  $("f-sectores").value = (f.organizationIndustries || []).join("\n");
  $("f-claves").value = (f.keywords || []).join(", ");
  const marcar = (contenedor, valores) => $(contenedor).querySelectorAll("input").forEach((c) => {
    c.checked = (valores || []).includes(c.value);
  });
  marcar("f-niveles", f.personSeniorities);
  marcar("f-tamanos", f.organizationNumEmployeesRanges);
}

function filtrosVacios(f) {
  return !f.personTitles.length && !f.personSeniorities.length && !f.organizationIndustries.length && !f.keywords.length;
}

async function interpretarBusqueda() {
  ocultar("aviso-interpretar");
  const frase = $("frase-busqueda").value.trim();
  $("frase-busqueda").removeAttribute("aria-invalid");
  if (!frase) {
    $("frase-busqueda").setAttribute("aria-invalid", "true");
    avisar("aviso-interpretar", "error", "Escriba a quién busca, por ejemplo: " + EJEMPLO_BUSQUEDA + ".", "Falta la Búsqueda");
    $("frase-busqueda").focus();
    return;
  }
  const res = await conCarga($("interpretar"), () => llamar("POST", `${API}/search/interpret`, { naturalQuery: frase }));
  if (!res.ok) {
    avisar("aviso-interpretar", "error", textoError(res));
    return;
  }
  const f = res.datos.data;
  pintarFiltros(f);
  $("resultado-previa").replaceChildren();
  avisar("aviso-interpretar", "success", "Revise los filtros de abajo, ajústelos si hace falta y pida la vista previa "
    + "o ejecute la búsqueda." + (f.model ? ` Modelo: ${f.model}.` : ""), "Filtros Listos");
}

const FRECUENCIAS = { DAILY: "Diaria", WEEKLY: "Semanal" };

function cuerpoBusqueda() {
  const frecuencia = $("f-campana").value;
  return { naturalQuery: $("frase-busqueda").value.trim() || null, filters: leerFiltros(),
    scheduled: Boolean(frecuencia), frequency: frecuencia || null };
}

function validarFiltros() {
  ocultar("aviso-busqueda");
  if (filtrosVacios(leerFiltros())) {
    avisar("aviso-busqueda", "error", "Agregue al menos un cargo, un nivel, un sector o una palabra clave. "
      + "Con solo la ciudad o el tamaño, Apollo traería a cualquier persona.", "Faltan Filtros");
    return false;
  }
  return true;
}

async function vistaPrevia() {
  if (!validarFiltros()) return;
  $("resultado-previa").replaceChildren(skeleton(5));
  const res = await conCarga($("vista-previa"), () => llamar("POST", `${API}/search/preview`, cuerpoBusqueda()));
  if (!res.ok) {
    $("resultado-previa").replaceChildren();
    avisar("aviso-busqueda", "error", errorProveedor(res, [["llaveApollo", "APOLLO_API_KEY"]]));
    return;
  }
  const d = res.datos.data;
  if (!d.sample.length) {
    $("resultado-previa").replaceChildren(el("div", { clase: "vacio" }, [
      el("span", { clase: "icono", texto: "person_search" }),
      el("strong", { texto: "Sin Resultados" }),
      el("p", { clase: "ayuda", texto: "Apollo no encontró personas con estos filtros. Quite alguno y pruebe de nuevo." }),
    ]));
    return;
  }
  const filas = d.sample.map((p) => el("tr", {}, [
    el("td", { texto: [p.firstName, p.lastNameObfuscated].filter(Boolean).join(" ") || "—" }),
    el("td", { title: p.title || "", texto: p.title || "—" }),
    el("td", { title: p.organizationName || "", texto: p.organizationName || "—" }),
    el("td", { clase: "centro" }, [el("span", { clase: `label ${p.hasEmail ? "success" : ""}`, texto: p.hasEmail ? "Sí" : "No" })]),
    el("td", { clase: "centro" }, [el("span", { clase: `label ${p.alreadySeen ? "" : "info"}`, texto: p.alreadySeen ? "Ya Vista" : "Nueva" })]),
  ]));
  $("resultado-previa").replaceChildren(
    el("div", { clase: "tarjetas-creditos" }, [
      kpi("ok", "Personas en Apollo", numero(d.totalEntries), "con estos filtros"),
      kpi(d.newInSample ? "ok" : "medio", "Nuevas en la Muestra", `${d.newInSample}`, `de ${d.sample.length}`, [
        ["Por Ejecución", "Hasta 25 personas nuevas"]]),
    ]),
    el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
      el("colgroup", {}, [el("col"), el("col"), el("col"), el("col", { style: "width:110px" }), el("col", { style: "width:110px" })]),
      el("thead", {}, [el("tr", {}, ["Nombre", "Cargo", "Empresa", "¿Tiene Correo?", "¿Nueva?"].map((t) => el("th", { texto: t })))]),
      el("tbody", {}, filas),
    ])]),
    el("p", { clase: "ayuda", style: "margin-top: var(--s)", texto: "Apollo no da apellido completo ni correo al buscar: los da al conseguir el correo, solo para quien pasa la calificación." }));
}

async function ejecutarBusqueda() {
  if (!validarFiltros()) return;
  const cuerpo = cuerpoBusqueda();
  const ok = await confirmar({
    titulo: cuerpo.scheduled ? "¿Ejecutar y Crear la Campaña?" : "¿Ejecutar la Búsqueda?",
    texto: "Se procesan hasta 25 personas nuevas. Conseguir el correo de las que pasen la calificación gasta créditos, "
      + "con los topes de la pestaña Créditos. Los borradores quedan en la bandeja para que alguien los apruebe."
      + (cuerpo.scheduled ? ` Además, la búsqueda se repetirá sola ${cuerpo.frequency === "WEEKLY"
        ? "una vez por semana" : "cada día hábil"} a las 6:00, solo con personas nuevas.` : ""),
    boton: "Ejecutar", icono: "play_arrow",
  });
  if (!ok) return;
  const res = await conCarga($("ejecutar-busqueda"), () => llamar("POST", `${API}/search/run`, cuerpo));
  if (!res.ok) {
    avisar("aviso-busqueda", "error", textoError(res));
    return;
  }
  seguirEjecucion(res.datos.data);
  cargarBusquedas();
}

function etiquetaBusqueda(codigo) {
  const e = ESTADOS_BUSQUEDA[codigo] || { clase: "", texto: codigo };
  return el("span", { clase: `label ${e.clase}`, texto: e.texto });
}

function pintarEjecucion(r) {
  const res = r.results || {};
  const enCurso = r.statusCode === "1" || r.statusCode === "2";
  $("tarjeta-ejecucion").classList.remove("oculto");
  $("titulo-ejecucion").textContent = `Búsqueda #${r.id}${r.parentSearchRunId ? ` (Campaña #${r.parentSearchRunId})` : ""}`;
  $("estado-ejecucion").replaceChildren(etiquetaBusqueda(r.statusCode));
  $("embudo-ejecucion").replaceChildren(
    kpi("ok", "1. En Apollo", numero(r.foundCount), "personas", [["Nuevas Procesadas", `${r.newCount}`]]),
    kpi(r.qualifiedCount ? "ok" : "medio", "2. Calificadas", `${r.qualifiedCount}`, `de ${r.newCount}`, [
      ["Descartadas por la IA", `${res.DESCARTADO_IA || 0}`]]),
    kpi((res.CREADO || 0) ? "ok" : "medio", "3. Contactos con Borrador", `${res.CREADO || 0}`, "en la bandeja", [
      ["Sin Correo", `${res.SIN_CORREO || 0}`],
      ["Excluidos", `${res.EXCLUIDO || 0}`]]));
  const nota = [];
  if (enCurso) {
    nota.push(el("div", { clase: "alert info" }, [el("span", { clase: "icono", texto: "hourglass_top" }),
      el("div", {}, [el("strong", { texto: "Buscando" }),
        el("p", { texto: "La búsqueda sigue en segundo plano. Esta tarjeta se actualiza sola; puede cambiar de pestaña." })])]));
  } else if (r.errorDetail) {
    nota.push(alertaFija(r.statusCode === "4" ? "error" : "warning", r.statusCode === "4" ? "error" : "warning",
      r.statusCode === "4" ? "La Búsqueda Falló" : "Terminó con Observaciones", r.errorDetail));
  }
  if (!enCurso && (res.CREADO || 0) > 0) {
    const ir = el("button", { type: "button", clase: "btn primary" }, [el("span", { clase: "icono", texto: "inbox" }), "Revisar en la Bandeja"]);
    ir.addEventListener("click", () => irA("panel-bandeja"));
    nota.push(el("div", { clase: "barra-acciones", style: "margin-top: var(--m)" }, [ir]));
  }
  $("nota-ejecucion").replaceChildren(...nota);
}

function seguirEjecucion(r) {
  clearTimeout(seguimiento);
  pintarEjecucion(r);
  if (r.statusCode !== "1" && r.statusCode !== "2") {
    cargarBusquedas();
    return;
  }
  seguimiento = setTimeout(async () => {
    const res = await llamar("GET", `${API}/search/runs/${r.id}`);
    if (res.ok) seguirEjecucion(res.datos.data);
  }, 2000);
}

async function cargarBusquedas() {
  ocultar("aviso-historial");
  $("tabla-busquedas").replaceChildren(skeleton(4));
  const res = await conCarga($("recargar-busquedas"), () => llamar("GET", `${API}/search/runs?page=0&size=20`));
  if (!res.ok) {
    $("tabla-busquedas").replaceChildren();
    avisar("aviso-historial", "error", textoError(res));
    return;
  }
  const filas = res.datos.data.content || [];
  if (!filas.length) {
    $("tabla-busquedas").replaceChildren(el("div", { clase: "vacio" }, [
      el("span", { clase: "icono", texto: "travel_explore" }),
      el("strong", { texto: "Aún no Hay Búsquedas" }),
      el("p", { clase: "ayuda", texto: "Escriba a quién busca arriba y ejecute la primera." }),
    ]));
    return;
  }
  $("tabla-busquedas").replaceChildren(el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
    el("colgroup", {}, [el("col", { style: "width:70px" }), el("col"), el("col", { style: "width:110px" }),
      el("col", { style: "width:120px" }), el("col", { style: "width:270px" }), el("col", { style: "width:200px" }),
      el("col", { style: "width:80px" })]),
    el("thead", {}, [el("tr", {}, ["#", "Búsqueda", "Fecha", "Estado", "Apollo · Nuevas · Calificadas · Contactos", "Campaña Diaria", "Acciones"]
      .map((t) => el("th", { texto: t })))]),
    el("tbody", {}, filas.map(filaBusqueda)),
  ])]));
}

function filaBusqueda(r) {
  const res = r.results || {};
  const texto = r.naturalQuery || "(Solo filtros)";
  const ver = el("button", { type: "button", clase: "btn-icono accion", title: "Ver qué pasó con cada persona",
    "aria-label": "Ver resultados" }, [el("span", { clase: "icono", texto: "visibility" })]);
  ver.addEventListener("click", () => verResultados(r));
  let campana;
  if (r.parentSearchRunId) {
    campana = el("span", { clase: "ayuda", texto: `Automática de #${r.parentSearchRunId}` });
  } else {
    const boton = el("button", { type: "button", clase: "btn tertiary",
      title: r.scheduled ? "Pausar la campaña" : `Repetir ${r.frequency === "WEEKLY" ? "cada semana" : "cada día hábil"} a las 6:00` },
    [el("span", { clase: "icono", texto: r.scheduled ? "pause" : "event_repeat" }), r.scheduled ? "Pausar" : "Activar"]);
    boton.addEventListener("click", () => cambiarCampana(r, boton));
    campana = el("span", {}, [r.scheduled ? el("span", { clase: "label success", texto: FRECUENCIAS[r.frequency] || "Activa" }) : null, boton]);
  }
  return el("tr", {}, [
    el("td", { clase: "centro", texto: String(r.id) }),
    el("td", { title: texto, texto }),
    el("td", { texto: r.createdDate ? partesFecha(r.createdDate).fecha : "—" }),
    el("td", { clase: "centro" }, [etiquetaBusqueda(r.statusCode)]),
    el("td", { clase: "centro", texto: `${numero(r.foundCount)} · ${r.newCount} · ${r.qualifiedCount} · ${res.CREADO || 0}` }),
    el("td", {}, [campana]),
    el("td", { clase: "centro" }, [ver]),
  ]);
}

async function cambiarCampana(r, boton) {
  if (r.scheduled) {
    const ok = await confirmar({ titulo: "¿Pausar la Campaña?", texto: "La búsqueda deja de repetirse cada día. "
      + "Lo que ya creó sigue en la bandeja. Puede activarla de nuevo cuando quiera.", boton: "Pausar", icono: "pause" });
    if (!ok) return;
  }
  const res = await conCarga(boton, () => llamar("PATCH", `${API}/search/runs/${r.id}/schedule`, { scheduled: !r.scheduled }));
  if (!res.ok) {
    avisar("aviso-historial", "error", textoError(res));
    return;
  }
  avisar("aviso-historial", "success", r.scheduled ? "La campaña quedó en pausa."
    : `La búsqueda se repetirá ${r.frequency === "WEEKLY" ? "cada semana" : "cada día hábil"} a las 6:00, solo con personas nuevas.`,
  r.scheduled ? "Campaña Pausada" : "Campaña Activa");
  cargarBusquedas();
}

async function verResultados(r) {
  const contenido = el("div", {}, [skeleton(5)]);
  const pie = [botonCancelar("Cerrar")];
  abrirDrawer(`Búsqueda #${r.id}`, [contenido], pie);
  const res = await llamar("GET", `${API}/search/runs/${r.id}/results`);
  if (!res.ok) {
    contenido.replaceChildren(alertaFija("error", "error", "No se Pudo Cargar", textoError(res)));
    return;
  }
  const filas = res.datos.data;
  let filtros = {};
  try { filtros = JSON.parse(r.filtersJson || "{}"); } catch { filtros = {}; }
  const resumen = [
    ...rof("Búsqueda", r.naturalQuery || "(Solo filtros)"),
    ...rof("Cargos", (filtros.personTitles || []).join(", ") || "—"),
    ...rof("Ubicación", (filtros.organizationLocations || []).join(" · ") || "—"),
    ...rof("Sectores", (filtros.organizationIndustries || []).join(", ") || "—"),
  ];
  if (!filas.length) {
    contenido.replaceChildren(...resumen, el("div", { clase: "vacio" }, [
      el("span", { clase: "icono", texto: "group_off" }),
      el("strong", { texto: "Sin Personas Nuevas" }),
      el("p", { clase: "ayuda", texto: "Todas las personas de esta búsqueda ya se habían procesado antes." })]));
    return;
  }
  contenido.replaceChildren(...resumen, el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla" }, [
    el("colgroup", {}, [el("col", { style: "width:170px" }), el("col", { style: "width:70px" }), el("col")]),
    el("thead", {}, [el("tr", {}, ["Resultado", "Puntaje", "Contacto"].map((t) => el("th", { texto: t })))]),
    el("tbody", {}, filas.map((p) => {
      const e = RESULTADOS_BUSQUEDA[p.result] || { clase: "", texto: p.result };
      const contacto = p.pmLeafletContactId
        ? [p.firstName, p.title, p.organizationName].filter(Boolean).join(" · ")
        : "No se guardan datos de quien no se contacta";
      return el("tr", {}, [
        el("td", {}, [el("span", { clase: `label ${e.clase}`, texto: e.texto })]),
        el("td", { clase: "centro", texto: p.score == null ? "—" : String(p.score) }),
        el("td", { title: contacto, texto: contacto, clase: p.pmLeafletContactId ? "" : "ayuda" }),
      ]);
    })),
  ])]));
}

async function repetirCampanas() {
  const ok = await confirmar({ titulo: "¿Repetir las Campañas Ahora?", texto: "Ejecuta de nuevo cada campaña activa, "
    + "le toque hoy o no (el job de las 6:00 respeta la frecuencia: diaria o semanal), solo con personas nuevas. "
    + "Puede tardar un par de minutos y gasta créditos con los mismos topes.", boton: "Repetir Ahora", icono: "event_repeat" });
  if (!ok) return;
  const res = await conCarga($("repetir-campanas"), () => llamar("POST", `${API}/dev/search/run-scheduled`));
  if (!res.ok) {
    avisar("aviso-historial", "error", textoError(res));
    return;
  }
  const n = res.datos.data.campaignsExecuted;
  avisar("aviso-historial", n ? "success" : "info", n ? `Se ejecutaron ${n} campaña(s). Abajo está cada ejecución automática.`
    : "No hay campañas activas. Elija \"Repetir\" al ejecutar o use Activar en el historial.",
  n ? "Campañas Ejecutadas" : "Sin Campañas Activas");
  cargarBusquedas();
}

async function borrarBusquedas() {
  const ok = await confirmar({ titulo: "¿Borrar los Datos de Prueba?", texto: "Se borran todas las búsquedas y lo que "
    + "crearon en la base local: contactos, evidencia, borradores, turnos de envío y la memoria de Apollo. "
    + "El gasto de créditos se conserva. No se puede deshacer.", boton: "Borrar", icono: "delete", destructivo: true });
  if (!ok) return;
  const res = await conCarga($("borrar-busquedas"), () => llamar("DELETE", `${API}/dev/search/data`));
  if (!res.ok) {
    avisar("aviso-historial", "error", textoError(res));
    return;
  }
  const b = res.datos.data;
  clearTimeout(seguimiento);
  $("tarjeta-ejecucion").classList.add("oculto");
  $("resultado-previa").replaceChildren();
  avisar("aviso-historial", "success", `Se borraron ${b.searchRuns || 0} búsqueda(s), ${b.contacts || 0} contacto(s) y `
    + `${b.drafts || 0} borrador(es).`, "Datos Borrados");
  cargarBusquedas();
}

function pintarModoApollo(info) {
  const chip = $("modo-apollo");
  chip.className = `label ${info.apolloSimulado ? "warning" : info.llaveApollo ? "ok" : "medio"}`;
  chip.textContent = info.apolloSimulado ? "Apollo Simulado" : info.llaveApollo ? "Apollo Real" : "Sin Apollo";
  if (info.apolloSimulado) {
    pintarAlerta($("aviso-apollo-simulado"), "alerta", "No hay APOLLO_API_KEY, así que la búsqueda trae personas y "
      + "empresas INVENTADAS (tools/mini-front/apollo_simulado.py, dominios .example). Sirven para ver el flujo completo. "
      + "Cuando haya plan pago de Apollo, se pone la llave en el .env, se reinicia el servicio y la misma búsqueda usa datos reales.",
    "Apollo Simulado");
  } else if (!info.llaveApollo) {
    pintarAlerta($("aviso-apollo-simulado"), "alerta", "No hay APOLLO_API_KEY y el Apollo simulado no está corriendo. "
      + "Arránquelo con la tarea \"Prospección: Apollo simulado (8772)\" y reinicie msa-prospeccion.", "Sin Apollo");
  } else {
    $("aviso-apollo-simulado").className = "alert oculto";
  }
}

function iniciarBusqueda() {
  opcionesFiltro("f-niveles", NIVELES_APOLLO);
  opcionesFiltro("f-tamanos", TAMANOS_APOLLO);
  $("frase-busqueda").addEventListener("input", () => {
    $("contador-frase").textContent = numero($("frase-busqueda").value.length);
  });
  $("frase-busqueda").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      interpretarBusqueda();
    }
  });
  $("interpretar").addEventListener("click", interpretarBusqueda);
  $("ejemplo-busqueda").addEventListener("click", () => {
    $("frase-busqueda").value = EJEMPLO_BUSQUEDA;
    $("contador-frase").textContent = numero(EJEMPLO_BUSQUEDA.length);
    interpretarBusqueda();
  });
  $("vista-previa").addEventListener("click", vistaPrevia);
  $("ejecutar-busqueda").addEventListener("click", ejecutarBusqueda);
  $("recargar-busquedas").addEventListener("click", cargarBusquedas);
  $("repetir-campanas").addEventListener("click", repetirCampanas);
  $("borrar-busquedas").addEventListener("click", borrarBusquedas);
}

// ---------------------------------------------------------------- panel (6.1)

const PASOS_EMBUDO = [
  ["processed", "Personas Nuevas", "Procesadas por las búsquedas del periodo (no vistas antes)"],
  ["qualified", "Calificadas por la IA", "Alcanzaron el umbral del perfil de interés"],
  ["contacts", "Contactos Creados", "Con correo de trabajo verificado y sin exclusiones"],
  ["drafted", "Con Borrador", "Gemini redactó el correo"],
  ["approved", "Aprobados", "Una persona aprobó el correo"],
  ["sent", "Enviados", "Salieron por el buzón (5.2 y 5.4)"],
  ["replied", "Respondieron", "Contestaron el correo (5.4)"],
  ["handedOff", "Pasaron al Vendedor", "Tarea creada en el CRM sobre su prospecto (5.6)"],
];

function porcentaje(valor) {
  return valor == null ? "—" : `${String(valor).replace(".", ",")} %`;
}

function hoyColombia() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Bogota" }).format(new Date());
}

function filtrosPanelPorDefecto() {
  const hoy = hoyColombia();
  $("panel-desde").value = `${hoy.slice(0, 8)}01`;
  $("panel-hasta").value = hoy;
  $("panel-campana").value = "";
}

async function cargarCampanasPanel() {
  const res = await llamar("GET", `${API}/search/runs?page=0&size=100`);
  const actual = $("panel-campana").value;
  const opciones = [el("option", { value: "", texto: "Todas" })];
  if (res.ok) {
    (res.datos.data.content || []).filter((r) => !r.parentSearchRunId).forEach((r) => {
      const texto = `#${r.id} · ${r.naturalQuery || "(Solo filtros)"}${r.scheduled ? " · Campaña" : ""}`;
      opciones.push(el("option", { value: String(r.id), texto: texto.length > 70 ? `${texto.slice(0, 69)}…` : texto }));
    });
  }
  $("panel-campana").replaceChildren(...opciones);
  $("panel-campana").value = actual;
}

async function cargarPanel() {
  ocultar("aviso-panel");
  const desde = $("panel-desde").value;
  const hasta = $("panel-hasta").value;
  if (desde && hasta && hasta < desde) {
    avisar("aviso-panel", "error", "La fecha final es anterior a la inicial.", "Revise el Periodo");
    return;
  }
  const consulta = new URLSearchParams();
  if (desde) consulta.set("from", desde);
  if (hasta) consulta.set("to", hasta);
  if ($("panel-campana").value) consulta.set("searchRunId", $("panel-campana").value);
  $("panel-hoy").replaceChildren(skeleton(4), skeleton(4), skeleton(4));
  $("panel-embudo").replaceChildren(skeleton(7));
  $("panel-tasas").replaceChildren();
  cargarCreditosPanel(); // aparte: el saldo de Hunter puede tardar y no debe frenar el embudo
  const res = await conCarga($("buscar-panel"), () => llamar("GET", `${API}/dashboard?${consulta}`));
  if (!res.ok) {
    $("panel-hoy").replaceChildren();
    $("panel-embudo").replaceChildren();
    avisar("aviso-panel", "error", textoError(res));
    return;
  }
  const d = res.datos.data;
  pintarHoy(d.pending);
  pintarEmbudo(d);
  pintarTasas(d);
}

async function cargarCreditosPanel() {
  $("panel-creditos-mes").replaceChildren(skeleton(5), skeleton(5));
  const res = await llamar("GET", `${API}/credits`);
  $("panel-creditos-mes").replaceChildren(...(res.ok ? res.datos.data.map(tarjetaCredito)
    : [el("p", { clase: "ayuda", texto: textoError(res) })]));
}

function pintarHoy(p) {
  const proximo = partesFecha(p.nextScheduled);
  const ultima = p.lastRun;
  const revisar = kpi(p.draftsToReview ? "medio" : "ok", "Por Revisar", numero(p.draftsToReview),
    p.draftsToReview === 1 ? "borrador" : "borradores", [["Único Paso Humano", "Aprobar el correo"]]);
  if (p.draftsToReview) {
    const ir = el("button", { type: "button", clase: "btn tertiary" }, [el("span", { clase: "icono", texto: "inbox" }), "Ir a la Bandeja"]);
    ir.addEventListener("click", () => irA("panel-bandeja"));
    revisar.append(ir);
  }
  $("panel-hoy").replaceChildren(
    revisar,
    kpi("ok", "En Cola de Envío", numero(p.queued), "correos", [
      ["Próximo Turno", p.nextScheduled ? `${proximo.dia} ${proximo.fecha} ${proximo.hora}` : "—"]]),
    kpi(p.activeCampaigns ? "ok" : "medio", "Campañas Activas", numero(p.activeCampaigns), "diarias o semanales", [
      ["Última Búsqueda", ultima ? `#${ultima.id} · ${partesFecha(ultima.createdDate).fecha}` : "—"],
      ["Estado", ultima ? (ESTADOS_BUSQUEDA[ultima.statusCode] || {}).texto || ultima.statusCode : "—"]]));
}

function pintarEmbudo(d) {
  const f = d.funnel;
  const o = d.outcomes;
  $("panel-subtitulo").textContent = `${d.searchRuns} búsqueda(s) del ${partesFecha(d.from).fecha} al ${partesFecha(d.to).fecha}`
    + (d.searchRunId ? ` · búsqueda #${d.searchRunId} y sus ejecuciones automáticas` : "");
  if (!d.searchRuns) {
    const ir = el("button", { type: "button", clase: "btn primary" }, [el("span", { clase: "icono", texto: "travel_explore" }), "Ir a Búsqueda y Campañas"]);
    ir.addEventListener("click", () => irA("panel-busqueda"));
    $("panel-embudo").replaceChildren(el("div", { clase: "vacio" }, [
      el("span", { clase: "icono", texto: "filter_alt_off" }),
      el("strong", { texto: "Sin Búsquedas en este Periodo" }),
      el("p", { clase: "ayuda", texto: "El embudo cuenta lo que trajeron las búsquedas creadas en el periodo. Cambie las fechas o ejecute una búsqueda." }),
      ir]));
    $("panel-quedaron").replaceChildren();
    return;
  }
  const maximo = Math.max(f.processed, 1);
  const filas = PASOS_EMBUDO.map(([clave, nombre, ayuda], i) => {
    const valor = f[clave];
    const previo = i ? f[PASOS_EMBUDO[i - 1][0]] : null;
    const paso = i && previo ? `${porcentaje(Math.round(valor * 1000 / previo) / 10)} del paso anterior` : i ? "—" : "Base del embudo";
    const ancho = valor ? Math.max(valor / maximo * 100, 0.6) : 0;
    const fila = el("tr", { tabindex: "0", "aria-label": `${nombre}: ${numero(valor)}. ${paso}.` }, [
      el("th", { scope: "row", texto: nombre }),
      el("td", { clase: "embudo-celda" }, [el("span", { clase: "embudo-barra", style: `width:${ancho}%` })]),
      el("td", { clase: "derecha cifra-embudo", texto: numero(valor) }),
      el("td", { clase: "derecha ayuda", texto: i ? porcentaje(previo ? Math.round(valor * 1000 / previo) / 10 : null) : "" }),
    ]);
    const mostrar = (x, y) => mostrarTooltip(x, y, nombre, [[numero(valor), ""], [paso, ""], [ayuda, ""]]);
    fila.addEventListener("pointermove", (e) => mostrar(e.clientX, e.clientY));
    fila.addEventListener("pointerleave", ocultarTooltip);
    fila.addEventListener("focus", () => {
      const r = fila.getBoundingClientRect();
      mostrar(r.left + r.width / 2, r.top);
    });
    fila.addEventListener("blur", ocultarTooltip);
    return fila;
  });
  $("panel-embudo").replaceChildren(el("div", { clase: "tabla-envoltura" }, [el("table", { clase: "tabla embudo" }, [
    el("caption", { clase: "oculto-visual", texto: "Embudo del periodo: cantidad en cada paso y porcentaje frente al paso anterior" }),
    el("colgroup", {}, [el("col", { style: "width:190px" }), el("col"), el("col", { style: "width:90px" }), el("col", { style: "width:90px" })]),
    el("thead", {}, [el("tr", {}, [el("th", { texto: "Paso" }), el("th", { texto: "" }), el("th", { clase: "derecha", texto: "Cantidad" }),
      el("th", { clase: "derecha", texto: "Conversión" })])]),
    el("tbody", {}, filas),
  ])]));
  $("panel-quedaron").replaceChildren(el("dl", { clase: "quedaron" }, [
    ["Descartadas por la IA", o.discardedByAi], ["Sin Correo", o.withoutEmail], ["Excluidas", o.excluded],
    ["Borradores por Revisar", o.draftsToReview], ["Borradores Descartados", o.draftsDiscarded],
    ["En Cola", o.queued], ["Fallidos · Cancelados", `${o.failed} · ${o.cancelled}`],
    ["Rebotes", o.bounced], ["Bajas", o.unsubscribed], ["Aperturas (Aprox.)", o.opened ?? 0],
  ].map(([t, v]) => el("div", {}, [el("dt", { texto: t }), el("dd", { texto: typeof v === "number" ? numero(v) : v })]))));
}

function pintarTasas(d) {
  const r = d.rates;
  const f = d.funnel;
  // Umbrales de alerta de entregabilidad: rebote > 2 % o baja > 1 % piden revisar la lista o el mensaje.
  const estado = (valor, alerta, grave) => valor == null ? "" : valor > grave ? "mal" : valor > alerta ? "medio" : "ok";
  const texto = (valor, alerta, grave) => valor == null ? "Sin Base" : valor > grave ? "Alta" : valor > alerta ? "Revisar" : "Sana";
  $("panel-tasas").replaceChildren(
    kpi("", "Calificación", porcentaje(r.qualification), `de ${numero(f.processed)} nuevas`),
    kpi("", "Aprobación", porcentaje(r.approval), `de ${numero(f.drafted)} borradores`),
    kpi("", "Respuesta", porcentaje(r.reply), `de ${numero(f.sent)} enviados`),
    kpi(estado(r.bounce, 2, 5), "Rebote", porcentaje(r.bounce), `de ${numero(f.sent)} enviados`, [["Estado", texto(r.bounce, 2, 5)]]),
    kpi(estado(r.unsubscribe, 1, 3), "Baja", porcentaje(r.unsubscribe), `de ${numero(f.sent)} enviados`, [["Estado", texto(r.unsubscribe, 1, 3)]]));
}

function mostrarTooltip(x, y, titulo, filas) {
  const t = $("tooltip");
  t.replaceChildren(el("strong", { texto: titulo }), ...filas.map(([a]) => el("div", { texto: a })));
  t.classList.remove("oculto");
  const ancho = t.offsetWidth;
  t.style.left = `${Math.min(Math.max(8, x - ancho / 2), window.innerWidth - ancho - 8)}px`;
  t.style.top = `${Math.max(8, y - t.offsetHeight - 12)}px`;
}

function ocultarTooltip() {
  $("tooltip").classList.add("oculto");
}

function iniciarPanel() {
  filtrosPanelPorDefecto();
  $("buscar-panel").addEventListener("click", cargarPanel);
  $("limpiar-panel").addEventListener("click", () => {
    filtrosPanelPorDefecto();
    cargarPanel();
  });
  $("recargar-panel").addEventListener("click", () => {
    cargarCampanasPanel();
    cargarPanel();
  });
}

// ---------------------------------------------------------------- navegación

const ANCLAS = { "panel-panel": "#panel", "panel-busqueda": "#busqueda", "panel-config": "#configuracion", "panel-calificar": "#calificar", "panel-redactar": "#redactar",
  "panel-correo": "#correo", "panel-bandeja": "#bandeja", "panel-creditos": "#creditos", "panel-cola": "#cola" };

function cambiarPestana(boton) {
  document.querySelectorAll(".tab").forEach((p) => {
    const activa = p === boton;
    p.classList.toggle("activa", activa);
    p.setAttribute("aria-selected", String(activa));
    $(p.dataset.panel).classList.toggle("oculto", !activa);
  });
  history.replaceState(null, "", ANCLAS[boton.dataset.panel] || "#");
  if (boton.dataset.panel === "panel-panel") {
    cargarCampanasPanel();
    cargarPanel();
  }
  if (boton.dataset.panel === "panel-busqueda") cargarBusquedas();
  if (boton.dataset.panel === "panel-creditos") cargarCreditos();
  if (boton.dataset.panel === "panel-bandeja") cargarBandeja(paginaBandeja);
  if (boton.dataset.panel === "panel-cola") {
    cargarCola();
    cargarBloqueos();
    if (!$("tabla-festivos").children.length) cargarFestivos();
  }
}

function irA(panel) {
  cambiarPestana(document.querySelector(`[data-panel="${panel}"]`));
}

function iniciar() {
  iniciarFieldInfo();
  iniciarCola();
  iniciarBandeja();
  iniciarBusqueda();
  iniciarPanel();
  document.querySelectorAll(".tab").forEach((p) => p.addEventListener("click", () => cambiarPestana(p)));

  $("perfil").addEventListener("input", () => { revisarPerfil(); marcarCambios(true); });
  $("mensaje").addEventListener("input", () => { revisarMensaje(); marcarCambios(true); });
  ["umbral", "limite-diario", "espaciado", "linkedin"].forEach((id) =>
    $(id).addEventListener("input", () => marcarCambios(true)));
  $("formatear").addEventListener("click", () => {
    const leido = leerPerfil($("perfil").value);
    if (leido.json) $("perfil").value = JSON.stringify(leido.json, null, 2);
    revisarPerfil();
  });
  $("cargar-perfil").addEventListener("click", () => cargarDocumento("perfil"));
  $("cargar-mensaje").addEventListener("click", () => cargarDocumento("mensaje"));
  $("recargar").addEventListener("click", () => {
    if (!hayCambios || confirm("Hay cambios sin guardar. ¿Descartarlos?")) cargarConfig();
  });
  $("guardar").addEventListener("click", guardar);

  $("agregar").addEventListener("click", () => agregarContacto());
  $("ejemplos").addEventListener("click", () => {
    $("contactos").replaceChildren();
    EJEMPLOS.forEach((e) => agregarContacto(e));
  });
  $("limpiar").addEventListener("click", () => {
    $("contactos").replaceChildren();
    $("resultados").replaceChildren();
    ocultar("aviso-calificar");
    agregarContacto();
  });
  $("calificar").addEventListener("click", calificar);

  $("ejemplo-redactar").addEventListener("click", () => {
    camposRedactar().forEach((c) => { c.value = EJEMPLO_REDACTAR[c.dataset.campo] ?? ""; });
  });
  $("redactar").addEventListener("click", redactar);
  $("recargar-creditos").addEventListener("click", cargarCreditos);
  $("buscar-apollo").addEventListener("click", () => buscarApollo(1));
  $("limpiar-apollo").addEventListener("click", () => {
    camposApollo().forEach((c) => { c.value = ""; });
    $("resultados-apollo").replaceChildren();
    ocultar("aviso-apollo");
    camposApollo()[0].focus();
  });
  $("conseguir-correo").addEventListener("click", conseguirCorreo);
  $("buscar-hunter").addEventListener("click", buscarConHunter);
  $("apollo-id").addEventListener("keydown", (e) => { if (e.key === "Enter") conseguirCorreo(); });
  document.querySelectorAll("[data-ir]").forEach((enlace) => enlace.addEventListener("click", (evento) => {
    evento.preventDefault();
    irA(enlace.dataset.ir);
  }));

  window.addEventListener("beforeunload", (evento) => {
    if (hayCambios) evento.preventDefault();
  });

  const ancla = location.hash; // se lee antes de cambiar de pestaña, que la reescribe
  if (ancla.startsWith("#calificar")) {
    irA("panel-calificar");
  } else if (ancla.startsWith("#redactar")) {
    irA("panel-redactar");
    if (ancla === "#redactar-ejemplo") $("ejemplo-redactar").click();
  } else if (ancla === "#panel") {
    irA("panel-panel");
  } else if (ancla === "#busqueda") {
    irA("panel-busqueda");
  } else if (ancla === "#configuracion") {
    irA("panel-config");
  } else if (ancla === "#creditos") {
    irA("panel-creditos");
  } else if (ancla === "#correo") {
    irA("panel-correo");
  } else if (ancla === "#cola") {
    irA("panel-cola");
  } else if (ancla === "#bandeja") {
    irA("panel-bandeja");
  }
  if (ancla === "#calificar-ejemplos") {
    EJEMPLOS.forEach((e) => agregarContacto(e));
  } else {
    agregarContacto();
  }
  mostrarEstado().then((arriba) => {
    if (arriba) cargarConfig();
    else {
      $("version").textContent = "Sin Conexión";
      avisar("aviso-config", "error",
        "El servicio no responde. Arránquelo (ver tools/mini-front/README.md) y recargue esta página.");
    }
  });
}

iniciar();
