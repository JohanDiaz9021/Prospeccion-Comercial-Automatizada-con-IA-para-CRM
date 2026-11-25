# Cambios de Prospección IA para los repos del CRM

Este ZIP trae los cambios del módulo Prospección IA (microservicio `msa-prospeccion-crm`) en los repos del CRM. Se hicieron en local, en la rama `feature/prospeccion-ia` creada desde `feature/deployCrm`, porque el equipo del módulo solo tiene permiso de lectura.

`CONTENIDO.md` dice qué repos traen cambios. Cada carpeta de repo trae:

- `parches/`: un archivo `.patch` por commit. Se pueden leer como texto para revisar qué líneas cambian.
- `commits.txt`: la lista de commits en orden.
- `<repo>.bundle`: la misma rama completa, como respaldo.

## Opción A: aplicar los parches (recomendada)

En cada repo que venga en el ZIP:

```powershell
git fetch origin
git switch -c feature/prospeccion-ia origin/feature/deployCrm
git am --3way <ruta>\<repo>\parches\*.patch
```

Si hay un conflicto, `git am` se detiene y muestra el archivo. Para resolverlo:

```powershell
# editar el archivo en conflicto
git add <archivo>
git am --continue
# o, para cancelar todo: git am --abort
```

Al terminar, revisar con `git log origin/feature/deployCrm..feature/prospeccion-ia`, subir la rama y abrir el PR hacia `feature/deployCrm`:

```powershell
git push -u origin feature/prospeccion-ia
```

## Opción B: traer la rama desde el bundle

```powershell
git fetch origin
git fetch <ruta>\<repo>\<repo>.bundle feature/prospeccion-ia:feature/prospeccion-ia
git switch feature/prospeccion-ia
git rebase origin/feature/deployCrm   # solo si deployCrm avanzó después de la entrega
```

## Contexto

- Qué cambia en cada repo y por qué: `docs/crm/peticion-completa-y-dominio-de-prospectos.md` del repo `int-automatizacion-de-captura-de-leads-con-ia`.
- Los parches no incluyen llaves, contraseñas ni archivos `.env`.
