# Prueba de cobertura de Apollo y Hunter (tarea 1.7)

Mide, antes de construir, cuántos gerentes colombianos del perfil de interés tiene Apollo y a cuántos se les puede escribir (correo verificado). Si la cobertura es baja, se reevalúa el proveedor.

## Qué necesitas

1. **Cuenta gratis de Apollo creada con un correo corporativo** (la API gratis lo exige). En Apollo: *Settings → Integrations → API → Create new key*. Dale acceso a `mixed_people/api_search` y `people/match`, o crea una *master key*.
2. **Cuenta gratis de Hunter.** La llave está en *Dashboard → API keys*. Solo hace falta para la etapa B.
3. **Python 3.10 o superior.** No hay que instalar nada más.
4. **Las llaves en el `.env` de la raíz del repo** (nunca en el código ni en el chat):

   ```
   APOLLO_API_KEY=...
   HUNTER_API_KEY=...
   ```

## Cómo se corre

Desde la raíz del repo, en PowerShell:

```powershell
# 0. Ver qué se va a llamar, sin llamar a nada
python tools/prueba-cobertura/prueba_cobertura.py --simular

# 1. Etapa A: 30 búsquedas, 0 créditos (unas 60 llamadas, 2 o 3 minutos)
python tools/prueba-cobertura/prueba_cobertura.py

# 2. Etapa B (opcional, gasta créditos): 20 personas con correo y 10 sin correo
python tools/prueba-cobertura/prueba_cobertura.py --etapa b --muestra-con-correo 20 --muestra-sin-correo 10
```

Con `--solo B01,B02` se corren solo algunas búsquedas. Antes de gastar, la etapa B muestra el gasto máximo y pide confirmación.

## Qué mide

| Etapa | Qué hace | Costo |
|---|---|---|
| A | Por cada búsqueda de [busquedas.json](busquedas.json): total de gerentes (`total_entries`), cuántos de los primeros 100 tienen correo (`has_email`) y cuántos tienen correo verificado (filtro `contact_email_status[]=verified`) | 0 créditos (People API Search) |
| B | Muestra con correo en Apollo: se pide el correo (People Enrichment) y Hunter lo verifica. Muestra sin correo en Apollo: Hunter intenta encontrarlo con el dominio y el nombre | Apollo: 1 crédito por correo entregado y 1 por datos demográficos. Hunter: 1 búsqueda solo si encuentra el correo y 1 verificación por correo |

Las 30 búsquedas salen del perfil de interés aprobado:
- 15: las 5 ciudades por los 3 sectores de prioridad alta;
- 10: Bogotá y Medellín por los 5 sectores de prioridad media;
- 3: por grupo de cargos (TI, gerencia general, operaciones y finanzas) en toda Colombia;
- 1: empresas de 501 a 1.000 empleados;
- 1: el resto de Colombia, fuera de las 5 ciudades.

Se pueden editar en `busquedas.json` antes de correr la prueba.

## Resultados

Quedan en `tools/prueba-cobertura/resultados/`, carpeta que **no se sube a git** porque la etapa B tiene datos de personas:

- `resumen.md`: totales, porcentajes por búsqueda, por ciudad y por sector. **Es lo que se comparte.**
- `etapa_a_busquedas.csv`: cifras por búsqueda.
- `etapa_a_personas.json` y `etapa_b_muestra.csv`: datos de personas (correos enmascarados). No se comparten.

## Cómo decidir (propuesta para acordar con el equipo)

| % de gerentes con correo verificado (etapa A) | Decisión sugerida |
|---|---|
| 40 % o más | Seguir con Apollo; Hunter solo como respaldo |
| 20 % a 39 % | Seguir con Apollo + Hunter; revisar en la etapa B cuánto rescata Hunter antes de elegir el plan |
| Menos de 20 % | Reevaluar el proveedor antes de construir las fases 3 y 4 |

**Plan:** con los porcentajes de la etapa B se calculan los créditos al mes para el volumen previsto (por ejemplo, 1.000 prospectos calificados) y se compara con los planes de Apollo y Hunter de la presentación.
