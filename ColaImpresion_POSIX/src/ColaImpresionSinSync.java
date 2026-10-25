import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Variante experimental SIN sincronización.
 * Se comparte el mismo formato de archivo que ColaImpresion, pero se omiten
 * los semáforos y los bloqueos en la cola. Las pausas artificiales amplían
 * las ventanas de carrera para fines demostrativos.
 * No usar para trabajo real ni ejecutar simultáneamente con ColaImpresion.
 */
public class ColaImpresionSinSync {
    private static final int MAGIC = 0x43495043;
    private static final int HEADER_SIZE = 32;
    private static final int SLOT_SIZE = 256;
    private static final int CAPACITY = 5;
    private static final Path SHM = Paths.get("/dev/shm/cola_impresion_ipc.dat");
    private static final Path LOG = Paths.get("./bitacora_sin_sync.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static ByteBuffer map(FileChannel ch) throws IOException {
        return ch.map(FileChannel.MapMode.READ_WRITE, 0,
                HEADER_SIZE + (long) CAPACITY * SLOT_SIZE).order(ByteOrder.BIG_ENDIAN);
    }

    private static void log(String message) throws IOException {
        String line = "[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator();
        // La bitácora también carece de bloqueo en esta variante experimental.
        try (FileChannel ch = FileChannel.open(LOG, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer data = StandardCharsets.UTF_8.encode(line);
            while (data.hasRemaining()) ch.write(data);
        }
    }

    private static void initialize(int totalJobs) throws IOException {
        Files.createDirectories(SHM.getParent());
        Files.deleteIfExists(SHM);
        Files.deleteIfExists(LOG);
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            raf.setLength(HEADER_SIZE + (long) CAPACITY * SLOT_SIZE);
            ByteBuffer b = map(ch);
            b.putInt(0, MAGIC);
            b.putInt(4, CAPACITY);
            b.putInt(8, 0); b.putInt(12, 0); b.putInt(16, 0);
            b.putInt(20, totalJobs); b.putInt(24, 0); b.putInt(28, 1);
        }
        log("SERVIDOR SIN SYNC: cola inicializada; trabajos previstos=" + totalJobs);
    }

