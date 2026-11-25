# Plantilla de pull request

Bitbucket toma la descripción por defecto de los PR desde la configuración del repositorio (*Repository settings → Pull requests → Default description*). Pega ahí el bloque de abajo. También sirve para copiarlo a mano en cada PR.

```markdown
## Tarea

<!-- Número y nombre de la tarea del plan, por ejemplo: 2.3 Multi-tenancy y seguridad del servicio -->

## Qué cambia

-

## Cómo se probó

- [ ] `gradlew build` sin errores
- [ ] Probado en local con el perfil `local`
- [ ] Pruebas nuevas o actualizadas (o se explica por qué no aplica)

## Revisión

- [ ] Sin llaves, contraseñas, tokens ni datos reales de personas en el código, los logs o los ejemplos
- [ ] Textos para el usuario con claves `lbl*` (no texto fijo)
- [ ] Tablas del CRM (`pm_*`, `sc_*`, `sy_*`) solo por API o en solo lectura
- [ ] Documentación actualizada si cambia un endpoint, una tabla o una variable de entorno

## Pendientes o riesgos

-
```
