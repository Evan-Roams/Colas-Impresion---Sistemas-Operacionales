import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;

/**
 * Prueba auxiliar para demostrar una condición de carrera entre JVM independientes.
 * Cada proceso incrementa un contador en memoria mapeada compartida.
 * Modo sin-sync: read-modify-write sin protección.
 * Modo sync: la misma sección crítica protegida con FileLock.
 * No reemplaza la cola de impresión; es una prueba controlada del concepto de región crítica.
 */
public class ComparacionCarrera {
    private static final Path FILE = Paths.get("/dev/shm/carrera_ipc.dat");
    private static final int PROCESSES = 4;
    private static final int ITERATIONS = 50000;
    private static final EnumSet<PosixFilePermission> PERMS = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static ByteBuffer map(FileChannel ch) throws IOException {
        return ch.map(FileChannel.MapMode.READ_WRITE, 0, Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
    }

    private static void worker(boolean sync, int iterations) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(FILE.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            ByteBuffer b = map(ch);
            for (int i = 0; i < iterations; i++) {
                if (sync) {
                    try (FileLock ignored = ch.lock()) {
                        int value = b.getInt(0);
                        Thread.yield();
                        b.putInt(0, value + 1);
                    }
                } else {
                    int value = b.getInt(0);
                    Thread.yield();
                    b.putInt(0, value + 1);
                }
            }
        }
    }

    private static int readValue() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(FILE.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            return map(ch).getInt(0);
        }
    }

    private static int run(boolean sync) throws Exception {
        Files.createDirectories(FILE.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(FILE.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            raf.setLength(Integer.BYTES);
            map(ch).putInt(0, 0);
        }
        Files.setPosixFilePermissions(FILE, PERMS);

        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Process[] children = new Process[PROCESSES];
        for (int i = 0; i < PROCESSES; i++) {
            children[i] = new ProcessBuilder(java, "-cp", cp,
                ComparacionCarrera.class.getName(), "worker", sync ? "sync" : "nosync",
                Integer.toString(ITERATIONS)).inheritIO().start();
        }
        int failures = 0;
        for (Process p : children) if (p.waitFor() != 0) failures++;
        int expected = PROCESSES * ITERATIONS;
        int actual = readValue();
        System.out.println("Modo: " + (sync ? "SINCRONIZADO (FileLock)" : "SIN SINCRONIZACIÓN"));
        System.out.println("Procesos: " + PROCESSES);
        System.out.println("Incrementos por proceso: " + ITERATIONS);
        System.out.println("Valor esperado: " + expected);
        System.out.println("Valor obtenido: " + actual);
        System.out.println("Actualizaciones perdidas: " + (expected - actual));
        System.out.println("Procesos con error: " + failures);
        System.out.println("Archivo compartido: " + FILE);
        return actual;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("worker")) {
            worker(args[1].equals("sync"), Integer.parseInt(args[2]));
            return;
        }
        if (args.length != 1 || !(args[0].equals("sync") || args[0].equals("nosync"))) {
            System.out.println("Uso: java ComparacionCarrera sync|nosync");
            return;
        }
        run(args[0].equals("sync"));
    }
}