    private static int enqueue(String text) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            while (true) {
                ByteBuffer b = map(ch);
                if (b.getInt(0) != MAGIC) throw new IOException("Archivo compartido inválido.");
                int count = b.getInt(16);
                if (count < CAPACITY) {
                    int tail = b.getInt(12);
                    int id = b.getInt(28);
                    // Ventana artificial para facilitar observar lecturas/escrituras intercaladas.
                    Thread.sleep(8);
                    int offset = HEADER_SIZE + tail * SLOT_SIZE;
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    int len = Math.min(bytes.length, SLOT_SIZE - 8);
                    b.putInt(offset, id);
                    b.putInt(offset + 4, len);
                    for (int i = 0; i < len; i++) b.put(offset + 8 + i, bytes[i]);
                    Thread.sleep(5);
                    b.putInt(12, (tail + 1) % CAPACITY);
                    b.putInt(16, count + 1);
                    b.putInt(28, id + 1);
                    return id;
                }
                Thread.sleep(5);
            }
        }
    }

    private static String dequeue() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            while (true) {
                ByteBuffer b = map(ch);
                if (b.getInt(0) != MAGIC) throw new IOException("Archivo compartido inválido.");
                int count = b.getInt(16);
                int completed = b.getInt(24);
                int total = b.getInt(20);
                if (count > 0) {
                    int head = b.getInt(8);
                    int offset = HEADER_SIZE + head * SLOT_SIZE;
                    Thread.sleep(8);
                    int id = b.getInt(offset);
                    int len = b.getInt(offset + 4);
                    if (len < 0 || len > SLOT_SIZE - 8) {
                        log("ERROR DE CARRERA: longitud inválida en ranura: " + len);
                        len = Math.max(0, Math.min(len, SLOT_SIZE - 8));
                    }
                    byte[] bytes = new byte[len];
                    for (int i = 0; i < len; i++) bytes[i] = b.get(offset + 8 + i);
                    b.putInt(8, (head + 1) % CAPACITY);
                    b.putInt(16, count - 1);
                    return id + "|" + new String(bytes, StandardCharsets.UTF_8);
                }
                if (completed >= total) return null;
                Thread.sleep(5);
            }
        }
    }

    private static void markCompleted() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            ByteBuffer b = map(ch);
            int old = b.getInt(24);
            Thread.sleep(5);
            b.putInt(24, old + 1);
        }
    }

    private static void client(String name, int jobs) throws Exception {
        for (int i = 1; i <= jobs; i++) {
            String description = "Trabajo de " + name + " #" + i;
            int id = enqueue(description);
            log("CLIENTE " + name + ": encoló ID=" + id + " (" + description + ")");
            Thread.sleep(10);
        }
    }

    private static void printer(String name) throws Exception {
        while (true) {
            String item = dequeue();
            if (item == null) break;
            String[] parts = item.split("\\|", 2);
            log("IMPRESORA " + name + ": inicia ID=" + parts[0] + " (" + parts[1] + ")");
            Thread.sleep(35);
            markCompleted();
            log("IMPRESORA " + name + ": terminó ID=" + parts[0]);
        }
        log("IMPRESORA " + name + ": finalizó");
    }

    private static void launch(List<Process> processes, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(System.getProperty("java.class.path"));
        command.add(ColaImpresionSinSync.class.getName());
        command.addAll(List.of(args));
        processes.add(new ProcessBuilder(command).inheritIO().start());
    }

    private static int server(int clients, int printers, int jobsPerClient) throws Exception {
        int total = Math.multiplyExact(clients, jobsPerClient);
        initialize(total);
        List<Process> children = new ArrayList<>();
        for (int i = 1; i <= printers; i++) launch(children, "printer", "P" + i);
        for (int i = 1; i <= clients; i++)
            launch(children, "client", "C" + i, Integer.toString(jobsPerClient));
        int exit = 0;
        // Watchdog: una carrera puede corromper los contadores y dejar procesos
        // esperando indefinidamente. Limita la demostración a 30 segundos.
        long deadline = System.currentTimeMillis() + 30_000L;
        boolean timedOut = false;
        for (Process p : children) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0 || !p.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                exit = 1;
                break;
            }
            if (p.exitValue() != 0) exit = 1;
        }
        if (timedOut) {
            log("WATCHDOG: tiempo límite alcanzado; se detienen procesos restantes por posible bloqueo debido a carreras.");
            for (Process p : children) {
                if (p.isAlive()) p.destroyForcibly();
            }
            for (Process p : children) p.waitFor(2, TimeUnit.SECONDS);
        }
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            ByteBuffer b = map(ch);
            int completed = b.getInt(24);
            int count = b.getInt(16);
            log("RESUMEN SIN SYNC: esperados=" + total + ", contador completados="
                    + completed + ", pendientes según cabecera=" + count
                    + ", watchdog=" + (timedOut ? "activado" : "no activado"));
            System.out.println("Variante SIN sincronización terminada.");
            if (timedOut) System.out.println("AVISO: se alcanzó el límite de 30 s; posible bloqueo por condición de carrera.");
            System.out.println("Esperados: " + total + "; contador completados: "
                    + completed + "; pendientes: " + count);
            System.out.println("Bitácora: " + LOG.toAbsolutePath());
        }
        return exit;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help")) {
            System.out.println("Uso: java ColaImpresionSinSync server [clientes] [impresoras] [trabajosPorCliente]");
            return;
        }
        switch (args[0]) {
            case "server" -> {
                int clients = args.length > 1 ? Integer.parseInt(args[1]) : 3;
                int printers = args.length > 2 ? Integer.parseInt(args[2]) : 2;
                int jobs = args.length > 3 ? Integer.parseInt(args[3]) : 4;
                if (clients < 1 || printers < 1 || jobs < 1)
                    throw new IllegalArgumentException("Parámetros deben ser mayores que cero.");
                System.exit(server(clients, printers, jobs));
            }
            case "client" -> client(args[1], Integer.parseInt(args[2]));
            case "printer" -> printer(args[1]);
            default -> throw new IllegalArgumentException("Rol desconocido: " + args[0]);
        }
    }
}
