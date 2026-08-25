package odk.common;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Convertit un XLSForm (fichier Excel) en XML ODK via la CLI "xls2xform" de
 * pyxform (communaute ODK), deja installee sur cette machine (pip install
 * pyxform). Chemin absolu utilise plutot que le simple nom de commande : le
 * processus Tomcat n'a pas le meme PATH qu'une session utilisateur normale
 * (confirme en pratique - "xls2xform" seul echoue depuis Tomcat alors qu'il
 * fonctionne dans un terminal). Adapter XLS2XFORM_EXE si Python est installe
 * ailleurs ou sur une autre machine (meme logique que TOMCAT_HOME dans les
 * compile.bat). --skip_validate evite de dependre en plus de "ODK Validate"
 * (un outil Java externe separe, absent ici) ; les verifications internes de
 * pyxform suffisent pour detecter un XLSForm mal forme.
 */
public final class XlsFormConverter {

    private static final String XLS2XFORM_EXE =
            "C:\\Users\\MSI GE68\\AppData\\Local\\Programs\\Python\\Python312\\Scripts\\xls2xform.exe";

    private static final int TIMEOUT_SECONDS = 60;

    private XlsFormConverter() {
    }

    public static final class ConversionResult {
        public final boolean success;
        public final byte[] xmlBytes;
        public final String message;

        private ConversionResult(boolean success, byte[] xmlBytes, String message) {
            this.success = success;
            this.xmlBytes = xmlBytes;
            this.message = message;
        }

        static ConversionResult ok(byte[] xmlBytes, String message) {
            return new ConversionResult(true, xmlBytes, message);
        }

        static ConversionResult failed(String message) {
            return new ConversionResult(false, null, message);
        }
    }

    public static ConversionResult convert(byte[] xlsxBytes) throws IOException {
        File input = File.createTempFile("xlsform_in_", ".xlsx");
        File output = File.createTempFile("xlsform_out_", ".xml");
        try {
            Files.write(input.toPath(), xlsxBytes);
            // Le fichier de sortie ne doit pas deja exister pour que xls2xform le (re)cree proprement.
            output.delete();

            ProcessBuilder pb = new ProcessBuilder(
                    XLS2XFORM_EXE, "--skip_validate", input.getAbsolutePath(), output.getAbsolutePath());
            pb.redirectErrorStream(true);

            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                return ConversionResult.failed("Impossible de lancer xls2xform (chemin configure : "
                        + XLS2XFORM_EXE + ") : " + e.getMessage());
            }

            String processOutput = readAll(process.getInputStream());
            boolean finished = false;
            try {
                finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!finished) {
                process.destroyForcibly();
                return ConversionResult.failed("La conversion a dépassé le délai de " + TIMEOUT_SECONDS + " secondes.");
            }

            if (process.exitValue() != 0 || !output.exists() || output.length() == 0) {
                String message = processOutput == null || processOutput.trim().isEmpty()
                        ? "Échec de la conversion (code " + process.exitValue() + ")."
                        : processOutput.trim();
                return ConversionResult.failed(message);
            }

            byte[] xml = Files.readAllBytes(output.toPath());
            return ConversionResult.ok(xml, processOutput);
        } finally {
            input.delete();
            output.delete();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }
}
