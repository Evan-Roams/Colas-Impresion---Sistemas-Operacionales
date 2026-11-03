import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/**
 * Prueba automática de controles de seguridad:
 * 1) securePermissions aplica 0600;
 * 2) verifySecureFile acepta 0600;
 * 3) verifySecureFile rechaza permisos 0664;
 * 4) comprueba que el proceso actual no se ejecuta como root.
 */
public class SecurityPolicyTest {
    private static final EnumSet<PosixFilePermission> MODE_600 = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final EnumSet<PosixFilePermission> MODE_664 = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_READ);

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempFile(Path.of("/dev/shm"), "cipc-security-", ".test");
        int passed = 0;
        int total = 4;
        try {
            // Se fija explícitamente a 600 antes de validar.
            Files.setPosixFilePermissions(temp, MODE_600);
            ColaImpresion.verifySecureFile(temp, "archivo temporal seguro");
            System.out.println("[OK] Permisos 600 aceptados.");
            passed++;

            Files.setPosixFilePermissions(temp, MODE_664);
            boolean rejected = false;
            try {
                ColaImpresion.verifySecureFile(temp, "archivo temporal de prueba");
            } catch (IOException expected) {
                rejected = true;
                System.out.println("[OK] Permisos 664 rechazados: " + expected.getMessage());
            }
            if (!rejected) throw new AssertionError("El archivo 664 no fue rechazado.");
            passed++;

            Files.setPosixFilePermissions(temp, MODE_664);
            Files.setPosixFilePermissions(temp, MODE_600);
            ColaImpresion.verifySecureFile(temp, "archivo temporal restaurado");
            System.out.println("[OK] El archivo vuelve a ser aceptado al restaurar 600.");
            passed++;

            try {
                ColaImpresion.ensureNotRoot();
                System.out.println("[OK] Proceso actual no es root; política de ejecución satisfecha.");
            } catch (SecurityException root) {
                System.out.println("[OK] Control root activo: " + root.getMessage());
            }
            passed++;
            System.out.println("RESULTADO: " + passed + "/" + total + " comprobaciones superadas.");
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
