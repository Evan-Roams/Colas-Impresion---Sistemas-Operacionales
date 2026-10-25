# Cola de impresión compartida con IPC (Kali Linux)

## Arquitectura
- Java con procesos JVM independientes (`ProcessBuilder`).
- Cola circular compartida mediante un archivo mapeado en `/dev/shm`.
- Semáforos POSIX con nombre de Linux, llamados `mutex`, `vacios` y `llenos`, accedidos desde Java con JNA.
- `FileLock` se usa solo para serializar escrituras de la bitácora.
- Permisos POSIX `600` para el archivo compartido y la bitácora.

Todo corre en la misma máquina Kali Linux. No se necesita servidor remoto ni conexión de red.

## Requisitos
- Kali Linux.
- JDK 17 o superior.
- JNA disponible como `/usr/share/java/jna.jar`.

Instala las dependencias:
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk libjna-java
```

## Compilar y ejecutar
Desde la carpeta raíz del proyecto:
```bash
chmod +x run.sh
./run.sh 3 2 4
```
Esto crea 3 clientes, 2 impresoras y 12 trabajos en total. También se puede ejecutar con otros números:
```bash
./run.sh 2 2 10
```

El script compila con JNA y pasa el classpath a los procesos hijos. La cola queda en:
`/dev/shm/cola_impresion_ipc.dat`

La bitácora queda en:
`./bitacora_impresion.log`

## Verificar resultados y permisos
Después de una ejecución:
```bash
cat bitacora_impresion.log
stat -c '%a %A %U %G %n' /dev/shm/cola_impresion_ipc.dat bitacora_impresion.log
```
Los permisos esperados para ambos archivos son `600` (`-rw-------`). La salida de la bitácora debe mostrar los trabajos encolados, iniciados y terminados.

## Limpieza de semáforos si una ejecución se interrumpe
El servidor elimina los semáforos con nombre al terminar normalmente. Si el programa se termina abruptamente, revisa `/dev/shm/sem.cipc_*` antes de borrar residuos. Cierra primero cualquier ejecución activa.
No ejecutes dos instancias del servidor a la vez: ambas usan rutas fijas en `/dev/shm`.

## Limitaciones académicas
Esta versión requiere Linux/POSIX y la biblioteca JNA. No usa `java.util.concurrent.Semaphore`, porque ese tipo coordina hilos dentro de una JVM y no constituye por sí solo un semáforo compartido entre procesos independientes.

## Variante experimental sin sincronización
Esta variante se incluye para comparación académica y es intencionalmente insegura.
No la ejecutes al mismo tiempo que la versión sincronizada porque ambas usan la misma ruta `/dev/shm/cola_impresion_ipc.dat`.

```bash
./run_sin_sync.sh 3 2 4
```

Revisa:
```bash
cat bitacora_sin_sync.log
```
El resultado puede variar entre ejecuciones. Una ejecución que por casualidad termine con los contadores esperados no demuestra que el código sea seguro; repite la prueba con más concurrencia, por ejemplo `./run_sin_sync.sh 8 4 20`. Esta variante amplía las ventanas de carrera mediante pausas artificiales para facilitar su observación.
