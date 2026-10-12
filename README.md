# Cola de impresión compartida con IPC — avance Checkpoint 2

Proyecto académico para Sistemas Operacionales. Implementación inicial en Java para Linux, con procesos JVM independientes y una cola circular almacenada en un archivo mapeado en `/dev/shm`.

## Requisitos
- Linux (usar la máquina virtual Kali del curso).
- JDK 17 o superior (`java -version`, `javac -version`).

## Compilar y ejecutar
Desde la carpeta `src`:

```bash
javac ColaImpresion.java
java ColaImpresion server 3 2 4
```

El ejemplo inicia 3 procesos cliente, 2 procesos impresora y 4 trabajos por cliente (12 trabajos esperados). Para una prueba corta, se puede usar `java ColaImpresion server 2 2 2`.

Al finalizar:
- La bitácora queda en `src/bitacora_impresion.log` (ruta relativa al directorio desde el que se ejecuta el comando).
- El archivo compartido temporal está en `/dev/shm/cola_impresion_ipc.dat`.

Para revisar los eventos:

```bash
cat bitacora_impresion.log
```

Para limpiar manualmente el archivo temporal después de terminar todos los procesos:

```bash
rm -f /dev/shm/cola_impresion_ipc.dat
```

## Qué demuestra esta versión
- El servidor crea e inicializa la cola compartida y lanza procesos separados mediante `ProcessBuilder`.
- Los clientes productores insertan trabajos en un búfer circular de capacidad limitada.
- Las impresoras consumidoras extraen trabajos, simulan la impresión y registran eventos.
- `FileLock` protege las secciones críticas que leen o modifican los índices y contadores compartidos.
- El sondeo temporizado permite esperar cuando la cola está llena o vacía.

## Limitaciones conocidas de este avance
- Esta versión usa `FileLock` y sondeo temporizado; todavía no implementa semáforos POSIX nativos. Por eso, en la documentación no debe afirmarse que ya existen semáforos POSIX reales.
- La impresión es simulada; no se usa una impresora física.
- Antes de entregar, ejecutar en la VM Kali del grupo y guardar capturas propias de la terminal y de la bitácora. Una prueba ejecutada en otro Linux no sustituye la evidencia de Kali si la profesora pide mostrar ese entorno.
- Revisar la bitácora y contar los trabajos terminados para cada configuración probada.
