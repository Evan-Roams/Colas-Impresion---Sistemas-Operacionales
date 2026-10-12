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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Demo académica: procesos JVM independientes y una cola circular en un archivo mapeado.
 * FileLock protege las operaciones críticas; el sondeo temporizado gestiona cola llena/vacía.
 */
public class ColaImpresion {
    private static final int MAGIC = 0x43495043; // CIPC
    private static final int HEADER_SIZE = 32;
    private static final int SLOT_SIZE = 256;
    private static final int CAPACITY = 5;
    private static final Path SHM = Paths.get("/dev/shm/cola_impresion_ipc.dat");
    private static final Path LOG = Paths.get("./bitacora_impresion.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Header: magic, capacity, head, tail, count, totalJobs, completed, nextId.
    private static ByteBuffer map(FileChannel ch) throws IOException {
        return ch.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_SIZE + (long) CAPACITY * SLOT_SIZE).order(ByteOrder.BIG_ENDIAN);
    }

    private static void log(String message) throws IOException {
        String line = "[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator();
        try (FileChannel ch = FileChannel.open(LOG, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            try (FileLock ignored = ch.lock()) {
                ch.write(StandardCharsets.UTF_8.encode(line));
            }
        }
    }

    private static void initialize(int totalJobs) throws IOException {
        Files.createDirectories(SHM.getParent());
        Files.deleteIfExists(SHM);
        Files.deleteIfExists(LOG);
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            raf.setLength(HEADER_SIZE + (long) CAPACITY * SLOT_SIZE);
            try (FileLock ignored = ch.lock()) {
                ByteBuffer b = map(ch);
                b.putInt(0, MAGIC); b.putInt(4, CAPACITY); b.putInt(8, 0); b.putInt(12, 0);
                b.putInt(16, 0); b.putInt(20, totalJobs); b.putInt(24, 0); b.putInt(28, 1);
            }
        }
        log("SERVIDOR: cola inicializada; capacidad=" + CAPACITY + ", trabajos previstos=" + totalJobs);
    }

    private static int enqueue(String text) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            while (true) {
                try (FileLock ignored = ch.lock()) {
                    ByteBuffer b = map(ch); validate(b);
                    int count = b.getInt(16);
                    if (count < CAPACITY) {
                        int tail = b.getInt(12), id = b.getInt(28);
                        int offset = HEADER_SIZE + tail * SLOT_SIZE;
                        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                        int len = Math.min(bytes.length, SLOT_SIZE - 8);
                        b.putInt(offset, id); b.putInt(offset + 4, len);
                        for (int i = 0; i < len; i++) b.put(offset + 8 + i, bytes[i]);
                        b.putInt(12, (tail + 1) % CAPACITY); b.putInt(16, count + 1); b.putInt(28, id + 1);
                        return id;
                    }
                }
                Thread.sleep(25);
            }
        }
    }

    private static String dequeue() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            while (true) {
                try (FileLock ignored = ch.lock()) {
                    ByteBuffer b = map(ch); validate(b);
                    int count = b.getInt(16), completed = b.getInt(24), total = b.getInt(20);
                    if (count > 0) {
                        int head = b.getInt(8), offset = HEADER_SIZE + head * SLOT_SIZE;
                        int id = b.getInt(offset), len = b.getInt(offset + 4);
                        byte[] bytes = new byte[Math.max(0, Math.min(len, SLOT_SIZE - 8))];
                        for (int i = 0; i < bytes.length; i++) bytes[i] = b.get(offset + 8 + i);
                        b.putInt(8, (head + 1) % CAPACITY); b.putInt(16, count - 1);
                        return id + "|" + new String(bytes, StandardCharsets.UTF_8);
                    }
                    if (completed >= total) return null;
                }
                Thread.sleep(25);
            }
        }
    }

    private static void markCompleted() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(SHM.toFile(), "rw"); FileChannel ch = raf.getChannel(); FileLock ignored = ch.lock()) {
            ByteBuffer b = map(ch); validate(b); b.putInt(24, b.getInt(24) + 1);
        }
    }

    private static void validate(ByteBuffer b) throws IOException {
        if (b.getInt(0) != MAGIC || b.getInt(4) != CAPACITY) throw new IOException("Archivo compartido inválido o incompatible.");
    }

    private static void client(String name, int jobs) throws Exception {
        for (int i = 1; i <= jobs; i++) {
            String description = "Trabajo de " + name + " #" + i;
            int id = enqueue(description);
            log("CLIENTE " + name + ": encoló trabajo ID=" + id + " (" + description + ")");
            Thread.sleep(35);
        }
    }

    private static void printer(String name) throws Exception {
        while (true) {
            String item = dequeue();
            if (item == null) break;
            String[] parts = item.split("\\|", 2);
            log("IMPRESORA " + name + ": inicia trabajo ID=" + parts[0] + " (" + parts[1] + ")");
            Thread.sleep(120);
            markCompleted();
            log("IMPRESORA " + name + ": terminó trabajo ID=" + parts[0]);
        }
        log("IMPRESORA " + name + ": finalizó; no quedan trabajos pendientes");
    }

    private static void launch(List<Process> processes, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(System.getProperty("java.class.path")); command.add(ColaImpresion.class.getName());
        for (String arg : args) command.add(arg);
        processes.add(new ProcessBuilder(command).inheritIO().start());
    }

    private static int server(int clients, int printers, int jobsPerClient) throws Exception {
        int total = clients * jobsPerClient;
        initialize(total);
        List<Process> children = new ArrayList<>();
        for (int i = 1; i <= printers; i++) launch(children, "printer", "P" + i);
        for (int i = 1; i <= clients; i++) launch(children, "client", "C" + i, Integer.toString(jobsPerClient));
        int exit = 0;
        for (Process p : children) if (p.waitFor() != 0) exit = 1;
        log("SERVIDOR: procesos finalizados. Total esperado=" + total);
        System.out.println("\nDemo terminada. Trabajos esperados: " + total);
        System.out.println("Bitácora: " + LOG.toAbsolutePath());
        System.out.println("Archivo compartido: " + SHM);
        return exit;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help")) {
            System.out.println("Uso: java ColaImpresion server [clientes] [impresoras] [trabajosPorCliente]");
            System.out.println("Ejemplo: java ColaImpresion server 3 2 4"); return;
        }
        switch (args[0]) {
            case "server" -> {
                int clients = args.length > 1 ? Integer.parseInt(args[1]) : 3;
                int printers = args.length > 2 ? Integer.parseInt(args[2]) : 2;
                int jobs = args.length > 3 ? Integer.parseInt(args[3]) : 4;
                if (clients < 1 || printers < 1 || jobs < 1) throw new IllegalArgumentException("Todos los parámetros deben ser mayores que cero.");
                System.exit(server(clients, printers, jobs));
            }
            case "client" -> client(args[1], Integer.parseInt(args[2]));
            case "printer" -> printer(args[1]);
            default -> throw new IllegalArgumentException("Rol desconocido: " + args[0]);
        }
    }
}
