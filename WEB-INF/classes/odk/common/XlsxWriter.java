package odk.common;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Générateur minimal de fichier .xlsx (OOXML SpreadsheetML) - un classeur, une seule feuille,
 * cellules texte en "inline strings" (évite un sharedStrings.xml séparé). Aucune dépendance externe
 * (pas d'Apache POI dans ce projet) : c'est un vrai fichier Excel valide, juste écrit à la main -
 * même esprit que le parseur/générateur CSV RFC4180 déjà écrit à la main dans EditSubmissionServlet.
 * Référence du format : ECMA-376 (Office Open XML), partie SpreadsheetML.
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    /** headers.size() doit correspondre a la longueur de chaque Object[] dans rows. Valeurs supportées : null, Boolean, Integer/Long/Short, BigDecimal/Double/Float, ou toString() en texte. */
    public static void write(OutputStream destination, String sheetName, List<String> headers, List<Object[]> rows)
            throws IOException {
        StringBuilder sheetXml = new StringBuilder();
        sheetXml.append("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>");
        sheetXml.append("<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData>");

        appendRow(sheetXml, 1, headers.toArray());
        int r = 2;
        for (Object[] row : rows) {
            appendRow(sheetXml, r++, row);
        }
        sheetXml.append("</sheetData></worksheet>");

        try (ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML);
            writeEntry(zip, "_rels/.rels", RELS_XML);
            writeEntry(zip, "xl/workbook.xml", workbookXml(sanitizeSheetName(sheetName)));
            writeEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML);
            writeEntry(zip, "xl/worksheets/sheet1.xml", sheetXml.toString());
        }
    }

    private static void appendRow(StringBuilder sb, int rowNum, Object[] values) {
        sb.append("<row r='").append(rowNum).append("'>");
        for (int i = 0; i < values.length; i++) {
            appendCell(sb, columnLetters(i) + rowNum, values[i]);
        }
        sb.append("</row>");
    }

    private static void appendCell(StringBuilder sb, String ref, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof Boolean) {
            sb.append("<c r='").append(ref).append("' t='b'><v>").append(((Boolean) v) ? 1 : 0).append("</v></c>");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            sb.append("<c r='").append(ref).append("'><v>").append(v).append("</v></c>");
        } else if (v instanceof BigDecimal) {
            sb.append("<c r='").append(ref).append("'><v>").append(((BigDecimal) v).toPlainString()).append("</v></c>");
        } else if (v instanceof Double || v instanceof Float) {
            sb.append("<c r='").append(ref).append("'><v>").append(v).append("</v></c>");
        } else {
            String text = stripInvalidXmlChars(v.toString());
            sb.append("<c r='").append(ref).append("' t='inlineStr'><is><t xml:space='preserve'>")
                    .append(xmlEscape(text)).append("</t></is></c>");
        }
    }

    /** Colonne Excel (A, B, ..., Z, AA, AB, ...) pour un index de colonne 0-based. */
    private static String columnLetters(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String sanitizeSheetName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Soumissions";
        }
        String safe = name.replaceAll("[\\\\/*?:\\[\\]]", "_");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Retire les caractères de contrôle interdits en XML 1.0 (garde tabulation/saut de ligne/retour chariot). */
    private static String stripInvalidXmlChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String workbookXml(String sheetName) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
                + "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' "
                + "xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'>"
                + "<sheets><sheet name='" + xmlEscape(sheetName) + "' sheetId='1' r:id='rId1'/></sheets></workbook>";
    }

    private static final String CONTENT_TYPES_XML =
            "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
            + "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
            + "<Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/>"
            + "<Default Extension='xml' ContentType='application/xml'/>"
            + "<Override PartName='/xl/workbook.xml' "
            + "ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'/>"
            + "<Override PartName='/xl/worksheets/sheet1.xml' "
            + "ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml'/>"
            + "</Types>";

    private static final String RELS_XML =
            "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
            + "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
            + "<Relationship Id='rId1' "
            + "Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' "
            + "Target='xl/workbook.xml'/></Relationships>";

    private static final String WORKBOOK_RELS_XML =
            "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
            + "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
            + "<Relationship Id='rId1' "
            + "Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' "
            + "Target='worksheets/sheet1.xml'/></Relationships>";
}
