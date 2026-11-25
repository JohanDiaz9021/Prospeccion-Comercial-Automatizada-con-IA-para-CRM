# Trabajar en los repos del CRM sin permiso de escritura

Solo tenemos permiso de lectura en `gtc-crm-root`, `mf-cotizaciones`, `msa-apigateway-crm` y `msa-params-crm`. Los cambios se hacen en local, en la rama `feature/prospeccion-ia`, y se le entregan al programador del CRM en un ZIP. Él aplica los cambios, resuelve los conflictos y abre el PR.

Los repos deben estar en la misma carpeta que este repo (`CRM COMPLETO\`).

## 1. Al empezar (una sola vez por repo)

Ya está hecho en los 4 repos. Si alguno se vuelve a clonar:

```powershell
cd "C:\Users\GTC-096\Desktop\CRM COMPLETO"
git clone -b feature/deployCrm https://bitbucket.org/gestion-tecnologica-y-contable/<repo>.git
git -C <repo> switch --no-track -c feature/prospeccion-ia origin/feature/deployCrm
```

## 2. Cada vez que se trabaja

```powershell
cd "C:\Users\GTC-096\Desktop\CRM COMPLETO\<repo>"
git switch feature/prospeccion-ia      # siempre trabajar en esta rama
# ... editar ...
git status                             # revisar qué cambió
git add <archivos>                     # solo los archivos que tocamos, nunca .env ni build/
git commit -m "2.4 Ruta /msa-prospeccion/** en el gateway"
```

- Un commit por tarea, con el número de la tarea en el mensaje.
- **No** usar `git push`: Bitbucket responde 403.
- **No** borrar el `.gitignore`: es lo que evita meter `node_modules/`, `build/` o `.env` en los commits.

## 3. Una vez por semana: ponerse al día con el CRM

```powershell
git fetch origin
git switch feature/prospeccion-ia
git rebase origin/feature/deployCrm
```

Se usa `rebase` y no `merge`: así los parches se aplican sobre lo último del CRM. Como la rama nunca se sube, el rebase es seguro. Si hay un conflicto: se edita el archivo, `git add <archivo>` y `git rebase --continue`.

## 4. Para entregar: generar el ZIP

Desde la raíz de este repo:

```powershell
powershell -ExecutionPolicy Bypass -File tools\entrega-crm\generar-entrega.ps1
```

El script:

1. revisa que cada repo esté en `feature/prospeccion-ia` y sin cambios pendientes;
2. avisa si el CRM avanzó y conviene hacer el paso 3 antes de entregar;
3. genera los parches, un bundle de respaldo y la lista de commits de cada repo con cambios;
4. agrega [INSTRUCCIONES-PARA-CRM.md](INSTRUCCIONES-PARA-CRM.md) y comprime todo en `CRM COMPLETO\entregas-prospeccion-ia\entrega_<fecha>.zip`.

Ese ZIP es el que se envía. **Respaldo:** como los commits solo existen en este PC, conviene generar el ZIP cada tanto (aunque no se envíe) y guardarlo en OneDrive.
