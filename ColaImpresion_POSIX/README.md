# Cola de impresión compartida con IPC — Kali Linux

## 1. Descripción
Aplicación académica de productor-consumidor que simula una cola de impresión compartida por varios procesos JVM independientes. Un proceso servidor inicializa la cola y crea clientes productores e impresoras consumidoras.

## 2. Arquitectura y tecnologías
- Java (JDK 17 o superior).
- Kali Linux (todos los procesos se ejecutan en la misma máquina).
- Memoria compartida mediante un archivo mapeado en `/dev/shm/cola_impresion_ipc.dat`.
- Semáforos POSIX con nombre (`/cipc_mutex`, `/cipc_vacios`, `/cipc_llenos`) invocados desde Java mediante JNA.
- `ProcessBuilder` para crear procesos JVM independientes.
- `FileLock` únicamente para serializar las escrituras de la bitácora.
- Permisos POSIX `0600` para la cola, la bitácora y los semáforos.

## 3. Requisitos
- Kali Linux.
- JDK 17 o superior.
- JNA, normalmente disponible como `/usr/share/java/jna.jar`.

Instalación en Kali:
```bash
sudo apt update
sudo apt install -y default-jdk libjna-java
```

## 4. Ejecución normal
Ejecute como usuario normal (no root), desde la carpeta raíz:
```bash
chmod +x run.sh
./run.sh 3 2 4
```
Parámetros: número de clientes, número de impresoras y trabajos por cliente. El ejemplo genera 12 trabajos.

La ejecución se rechaza si el usuario efectivo es root. La aplicación verifica que el archivo compartido sea un archivo regular, no un enlace simbólico y tenga exactamente permisos `600` antes de utilizarlo.

## 5. Archivos generados
- Cola compartida: `/dev/shm/cola_impresion_ipc.dat`
- Bitácora: `./bitacora_impresion.log`

Comprobar permisos:
```bash
stat -c '%a %A %U %G %n' /dev/shm/cola_impresion_ipc.dat bitacora_impresion.log
```
Ambos deben mostrar `600 -rw-------`.

## 6. Prueba automática de seguridad
Ejecute:
```bash
chmod +x test-security.sh
./test-security.sh
```
La prueba crea un archivo temporal aislado en `/dev/shm` y comprueba que:
1. Un archivo con permisos `600` sea aceptado.
2. Un archivo con permisos `664` sea rechazado.
3. El archivo vuelva a aceptarse al restaurar `600`.
4. La política de ejecución no-root se evalúe para el usuario actual.

La prueba no modifica la cola real ni la bitácora del proyecto.

## 7. Variante experimental sin sincronización
Solo para comparación académica; no ejecutar al mismo tiempo que la versión sincronizada porque ambas utilizan la misma ruta de cola.
```bash
chmod +x run_sin_sync.sh
./run_sin_sync.sh 3 2 4
```
Bitácora: `bitacora_sin_sync.log`. Esta variante omite los semáforos de la cola y contiene pausas artificiales para ampliar las ventanas de carrera. El resultado puede variar entre ejecuciones.

## 8. Prueba auxiliar de condición de carrera
La clase `ComparacionCarrera` compara incrementos concurrentes en memoria mapeada con y sin `FileLock`:
```bash
chmod +x run-carrera.sh
./run-carrera.sh nosync
./run-carrera.sh sync
```
Esta prueba auxiliar usa `FileLock` para aislar el concepto de exclusión mutua; no sustituye los semáforos POSIX usados por la cola principal.

## 9. Limitaciones y uso seguro
- Ejecutar una sola instancia del servidor a la vez: se utilizan nombres y rutas fijas en `/dev/shm`.
- Los permisos `0600` y el rechazo de root son controles locales; no proporcionan cifrado ni integridad criptográfica de la bitácora.
- La comprobación de permisos se realiza antes de abrir/usar el archivo, pero una modificación externa concurrente entre la comprobación y la apertura (TOCTOU) no queda eliminada completamente.
- Si el servidor se interrumpe abruptamente, pueden quedar semáforos POSIX con nombre. Antes de limpiar recursos, cierre cualquier instancia activa y revise `/dev/shm/sem.cipc_*`.
- La variante sin sincronización es intencionalmente insegura y nunca debe usarse para operaciones reales.

## 10. JNA y advertencias de acceso nativo
El script de ejecución utiliza `--enable-native-access=ALL-UNNAMED` para habilitar el acceso nativo requerido por JNA en versiones modernas de Java.
