import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Cola de impresión compartida entre procesos independientes de Java en Linux.
 * Memoria compartida: archivo mapeado en /dev/shm.
 * Sincronización: semáforos POSIX con nombre (mutex, vacios, llenos).
 * FileLock se conserva únicamente para serializar escrituras de la bitácora.
 */
public class ColaImpresion {
    private interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);
        Pointer sem_open(String name, int oflag, int mode, int value);
        int sem_wait(Pointer sem);
        int sem_trywait(Pointer sem);
        int sem_post(Pointer sem);
        int sem_close(Pointer sem);
        int sem_unlink(String name);
        int __errno_location();
    }

    private static final int O_CREAT = 64; // Linux
    private static final int O_EXCL = 128; // Linux
    private static final int SEM_MODE = 0600;

    private static final String SEM_MUTEX = "/cipc_mutex";
    private static final String SEM_EMPTY = "/cipc_vacios";
    private static final String SEM_FULL = "/cipc_llenos";

    private static final int MAGIC = 0x43495043; // CIPC
    private static final int HEADER_SIZE = 32;
    private static final int SLOT_SIZE = 256;
    private static final int CAPACITY = 5;
    private static final Path SHM = Paths.get("/dev/shm/cola_impresion_ipc.dat");
    private static final Path LOG = Paths.get("./bitacora_impresion.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final EnumSet<PosixFilePermission> OWNER_RW =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static ByteBuffer map(FileChannel ch) throws IOException {
        return ch.map(FileChannel.MapMode.READ_WRITE, 0,
                HEADER_SIZE + (long) CAPACITY * SLOT_SIZE).order(ByteOrder.BIG_ENDIAN);
    }

    private static void securePermissions(Path path) throws IOException {
        Files.setPosixFilePermissions(path, OWNER_RW);
    }

    private static void log(String message) throws IOException {
        String line = "[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator();
        try (FileChannel ch = FileChannel.open(LOG, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            securePermissions(LOG);
            try (FileLock ignored = ch.lock()) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(line);
                while (data.hasRemaining()) ch.write(data);
            }
        }
    }

    private static Pointer openSemaphore(String name) throws IOException {
        Pointer p = LibC.INSTANCE.sem_open(name, 0, SEM_MODE, 0);
        if (p == null || Pointer.nativeValue(p) == -1L) {
            throw new IOException("No se pudo abrir el semáforo POSIX " + name
                    + ". Verifique JNA y los recursos del sistema.");
        }
        return p;
    }

    private static void waitSem(Pointer sem) throws IOException {
        while (LibC.INSTANCE.sem_wait(sem) != 0) {
            throw new IOException("sem_wait falló.");
        }
    }

    private static void postSem(Pointer sem) throws IOException {
        if (LibC.INSTANCE.sem_post(sem) != 0) throw new IOException("sem_post falló.");
    }

    private static void closeSem(Pointer sem) {
        if (sem != null && Pointer.nativeValue(sem) != -1L) LibC.INSTANCE.sem_close(sem);
    }

    private static void unlinkSemaphores() {
        LibC.INSTANCE.sem_unlink(SEM_MUTEX);
        LibC.INSTANCE.sem_unlink(SEM_EMPTY);
        LibC.INSTANCE.sem_unlink(SEM_FULL);
    }

    private static void initialize(int totalJobs) throws IOException {
        Files.createDirectories(SHM.getParent());
        Files.deleteIfExists(SHM);
        Files.deleteIfExists(LOG);

        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            raf.setLength(HEADER_SIZE + (long) CAPACITY * SLOT_SIZE);
            securePermissions(SHM);
            try (FileLock ignored = ch.lock()) {
                ByteBuffer b = map(ch);
                b.putInt(0, MAGIC);
                b.putInt(4, CAPACITY);
                b.putInt(8, 0);  // head
                b.putInt(12, 0); // tail
                b.putInt(16, 0); // count
                b.putInt(20, totalJobs);
                b.putInt(24, 0); // completed
                b.putInt(28, 1); // next ID
            }
        }

        Files.createFile(LOG);
        securePermissions(LOG);

        // Evita reutilizar semáforos abandonados por una ejecución anterior.
        // Ejecutar solo una instancia del servidor a la vez.
        unlinkSemaphores();
        createSemaphore(SEM_MUTEX, 1);
        createSemaphore(SEM_EMPTY, CAPACITY);
        createSemaphore(SEM_FULL, 0);

        log("SERVIDOR: cola inicializada; capacidad=" + CAPACITY
                + ", trabajos previstos=" + totalJobs);
    }

    private static void createSemaphore(String name, int initialValue) throws IOException {
        Pointer p = LibC.INSTANCE.sem_open(name, O_CREAT | O_EXCL, SEM_MODE, initialValue);
        if (p == null || Pointer.nativeValue(p) == -1L) {
            unlinkSemaphores();
            throw new IOException("No se pudo crear el semáforo " + name
                    + ". Compruebe que no haya otra instancia del servidor.");
        }
        closeSem(p);
    }

    private static int enqueue(String text) throws Exception {
        Pointer mutex = openSemaphore(SEM_MUTEX);
        Pointer empty = openSemaphore(SEM_EMPTY);
        Pointer full = openSemaphore(SEM_FULL);
        boolean reservedSlot = false;
        try {
            waitSem(empty);
            reservedSlot = true;
            waitSem(mutex);
            try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
                 FileChannel ch = raf.getChannel()) {
                ByteBuffer b = map(ch);
                validate(b);
                int tail = b.getInt(12);
                int id = b.getInt(28);
                int offset = HEADER_SIZE + tail * SLOT_SIZE;
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                int len = Math.min(bytes.length, SLOT_SIZE - 8);
                b.putInt(offset, id);
                b.putInt(offset + 4, len);
                for (int i = 0; i < len; i++) b.put(offset + 8 + i, bytes[i]);
                b.putInt(12, (tail + 1) % CAPACITY);
                b.putInt(16, b.getInt(16) + 1);
                b.putInt(28, id + 1);
                reservedSlot = false;
                returnAndPost(mutex, full);
                return id;
            } catch (Exception e) {
                postSem(mutex);
                if (reservedSlot) postSem(empty);
                throw e;
            }
        } finally {
            closeSem(mutex);
            closeSem(empty);
            closeSem(full);
        }
    }

    private static void returnAndPost(Pointer mutex, Pointer full) throws IOException {
        postSem(mutex);
        postSem(full);
    }

    private static String dequeue() throws Exception {
        Pointer mutex = openSemaphore(SEM_MUTEX);
        Pointer empty = openSemaphore(SEM_EMPTY);
        Pointer full = openSemaphore(SEM_FULL);
        try {
            // No se puede bloquear indefinidamente en "llenos", porque las
            // impresoras deben detectar cuándo ya terminaron todos los trabajos.
            while (true) {
                if (LibC.INSTANCE.sem_trywait(full) == 0) break;
                try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
                     FileChannel ch = raf.getChannel(); FileLock ignored = ch.lock()) {
                    ByteBuffer b = map(ch);
                    validate(b);
                    if (b.getInt(24) >= b.getInt(20) && b.getInt(16) == 0) return null;
                }
                Thread.sleep(25);
            }

            waitSem(mutex);
            try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
                 FileChannel ch = raf.getChannel()) {
                ByteBuffer b = map(ch);
                validate(b);
                int head = b.getInt(8);
                int offset = HEADER_SIZE + head * SLOT_SIZE;
                int id = b.getInt(offset);
                int len = b.getInt(offset + 4);
                byte[] bytes = new byte[Math.max(0, Math.min(len, SLOT_SIZE - 8))];
                for (int i = 0; i < bytes.length; i++) bytes[i] = b.get(offset + 8 + i);
                b.putInt(8, (head + 1) % CAPACITY);
                b.putInt(16, b.getInt(16) - 1);
                postSem(mutex);
                postSem(empty);
                return id + "|" + new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                postSem(mutex);
                postSem(full);
                throw e;
            }
        } finally {
            closeSem(mutex);
            closeSem(empty);
            closeSem(full);
        }
    }

    private static void markCompleted() throws Exception {
        Pointer mutex = openSemaphore(SEM_MUTEX);
        try {
            waitSem(mutex);
            try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
                 FileChannel ch = raf.getChannel()) {
                ByteBuffer b = map(ch);
                validate(b);
                b.putInt(24, b.getInt(24) + 1);
            } finally {
                postSem(mutex);
            }
        } finally {
            closeSem(mutex);
        }
    }

    private static void validate(ByteBuffer b) throws IOException {
        if (b.getInt(0) != MAGIC || b.getInt(4) != CAPACITY)
            throw new IOException("Archivo compartido inválido o incompatible.");
    }

    private static void client(String name, int jobs) throws Exception {
        for (int i = 1; i <= jobs; i++) {
            String description = "Trabajo de " + name + " #" + i;
            int id = enqueue(description);
            log("CLIENTE " + name + ": encoló trabajo ID=" + id
                    + " (" + description + ")");
            Thread.sleep(35);
        }
    }

    private static void printer(String name) throws Exception {
        while (true) {
            String item = dequeue();
            if (item == null) break;
            String[] parts = item.split("\\|", 2);
            log("IMPRESORA " + name + ": inicia trabajo ID=" + parts[0]
                    + " (" + parts[1] + ")");
            Thread.sleep(120);
            markCompleted();
            log("IMPRESORA " + name + ": terminó trabajo ID=" + parts[0]);
        }
        log("IMPRESORA " + name + ": finalizó; no quedan trabajos pendientes");
    }

    private static void launch(List<Process> processes, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ColaImpresion.class.getName());
        command.addAll(List.of(args));
        processes.add(new ProcessBuilder(command).inheritIO().start());
    }

    private static int server(int clients, int printers, int jobsPerClient) throws Exception {
        int total = Math.multiplyExact(clients, jobsPerClient);
        initialize(total);
        List<Process> children = new ArrayList<>();
        try {
            for (int i = 1; i <= printers; i++) launch(children, "printer", "P" + i);
            for (int i = 1; i <= clients; i++)
                launch(children, "client", "C" + i, Integer.toString(jobsPerClient));

            int exit = 0;
            for (Process p : children) if (p.waitFor() != 0) exit = 1;
            log("SERVIDOR: procesos finalizados. Total esperado=" + total);
            System.out.println("\nDemo terminada. Trabajos esperados: " + total);
            System.out.println("Bitácora: " + LOG.toAbsolutePath());
            System.out.println("Archivo compartido: " + SHM);
            System.out.println("Permisos esperados: 600 (solo propietario)");
            return exit;
        } finally {
            unlinkSemaphores();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help")) {
            System.out.println("Uso: ./run.sh [clientes] [impresoras] [trabajosPorCliente]");
            System.out.println("Ejemplo: ./run.sh 3 2 4");
            return;
        }
        switch (args[0]) {
            case "server" -> {
                int clients = args.length > 1 ? Integer.parseInt(args[1]) : 3;
                int printers = args.length > 2 ? Integer.parseInt(args[2]) : 2;
                int jobs = args.length > 3 ? Integer.parseInt(args[3]) : 4;
                if (clients < 1 || printers < 1 || jobs < 1)
                    throw new IllegalArgumentException("Todos los parámetros deben ser mayores que cero.");
                System.exit(server(clients, printers, jobs));
            }
            case "client" -> client(args[1], Integer.parseInt(args[2]));
            case "printer" -> printer(args[1]);
            default -> throw new IllegalArgumentException("Rol desconocido: " + args[0]);
        }
    }
}
