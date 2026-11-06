package odk.editsubmission;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.opendatakit.aggregate.ContextFactory;
import org.opendatakit.common.web.CallingContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import odk.common.ActivityLog;
import odk.common.FormDeletionUtil;
import odk.common.FormSchemaUtil;
import odk.common.FormUploadUtil;
import odk.common.NavBar;
import odk.common.XlsFormConverter;
import odk.common.XlsxWriter;

/**
 * Admin-only tool (see /editSubmission intercept-url, ROLE_DATA_OWNER) to edit
 * scalar values already submitted for any form, directly in its "_CORE" table.
 * Column/table names used in SQL always come from the _form_data_model /
 * information_schema metadata resolved server-side, never from the request -
 * only bound parameter values come from user input.
 */
public class EditSubmissionServlet extends HttpServlet {

    private static final int PAGE_SIZE = 100;

    private static class ColumnMeta {
        final String elementName;
        final String columnName;
        final String sqlType;

        ColumnMeta(String elementName, String columnName, String sqlType) {
            this.elementName = elementName;
            this.columnName = columnName;
            this.sqlType = sqlType;
        }
    }

    /** Supplies a field's raw string value regardless of whether it comes from request params or a CSV row. */
    private interface ValueSource {
        String get(String columnName);
    }

    private static class GeoField {
        final String label;
        final String latColumn;
        final String lngColumn;

        GeoField(String label, String latColumn, String lngColumn) {
            this.label = label;
            this.latColumn = latColumn;
            this.lngColumn = lngColumn;
        }
    }

    private DataSource getDataSource() {
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        return (DataSource) ctx.getBean("dataSource");
    }

    private static final String MIGRATION_CORRECTIONS = "corrections_v1";

    @Override
    public void init() throws ServletException {
        try (Connection conn = getDataSource().getConnection(); java.sql.Statement st = conn.createStatement()) {
            FormSchemaUtil.ensureSubmissionTables(st);
            int migrated = migrateEditsToCorrections(conn);
            if (migrated >= 0) {
                getServletContext().log("EditSubmissionServlet: reprise des modifications en corrections terminée ("
                        + migrated + " champ(s) remis au brut).");
            }
        } catch (SQLException e) {
            getServletContext().log("EditSubmissionServlet: échec de la reprise des modifications en corrections", e);
        }
    }

    /**
     * Reprise unique (marquée dans odk_prod.app_migration) des modifications faites AVANT la couche
     * de corrections, quand elles écrasaient directement la table ODK. Pour chaque champ modifié
     * d'après l'historique, la plus ancienne valeur "avant" est la valeur brute du terrain : on la
     * remet dans la table ODK, et la valeur actuelle devient une correction. Les modifications
     * antérieures à l'historique ne sont pas récupérables (leur valeur actuelle reste le brut).
     * Retourne le nombre de champs remis au brut, ou -1 si la reprise était déjà faite.
     */
    private static int migrateEditsToCorrections(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM odk_prod.app_migration WHERE name = ?")) {
            ps.setString(1, MIGRATION_CORRECTIONS);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return -1;
                }
            }
        }
        // form_id -> uri -> colonne -> plus ancienne valeur "avant" (= brut d'origine)
        Map<String, Map<String, Map<String, String>>> rawByForm = new java.util.LinkedHashMap<String, Map<String, Map<String, String>>>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT form_id, submission_uri, changes "
                + "FROM odk_prod.app_submission_history WHERE action IN (?,?,?) AND changes IS NOT NULL "
                + "ORDER BY changed_at, id")) {
            ps.setString(1, HISTORY_UPDATE);
            ps.setString(2, HISTORY_UPDATE_CSV);
            ps.setString(3, HISTORY_REVERT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<FieldChange> changes = GSON.<List<FieldChange>>fromJson(rs.getString(3), FIELD_CHANGE_LIST);
                    if (changes == null) {
                        continue;
                    }
                    Map<String, Map<String, String>> byUri = rawByForm.get(rs.getString(1));
                    if (byUri == null) {
                        byUri = new java.util.LinkedHashMap<String, Map<String, String>>();
                        rawByForm.put(rs.getString(1), byUri);
                    }
                    Map<String, String> byColumn = byUri.get(rs.getString(2));
                    if (byColumn == null) {
                        byColumn = new java.util.LinkedHashMap<String, String>();
                        byUri.put(rs.getString(2), byColumn);
                    }
                    for (FieldChange c : changes) {
                        if (!byColumn.containsKey(c.column)) {
                            byColumn.put(c.column, c.before);
                        }
                    }
                }
            }
        }

        int restored = 0;
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (Map.Entry<String, Map<String, Map<String, String>>> formEntry : rawByForm.entrySet()) {
                String formId = formEntry.getKey();
                String coreTable;
                try {
                    coreTable = resolveCoreTableOrThrow(conn, formId);
                } catch (SQLException e) {
                    continue; // formulaire supprimé depuis
                }
                Map<String, ColumnMeta> byName = new HashMap<String, ColumnMeta>();
                for (ColumnMeta col : listEditableColumns(conn, coreTable)) {
                    byName.put(col.columnName, col);
                }
                for (Map.Entry<String, Map<String, String>> uriEntry : formEntry.getValue().entrySet()) {
                    String uri = uriEntry.getKey();
                    Map<String, Object> current = new HashMap<String, Object>();
                    // Sans filtre corbeille : une soumission à la corbeille doit aussi retrouver son brut.
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" = ?")) {
                        ps.setString(1, uri);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                continue; // soumission supprimée définitivement
                            }
                            for (String column : uriEntry.getValue().keySet()) {
                                if (byName.containsKey(column)) {
                                    current.put(column, rs.getObject(column));
                                }
                            }
                        }
                    }
                    for (Map.Entry<String, String> colEntry : uriEntry.getValue().entrySet()) {
                        ColumnMeta col = byName.get(colEntry.getKey());
                        if (col == null) {
                            continue;
                        }
                        Object currentValue = current.get(col.columnName);
                        Object rawValue = typedValue(col, colEntry.getValue());
                        if (sameValue(col, rawValue, currentValue)) {
                            continue;
                        }
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO odk_prod.app_submission_correction(submission_uri, column_name, form_id, value, "
                                        + "corrected_by) VALUES (?,?,?,?,?) ON CONFLICT (submission_uri, column_name) DO NOTHING")) {
                            ps.setString(1, uri);
                            ps.setString(2, col.columnName);
                            ps.setString(3, formId);
                            ps.setString(4, historyValue(currentValue));
                            ps.setString(5, "reprise");
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE odk_prod.\"" + coreTable + "\" SET \""
                                + col.columnName + "\" = ? WHERE \"_URI\" = ?")) {
                            bindValue(ps, 1, col, colEntry.getValue());
                            ps.setString(2, uri);
                            ps.executeUpdate();
                        }
                        restored++;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO odk_prod.app_migration(name) VALUES (?)")) {
                ps.setString(1, MIGRATION_CORRECTIONS);
                ps.executeUpdate();
            }
            conn.commit();
            return restored;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * ROLE_DATA_VIEWER (Lecteur de données) peut désormais accéder à /editSubmission (voir
     * l'intercept-url) pour consulter/télécharger, mais toute création/modification/suppression
     * reste réservée à ROLE_DATA_OWNER (Gestionnaire de formulaires) - vérifié explicitement ici à
     * chaque action d'écriture, en plus de masquer les boutons correspondants côté affichage.
     */
    private boolean isDataOwner() {
        return FormSchemaUtil.currentUserHasRole("ROLE_DATA_OWNER", getServletContext());
    }

    private void renderAccessDenied(PrintWriter out, String backHref, String backLabel) {
        writeHeader(out, "Accès refusé");
        out.println("<p class='err'>Votre rôle (Lecteur de données) permet de consulter et télécharger, "
                + "mais pas de créer, modifier ou supprimer.</p>");
        out.println("<p class='back-link'><a href='" + backHref + "'>&laquo; " + htmlEscape(backLabel) + "</a></p>");
        writeFooter(out);
    }

    private void denyPost(HttpServletResponse resp) throws IOException {
        resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                "Action non autorisée pour votre rôle (Lecteur de données : consultation/téléchargement uniquement).");
    }

    private static void checkIdentifier(String identifier) throws SQLException {
        FormSchemaUtil.checkIdentifier(identifier);
    }

    private static String resolveSubmissionDataModelUri(Connection conn, String formId) throws SQLException {
        return FormSchemaUtil.resolveSubmissionDataModelUri(conn, formId);
    }

    private static String resolveCoreTable(Connection conn, String submissionDataModelUri) throws SQLException {
        return FormSchemaUtil.resolveCoreTable(conn, submissionDataModelUri);
    }

    /**
     * META_INSTANCE_ID (le champ meta &lt;instanceID&gt; qu'ODK ajoute automatiquement à chaque
     * formulaire) est un vrai élément de _form_data_model (ELEMENT_TYPE=STRING), donc listé ici
     * comme n'importe quelle autre colonne - mais insertSubmissionRow le gère déjà explicitement
     * en dur (toujours = _URI de la nouvelle soumission) : sans cette exclusion il apparaît deux
     * fois dans l'INSERT généré et Postgres rejette la requête ("column ... specified more than
     * once"). Une table core partagée par plusieurs versions d'un même formulaire peut aussi, en
     * théorie, avoir plusieurs lignes pointant vers la même colonne physique - dédupliquer par nom
     * de colonne (la première rencontrée, dans l'ordre ORDINAL_NUMBER, est conservée) reste donc
     * une sécurité supplémentaire.
     */
    private static List<ColumnMeta> listEditableColumns(Connection conn, String coreTable) throws SQLException {
        checkIdentifier(coreTable);
        String sql = "SELECT dm.\"ELEMENT_NAME\", dm.\"PERSIST_AS_COLUMN_NAME\", c.data_type "
                + "FROM odk_prod.\"_form_data_model\" dm "
                + "JOIN information_schema.columns c "
                + "  ON c.table_schema = 'odk_prod' AND c.table_name = dm.\"PERSIST_AS_TABLE_NAME\" "
                + "  AND c.column_name = dm.\"PERSIST_AS_COLUMN_NAME\" "
                + "WHERE dm.\"PERSIST_AS_TABLE_NAME\" = ? "
                + "  AND dm.\"PERSIST_AS_COLUMN_NAME\" IS NOT NULL "
                + "  AND dm.\"PERSIST_AS_COLUMN_NAME\" <> 'META_INSTANCE_ID' "
                + "  AND dm.\"ELEMENT_TYPE\" NOT IN ('GROUP','REPEAT','BINARY') "
                + "ORDER BY dm.\"ORDINAL_NUMBER\"";
        Map<String, ColumnMeta> byColumnName = new java.util.LinkedHashMap<String, ColumnMeta>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, coreTable);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString(2);
                    checkIdentifier(columnName);
                    if (!byColumnName.containsKey(columnName)) {
                        byColumnName.put(columnName, new ColumnMeta(rs.getString(1), columnName, rs.getString(3)));
                    }
                }
            }
        }
        return new ArrayList<ColumnMeta>(byColumnName.values());
    }

    private static String resolveCoreTableOrThrow(Connection conn, String formId) throws SQLException {
        return FormSchemaUtil.resolveCoreTableOrThrow(conn, formId);
    }

    private static String currentUserName() {
        return SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : "unknown";
    }

    /**
     * A geopoint splits into 4 sibling columns (_LAT/_LNG/_ALT/_ACC) sharing the same
     * ELEMENT_NAME in _form_data_model - matched here by PERSIST_AS_COLUMN_NAME suffix
     * since ELEMENT_NAME alone isn't unique enough to key on.
     */
    private static List<GeoField> findGeoFields(List<ColumnMeta> columns) {
        java.util.Map<String, String> latByPrefix = new java.util.LinkedHashMap<String, String>();
        java.util.Map<String, String> lngByPrefix = new java.util.HashMap<String, String>();
        java.util.Map<String, String> labelByPrefix = new java.util.HashMap<String, String>();
        for (ColumnMeta col : columns) {
            String name = col.columnName;
            if (name.endsWith("_LAT")) {
                String prefix = name.substring(0, name.length() - 4);
                latByPrefix.put(prefix, name);
                labelByPrefix.put(prefix, col.elementName);
            } else if (name.endsWith("_LNG") || name.endsWith("_LON")) {
                String prefix = name.substring(0, name.length() - 4);
                lngByPrefix.put(prefix, name);
            }
        }
        List<GeoField> fields = new ArrayList<GeoField>();
        for (java.util.Map.Entry<String, String> e : latByPrefix.entrySet()) {
            String lngColumn = lngByPrefix.get(e.getKey());
            if (lngColumn != null) {
                fields.add(new GeoField(labelByPrefix.get(e.getKey()), e.getValue(), lngColumn));
            }
        }
        return fields;
    }

    private static String csvEscape(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Minimal RFC4180 CSV parser: handles quoted fields, embedded commas/newlines, and "" escapes. */
    private static List<String[]> parseCsv(String content) {
        List<String[]> rows = new ArrayList<String[]>();
        List<String> row = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowStarted = false;
        int len = content.length();
        int i = 0;
        while (i < len) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(ch);
                i++;
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
                rowStarted = true;
                i++;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
                rowStarted = true;
                i++;
            } else if (ch == '\r') {
                i++;
            } else if (ch == '\n') {
                row.add(field.toString());
                rows.add(row.toArray(new String[0]));
                row = new ArrayList<String>();
                field.setLength(0);
                rowStarted = false;
                i++;
            } else {
                field.append(ch);
                rowStarted = true;
                i++;
            }
        }
        if (rowStarted || field.length() > 0) {
            row.add(field.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    private static String htmlEscape(String s) {
        return FormSchemaUtil.htmlEscape(s);
    }

    private static String urlEncode(String s) {
        return FormSchemaUtil.urlEncode(s);
    }

    private static final String ICON_PLUS = "<svg viewBox='0 0 24 24'><path d='M12 5v14M5 12h14'/></svg>";
    private static final String ICON_EDIT =
            "<svg viewBox='0 0 24 24'><path d='M4 20h4L18.5 9.5a2.121 2.121 0 0 0-3-3L5 17v3z'/><path d='M13.5 6.5l4 4'/></svg>";
    private static final String ICON_VIEW =
            "<svg viewBox='0 0 24 24'><path d='M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z'/><circle cx='12' cy='12' r='3'/></svg>";
    private static final String ICON_TRASH =
            "<svg viewBox='0 0 24 24'><path d='M4 7h16'/><path d='M9 7V4h6v3'/>"
            + "<path d='M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13'/><path d='M10 11v6M14 11v6'/></svg>";
    private static final String ICON_UPLOAD =
            "<svg viewBox='0 0 24 24'><path d='M12 16V4'/><path d='M7 9l5-5 5 5'/><path d='M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3'/></svg>";
    private static final String ICON_DOWNLOAD =
            "<svg viewBox='0 0 24 24'><path d='M12 4v12'/><path d='M7 11l5 5 5-5'/><path d='M4 20h16'/></svg>";
    private static final String ICON_CARET =
            "<svg class='dropdown-caret' viewBox='0 0 24 24'><path d='M6 9l6 6 6-6'/></svg>";
    private static final String ICON_MAP =
            "<svg viewBox='0 0 24 24'><path d='M12 21s7-6.5 7-11a7 7 0 1 0-14 0c0 4.5 7 11 7 11z'/><circle cx='12' cy='10' r='2.4'/></svg>";
    /** Mise en évidence d'une valeur corrigée (différente du brut), lisible en thème clair comme sombre. */
    private static final String CORRECTED_CELL_STYLE = "background:rgba(245,166,35,.18);";

    private static final String ICON_DUPLICATE =
            "<svg viewBox='0 0 24 24'><rect x='9' y='9' width='11' height='11' rx='1.5'/>"
            + "<path d='M5 15V5a1 1 0 0 1 1-1h10'/></svg>";

    private void writeHeader(PrintWriter out, String title) {
        writeHeader(out, title, "soumissions");
    }

    private void writeHeader(PrintWriter out, String title, String section) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), section);
        out.println("<div class='container'>");
        out.println("<h2>" + htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String formId = req.getParameter("formId");
        String uri = req.getParameter("uri");
        String action = req.getParameter("action");
        try (Connection conn = getDataSource().getConnection()) {
            boolean canManage = isDataOwner();
            if (formId != null && !canManage
                    && !FormSchemaUtil.isFormAccessibleToViewer(conn, formId, FormSchemaUtil.currentLocalUsername())) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Ce formulaire n'appartient à aucun projet auquel vous êtes affecté.");
                return;
            }
            if (formId != null && "exportAll".equals(action)) {
                streamCsvExport(conn, resp, formId, null, exportVersion(req), exportStatusFilter(req));
                return;
            }
            if (formId != null && "exportAllJson".equals(action)) {
                streamJsonExport(conn, resp, formId, null, exportVersion(req), exportStatusFilter(req));
                return;
            }
            if (formId != null && "exportAllXlsx".equals(action)) {
                streamXlsxExport(conn, resp, formId, null, exportVersion(req), exportStatusFilter(req));
                return;
            }
            if (formId != null && "exportAllKml".equals(action)) {
                streamKmlExport(conn, resp, formId, exportVersion(req), exportStatusFilter(req));
                return;
            }
            if (formId != null && "csvTemplate".equals(action)) {
                streamCsvTemplate(conn, resp, formId);
                return;
            }
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            if (formId == null && "importForm".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission", "Formulaires");
                    return;
                }
                renderImportFormPage(out, null);
            } else if (formId == null && "importXlsForm".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission", "Formulaires");
                    return;
                }
                renderImportXlsFormPage(out, null);
            } else if (formId == null && "submissions".equals(action)) {
                renderSubmissionsHome(conn, out);
            } else if (formId == null) {
                String deletedForm = req.getParameter("deletedForm");
                renderFormList(conn, out, "1".equals(deletedForm) ? "Formulaire supprimé." : null, canManage);
            } else if ("new".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                renderCreateForm(conn, out, formId, null);
            } else if ("csv".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                renderCsvUploadForm(conn, out, formId, null);
            } else if ("csvUpdate".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                renderCsvUpdateForm(conn, out, formId, null);
            } else if ("map".equals(action)) {
                renderMap(conn, out, formId);
            } else if ("delete".equals(action) && uri != null) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                renderDeleteConfirm(conn, out, formId, uri, null);
            } else if ("duplicate".equals(action) && uri != null) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                renderDuplicateConfirm(conn, out, formId, uri, null);
            } else if ("deleteForm".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                renderDeleteFormConfirm(conn, out, formId, null);
            } else if ("trash".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "editSubmission?formId=" + urlEncode(formId), formId);
                    return;
                }
                String restored = req.getParameter("restored");
                String purged = req.getParameter("purged");
                String notice = restored != null ? restored + " soumission(s) restaurée(s)."
                        : purged != null ? purged + " soumission(s) supprimée(s) définitivement." : null;
                renderSubmissionTrash(conn, out, formId, notice, req.getParameter("error"));
            } else if (uri == null) {
                int offset = 0;
                try {
                    offset = Integer.parseInt(req.getParameter("offset"));
                } catch (NumberFormatException ignored) {
                }
                String deleted = req.getParameter("deleted");
                String statusChanged = req.getParameter("statusChanged");
                String notice = deleted != null
                        ? ("1".equals(deleted) ? "Soumission mise à la corbeille." : deleted + " soumission(s) mise(s) à la corbeille.")
                        : statusChanged != null ? statusChanged + " soumission(s) : statut mis à jour." : null;
                renderSubmissionList(conn, out, formId, offset, notice, canManage,
                        normalizeStatusFilter(req.getParameter("statut")));
            } else {
                renderEditForm(conn, out, formId, uri, null, "1".equals(req.getParameter("created")),
                        "1".equals(req.getParameter("duplicated")), !canManage,
                        "1".equals(req.getParameter("reverted")) ? "Modification annulée : les champs ont retrouvé leur "
                                + "valeur précédente."
                        : "1".equals(req.getParameter("resetRaw")) ? "Correction(s) retirée(s) : valeur(s) brute(s) "
                                + "du terrain rétablie(s)."
                        : "1".equals(req.getParameter("statusSet")) ? "Statut mis à jour." : null);
            }
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String formId = req.getParameter("formId");
        String uri = req.getParameter("uri");
        String action = req.getParameter("action");
        try (Connection conn = getDataSource().getConnection()) {
            // Toutes les actions ci-dessous ecrivent en base (creation/modification/suppression),
            // y compris action=null (enregistrement d'une soumission modifiee, cf. renderEditForm) -
            // reservees a ROLE_DATA_OWNER ; seuls les exports de la selection (telechargement) restent hors garde.
            boolean isWriteAction = !"exportSelected".equals(action) && !"exportSelectedJson".equals(action)
                    && !"exportSelectedXlsx".equals(action);
            boolean canManage = isDataOwner();
            if (isWriteAction && !canManage) {
                denyPost(resp);
                return;
            }
            // "exportSelected" est le seul chemin POST accessible a un lecteur de donnees - meme
            // restriction par projet affecte que cote GET.
            if (formId != null && !canManage
                    && !FormSchemaUtil.isFormAccessibleToViewer(conn, formId, FormSchemaUtil.currentLocalUsername())) {
                denyPost(resp);
                return;
            }
            if ("importForm".equals(action)) {
                handleImportForm(conn, req, resp);
                return;
            }
            if ("importXlsForm".equals(action)) {
                handleImportXlsForm(req, resp);
                return;
            }
            if ("downloadConvertedXml".equals(action)) {
                streamConvertedXml(req, resp);
                return;
            }
            if ("deployConvertedXml".equals(action)) {
                handleDeployConvertedXml(conn, req, resp);
                return;
            }
            if ("deleteFormConfirm".equals(action)) {
                handleDeleteFormConfirm(conn, req, resp, formId);
                return;
            }
            if ("exportSelected".equals(action)) {
                streamCsvExport(conn, resp, formId, req.getParameterValues("uri"), exportVersion(req), null);
                return;
            }
            if ("exportSelectedJson".equals(action)) {
                streamJsonExport(conn, resp, formId, req.getParameterValues("uri"), exportVersion(req), null);
                return;
            }
            if ("exportSelectedXlsx".equals(action)) {
                streamXlsxExport(conn, resp, formId, req.getParameterValues("uri"), exportVersion(req), null);
                return;
            }
            if ("csvUpload".equals(action)) {
                resp.setContentType("text/html;charset=UTF-8");
                renderCsvPreview(conn, req, resp.getWriter(), formId);
                return;
            }
            if ("csvConfirm".equals(action)) {
                resp.setContentType("text/html;charset=UTF-8");
                handleCsvConfirm(conn, req, resp.getWriter(), formId);
                return;
            }
            if ("csvUpdateUpload".equals(action)) {
                resp.setContentType("text/html;charset=UTF-8");
                renderCsvUpdatePreview(conn, req, resp.getWriter(), formId);
                return;
            }
            if ("csvUpdateConfirm".equals(action)) {
                resp.setContentType("text/html;charset=UTF-8");
                handleCsvUpdateConfirm(conn, req, resp.getWriter(), formId);
                return;
            }
            if ("create".equals(action)) {
                String error = null;
                String newUri = null;
                try {
                    newUri = createSubmission(conn, req, formId);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    recordHistory(conn, formId, newUri, HISTORY_CREATE, null, "Saisie manuelle");
                    ActivityLog.record(conn, "submission.create", "soumission", newUri, "Formulaire : " + formId);
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(newUri) + "&created=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderCreateForm(conn, resp.getWriter(), formId, error);
                return;
            }
            if ("deleteConfirm".equals(action)) {
                String error = null;
                try {
                    deleteSubmission(conn, formId, uri);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    ActivityLog.record(conn, "submission.delete", "soumission", uri,
                            "Mise à la corbeille (formulaire : " + formId + ")");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&deleted=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderDeleteConfirm(conn, resp.getWriter(), formId, uri, error);
                return;
            }
            if ("duplicateConfirm".equals(action)) {
                String error = null;
                String newUri = null;
                try {
                    newUri = duplicateSubmission(conn, formId, uri);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    recordHistory(conn, formId, newUri, HISTORY_DUPLICATE, null, "Copie de " + uri);
                    ActivityLog.record(conn, "submission.duplicate", "soumission", newUri,
                            "Copie de " + uri + " (formulaire : " + formId + ")");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(newUri) + "&duplicated=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderDuplicateConfirm(conn, resp.getWriter(), formId, uri, error);
                return;
            }
            if ("deleteSelectedConfirm".equals(action)) {
                resp.setContentType("text/html;charset=UTF-8");
                renderDeleteConfirmMultiple(resp.getWriter(), formId, req.getParameterValues("uri"), null);
                return;
            }
            if ("deleteSelected".equals(action)) {
                String[] uris = req.getParameterValues("uri");
                String error = null;
                int count = 0;
                try {
                    count = deleteSubmissions(conn, formId, uris);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    ActivityLog.record(conn, "submission.delete_bulk", "formulaire", formId,
                            count + " soumission(s) mise(s) à la corbeille");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&deleted=" + count);
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderDeleteConfirmMultiple(resp.getWriter(), formId, uris, error);
                return;
            }
            if ("setStatusSelected".equals(action)) {
                String[] uris = req.getParameterValues("uri");
                String status = req.getParameter("status");
                int count = setStatus(conn, formId, uris, status, req.getParameter("comment"));
                if (count > 0) {
                    ActivityLog.record(conn, "submission.status", "formulaire", formId,
                            count + " soumission(s) : " + statusLabel(status));
                }
                String filter = normalizeStatusFilter(req.getParameter("statut"));
                resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + (filter == null ? "" : "&statut=" + filter)
                        + "&statusChanged=" + count);
                return;
            }
            if ("setStatus".equals(action)) {
                String status = req.getParameter("status");
                int count = setStatus(conn, formId, new String[] {uri}, status, req.getParameter("comment"));
                if (count > 0) {
                    ActivityLog.record(conn, "submission.status", "soumission", uri,
                            statusLabel(status) + " (formulaire : " + formId + ")");
                }
                resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri)
                        + (count > 0 ? "&statusSet=1" : ""));
                return;
            }
            if ("resetToRaw".equals(action)) {
                String column = req.getParameter("column");
                String error = null;
                try {
                    resetToRaw(conn, formId, uri, column == null || column.isEmpty() ? null : column);
                } catch (SQLException e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    ActivityLog.record(conn, "submission.reset_raw", "soumission", uri,
                            (column == null || column.isEmpty() ? "Toutes les corrections retirées" : "Champ remis au brut : "
                                    + column) + " (formulaire : " + formId + ")");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri) + "&resetRaw=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderEditForm(conn, resp.getWriter(), formId, uri, error, false);
                return;
            }
            if ("revertHistory".equals(action)) {
                int historyId = -1;
                try {
                    historyId = Integer.parseInt(req.getParameter("historyId"));
                } catch (NumberFormatException ignored) {
                }
                String error = null;
                try {
                    revertHistoryEntry(conn, formId, uri, historyId);
                } catch (SQLException e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    ActivityLog.record(conn, "submission.revert", "soumission", uri,
                            "Annulation d'une modification (formulaire : " + formId + ")");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri) + "&reverted=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderEditForm(conn, resp.getWriter(), formId, uri, error, false);
                return;
            }
            if ("restoreSelected".equals(action)) {
                String[] uris = req.getParameterValues("uri");
                int count = restoreSubmissions(conn, formId, uris);
                if (count > 0) {
                    ActivityLog.record(conn, "submission.restore", "formulaire", formId,
                            count + " soumission(s) restaurée(s)" + (count == 1 ? " : " + uris[0] : ""));
                }
                resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&action=trash&restored=" + count);
                return;
            }
            if ("purgeSelected".equals(action)) {
                String[] uris = req.getParameterValues("uri");
                int count;
                try {
                    count = purgeSubmissions(conn, formId, uris);
                } catch (SQLException e) {
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&action=trash&error="
                            + urlEncode("Erreur : " + e.getMessage()));
                    return;
                }
                if (count > 0) {
                    ActivityLog.record(conn, "submission.purge", "formulaire", formId,
                            count + " soumission(s) supprimée(s) définitivement" + (count == 1 ? " : " + uris[0] : ""));
                }
                resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&action=trash&purged=" + count);
                return;
            }
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            String error = null;
            try {
                saveSubmission(conn, req, formId, uri);
                ActivityLog.record(conn, "submission.update", "soumission", uri, "Formulaire : " + formId);
            } catch (Exception e) {
                error = e.getMessage();
            }
            renderEditForm(conn, out, formId, uri, error, false);
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private void renderFormList(Connection conn, PrintWriter out, String notice, boolean canManage) throws SQLException {
        writeHeader(out, "Choisir un formulaire", "formulaires");
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        out.println("<p class='info'>Sélectionnez un formulaire pour "
                + (canManage ? "consulter ou modifier ses soumissions." : "consulter ou télécharger ses soumissions.") + "</p>");
        if (canManage) {
            out.println("<div class='toolbar'>");
            out.println("<a class='btn-link' href='editSubmission?action=importForm'>" + ICON_UPLOAD
                    + " Importer un formulaire XML</a>");
            out.println("<a class='btn-link' href='editSubmission?action=importXlsForm'>" + ICON_UPLOAD
                    + " Importer un XLSForm (Excel)</a>");
            out.println("</div>");
        }
        String sql = "SELECT \"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_info_submission_association\" "
                + "WHERE \"IS_SUBMISSION_ALLOWED\" = true AND \"URI_SUBMISSION_DATA_MODEL\" IS NOT NULL "
                + "ORDER BY \"SUBMISSION_FORM_ID\"";
        List<String> formIds = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                formIds.add(rs.getString(1));
            }
        }
        if (!canManage) {
            Set<String> visible = FormSchemaUtil.listFormsVisibleToViewer(conn, FormSchemaUtil.currentLocalUsername());
            formIds.retainAll(visible);
        }
        if (formIds.isEmpty()) {
            out.println("<p class='info'>" + (canManage ? "Aucun formulaire pour le moment."
                    : "Aucun formulaire visible : vous n'êtes affecté à aucun projet.") + "</p>");
        } else {
            out.println("<div class='table-enhance'>");
            out.println("<input type='text' class='table-search-input' placeholder='Rechercher un formulaire...'>");
            out.println("<table><thead><tr><th data-sortable>Formulaire</th><th></th></tr></thead><tbody>");
            for (String formId : formIds) {
                out.println("<tr><td><a href='editSubmission?formId=" + urlEncode(formId) + "'>"
                        + htmlEscape(formId) + "</a></td><td><div class='row-actions'>"
                        + "<a class='btn-link' href='formXml?formId=" + urlEncode(formId) + "'>" + ICON_DOWNLOAD
                        + " Télécharger</a>"
                        + (canManage
                                ? "<a class='btn-link danger' href='editSubmission?formId=" + urlEncode(formId)
                                        + "&action=deleteForm'>" + ICON_TRASH + " Supprimer</a>"
                                : "")
                        + "</div></td></tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }
        writeFooter(out);
    }

    /** Ecran d'accueil de la section "Soumissions" : formulaires avec leur nombre de soumissions. */
    private void renderSubmissionsHome(Connection conn, PrintWriter out) throws SQLException {
        writeHeader(out, "Soumissions", "soumissions");
        boolean canManage = isDataOwner();
        out.println("<p class='info'>Choisissez un formulaire pour consulter"
                + (canManage ? ", modifier ou exporter ses soumissions." : " ou exporter ses soumissions.") + "</p>");
        String sql = "SELECT \"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_info_submission_association\" "
                + "WHERE \"IS_SUBMISSION_ALLOWED\" = true AND \"URI_SUBMISSION_DATA_MODEL\" IS NOT NULL "
                + "ORDER BY \"SUBMISSION_FORM_ID\"";
        List<String> formIds = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                formIds.add(rs.getString(1));
            }
        }
        if (!canManage) {
            Set<String> visible = FormSchemaUtil.listFormsVisibleToViewer(conn, FormSchemaUtil.currentLocalUsername());
            formIds.retainAll(visible);
        }
        if (formIds.isEmpty()) {
            out.println("<p class='info'>" + (canManage ? "Aucun formulaire pour le moment."
                    : "Aucun formulaire visible : vous n'êtes affecté à aucun projet.") + "</p>");
        } else {
            out.println("<div class='table-enhance'>");
            out.println("<input type='text' class='table-search-input' placeholder='Rechercher un formulaire...'>");
            out.println("<table><thead><tr><th data-sortable>Formulaire</th><th data-sortable>Soumissions</th>"
                    + "<th data-sortable>Dernière soumission</th><th></th></tr></thead><tbody>");
            for (String formId : formIds) {
                out.println("<tr>");
                try {
                    FormSchemaUtil.SubmissionStats stats = FormSchemaUtil.submissionStats(conn, formId);
                    out.println("<td>" + htmlEscape(formId) + "</td>");
                    out.println("<td>" + stats.count + "</td>");
                    out.println("<td>" + htmlEscape(stats.lastSubmissionDate == null ? "" : stats.lastSubmissionDate.toString())
                            + "</td>");
                } catch (SQLException e) {
                    out.println("<td>" + htmlEscape(formId) + "</td><td class='err' colspan='2'>Indisponible</td>");
                }
                out.println("<td><a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "'>"
                        + ICON_EDIT + " Voir les soumissions</a></td>");
                out.println("</tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }
        writeFooter(out);
    }

    // ---- Import direct d'un formulaire XML (delegue a l'endpoint ODK existant /formUpload) -----

    private void renderImportFormPage(PrintWriter out, String error) {
        writeHeader(out, "Importer un formulaire XML", "formulaires");
        out.println("<p class='back-link'><a href='editSubmission'>&laquo; Formulaires</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p class='info'>Le formulaire sera créé dans ODK et vous serez redirigé vers ses soumissions.</p>");
        out.println("<form method='post' action='editSubmission?action=importForm' enctype='multipart/form-data'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Fichier XML</label><input type='file' name='xmlFile' accept='.xml,text/xml' required>");
        out.println("<label>Titre</label><input type='text' name='formTitle' "
                + "placeholder='(facultatif, déduit du nom de fichier si vide)'>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>" + ICON_UPLOAD + " Importer</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void handleImportForm(Connection conn, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        byte[] xmlBytes = null;
        String xmlFileName = null;
        String formTitle = null;
        try {
            if (ServletFileUpload.isMultipartContent(req)) {
                ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
                List<FileItem> items = upload.parseRequest(req);
                for (FileItem item : items) {
                    if (!item.isFormField() && "xmlFile".equals(item.getFieldName())) {
                        xmlBytes = item.get();
                        xmlFileName = item.getName();
                    } else if (item.isFormField() && "formTitle".equals(item.getFieldName())) {
                        formTitle = item.getString("UTF-8");
                    }
                }
            }
        } catch (Exception e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportFormPage(resp.getWriter(), "Erreur de lecture du fichier : " + e.getMessage());
            return;
        }

        if (xmlBytes == null || xmlBytes.length == 0) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportFormPage(resp.getWriter(), "Aucun fichier XML reçu.");
            return;
        }
        if (formTitle == null || formTitle.trim().isEmpty()) {
            formTitle = FormUploadUtil.deriveTitleFromFileName(xmlFileName);
        }

        Set<String> before;
        try {
            before = FormSchemaUtil.listAllFormIds(conn);
        } catch (SQLException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportFormPage(resp.getWriter(), "Erreur : " + e.getMessage());
            return;
        }

        FormUploadUtil.UploadResult result;
        try {
            result = FormUploadUtil.uploadFormXml(req, xmlBytes, xmlFileName, formTitle);
        } catch (IOException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportFormPage(resp.getWriter(), "Échec de la connexion vers l'import ODK : " + e.getMessage());
            return;
        }

        if (result.isSuccess()) {
            try {
                Set<String> after = FormSchemaUtil.listAllFormIds(conn);
                after.removeAll(before);
                if (after.size() == 1) {
                    String newFormId = after.iterator().next();
                    ActivityLog.record(conn, "form.import", "formulaire", newFormId, "Import XML direct");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(newFormId) + "&created=1");
                } else {
                    resp.sendRedirect("editSubmission");
                }
            } catch (SQLException e) {
                resp.sendRedirect("editSubmission");
            }
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");
        String message = result.body == null || result.body.trim().isEmpty()
                ? "Échec de l'import (code " + result.status + ")."
                : result.body.trim();
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        renderImportFormPage(resp.getWriter(), message);
    }

    // ---- Import XLSForm (Excel) -> conversion en XML -> telecharger et/ou deployer --------------

    private void renderImportXlsFormPage(PrintWriter out, String error) {
        writeHeader(out, "Importer un XLSForm (Excel)", "formulaires");
        out.println("<p class='back-link'><a href='editSubmission'>&laquo; Formulaires</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p class='info'>Le fichier Excel (XLSForm) sera converti en XML. Vous pourrez ensuite "
                + "télécharger ce XML et/ou le déployer dans ODK, séparément.</p>");
        out.println("<form method='post' action='editSubmission?action=importXlsForm' enctype='multipart/form-data'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Fichier Excel</label><input type='file' name='xlsFile' "
                + "accept='.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' required>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>" + ICON_UPLOAD + " Convertir</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void handleImportXlsForm(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        byte[] xlsxBytes = null;
        String xlsxFileName = null;
        try {
            if (ServletFileUpload.isMultipartContent(req)) {
                ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
                List<FileItem> items = upload.parseRequest(req);
                for (FileItem item : items) {
                    if (!item.isFormField() && "xlsFile".equals(item.getFieldName())) {
                        xlsxBytes = item.get();
                        xlsxFileName = item.getName();
                    }
                }
            }
        } catch (Exception e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportXlsFormPage(resp.getWriter(), "Erreur de lecture du fichier : " + e.getMessage());
            return;
        }

        if (xlsxBytes == null || xlsxBytes.length == 0) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportXlsFormPage(resp.getWriter(), "Aucun fichier Excel reçu.");
            return;
        }

        XlsFormConverter.ConversionResult result;
        try {
            result = XlsFormConverter.convert(xlsxBytes);
        } catch (IOException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportXlsFormPage(resp.getWriter(), "Erreur de conversion : " + e.getMessage());
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");
        if (!result.success) {
            String message = result.message == null || result.message.trim().isEmpty()
                    ? "Échec de la conversion." : result.message.trim();
            if (message.length() > 1000) {
                message = message.substring(0, 1000);
            }
            renderImportXlsFormPage(resp.getWriter(), message);
            return;
        }
        String suggestedTitle = FormUploadUtil.deriveTitleFromFileName(xlsxFileName);
        renderXlsFormResult(resp.getWriter(), result.xmlBytes, suggestedTitle, null);
    }

    private void renderXlsFormResult(PrintWriter out, byte[] xmlBytes, String formTitle, String error) throws IOException {
        String xmlText = new String(xmlBytes, "UTF-8");
        writeHeader(out, "Formulaire converti", "formulaires");
        out.println("<p class='back-link'><a href='editSubmission'>&laquo; Formulaires</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        } else {
            out.println("<p class='success'>Conversion réussie. Téléchargez le XML, déployez-le dans ODK, ou les deux.</p>");
        }
        out.println("<div class='card'>");
        out.println("<div class='toolbar'>");
        out.println("<form class='inline' method='post' action='editSubmission?action=downloadConvertedXml'>");
        out.println("<input type='hidden' name='xml' value='" + htmlEscape(xmlText) + "'>");
        out.println("<button type='submit'>" + ICON_DOWNLOAD + " Télécharger le XML</button>");
        out.println("</form>");
        out.println("<form class='inline' method='post' action='editSubmission?action=deployConvertedXml' "
                + "style='display:flex;gap:8px;align-items:center;'>");
        out.println("<input type='hidden' name='xml' value='" + htmlEscape(xmlText) + "'>");
        out.println("<input type='text' name='formTitle' value='" + htmlEscape(formTitle == null ? "" : formTitle)
                + "' placeholder='Titre du formulaire' style='max-width:220px;'>");
        out.println("<button type='submit'>" + ICON_UPLOAD + " Déployer maintenant</button>");
        out.println("</form>");
        out.println("</div>");

        String previewHtml = buildFormPreviewHtml(xmlBytes);
        if (previewHtml != null) {
            out.println("<h2 style='margin-top:24px;'>Aperçu du formulaire</h2>");
            out.println("<p class='info'>Les champs sont désactivés (lecture seule) — c'est un aperçu, pas une vraie saisie.</p>");
            out.println("<div class='card'><div class='form-grid'>");
            out.println(previewHtml);
            out.println("</div></div>");
        }

        out.println("<h2 style='margin-top:24px;'>XML généré</h2>");
        out.println("<textarea readonly style='width:100%;min-height:220px;font-family:monospace;font-size:12px;'>"
                + htmlEscape(xmlText) + "</textarea>");
        out.println("</div>");
        writeFooter(out);
    }

    private static final Set<String> PREVIEW_CONTROL_TAGS = new HashSet<String>(
            Arrays.asList("input", "select1", "select", "upload", "trigger", "range"));

    /** Aperçu simple (lecture seule) des questions d'un formulaire, construit en parcourant &lt;h:body&gt;. */
    private static String buildFormPreviewHtml(byte[] xmlBytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(new String(xmlBytes, "UTF-8"))));

            Map<String, String> bindTypes = new HashMap<String, String>();
            NodeList binds = doc.getElementsByTagName("bind");
            for (int i = 0; i < binds.getLength(); i++) {
                Element b = (Element) binds.item(i);
                String nodeset = b.getAttribute("nodeset");
                String type = b.getAttribute("type");
                if (!nodeset.isEmpty()) {
                    bindTypes.put(nodeset, type.isEmpty() ? "string" : type);
                }
            }

            NodeList bodyNodes = doc.getElementsByTagName("h:body");
            if (bodyNodes.getLength() == 0) {
                return null;
            }
            StringBuilder html = new StringBuilder();
            int before = html.length();
            appendPreviewControls(bodyNodes.item(0), bindTypes, html);
            if (html.length() == before) {
                return null;
            }
            return html.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Traduit un type de bind XForms vers le type d'input HTML le plus proche (meme esprit que inputTypeFor). */
    private static String xformInputType(String bindType) {
        if (bindType == null) {
            return "text";
        }
        switch (bindType) {
            case "int":
            case "integer":
            case "decimal":
                return "number";
            case "date":
                return "date";
            case "dateTime":
            case "datetime":
                return "datetime-local";
            case "time":
                return "time";
            default:
                return "text";
        }
    }

    private static void appendPreviewControls(Node parent, Map<String, String> bindTypes, StringBuilder html) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = child.getNodeName();
            Element el = (Element) child;
            if (PREVIEW_CONTROL_TAGS.contains(tag)) {
                String ref = el.getAttribute("ref");
                String label = firstChildText(el, "label");
                html.append("<label>")
                        .append(htmlEscape(label == null || label.trim().isEmpty() ? "(sans libellé)" : label))
                        .append("</label>");
                if ("select1".equals(tag) || "select".equals(tag)) {
                    NodeList items = el.getElementsByTagName("item");
                    if ("select".equals(tag)) {
                        html.append("<div>");
                        for (int j = 0; j < items.getLength(); j++) {
                            String itemLabel = firstChildText((Element) items.item(j), "label");
                            html.append("<label style='font-weight:400;display:block;margin-bottom:4px;'>"
                                    + "<input type='checkbox' disabled> ")
                                    .append(htmlEscape(itemLabel == null ? "" : itemLabel)).append("</label>");
                        }
                        html.append("</div>");
                    } else {
                        html.append("<select disabled><option>—</option>");
                        for (int j = 0; j < items.getLength(); j++) {
                            String itemLabel = firstChildText((Element) items.item(j), "label");
                            html.append("<option>").append(htmlEscape(itemLabel == null ? "" : itemLabel)).append("</option>");
                        }
                        html.append("</select>");
                    }
                } else if ("upload".equals(tag)) {
                    html.append("<input type='file' disabled>");
                } else if ("trigger".equals(tag)) {
                    html.append("<label style='font-weight:400;'><input type='checkbox' disabled> "
                            + "<span class='muted'>Confirmation</span></label>");
                } else if ("range".equals(tag)) {
                    html.append("<input type='range' disabled>");
                } else {
                    String inputType = xformInputType(bindTypes.get(ref));
                    html.append("<input type='").append(inputType).append("' disabled>");
                }
            } else if ("group".equals(tag) || "repeat".equals(tag)) {
                String label = firstChildText(el, "label");
                String defaultLabel = "repeat".equals(tag) ? "Groupe répété" : "Groupe";
                html.append("<div style='grid-column:1/-1;font-weight:650;color:#16304f;"
                        + "margin-top:12px;padding-top:12px;border-top:1px solid #eef1f6;'>")
                        .append(htmlEscape(label == null || label.trim().isEmpty() ? defaultLabel : label));
                if ("repeat".equals(tag)) {
                    html.append(" <span class='muted' style='font-weight:400;'>(répétable)</span>");
                }
                html.append("</div>");
                appendPreviewControls(el, bindTypes, html);
            }
        }
    }

    private static String firstChildText(Element parent, String childTagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && childTagName.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private void streamConvertedXml(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String xml = req.getParameter("xml");
        if (xml == null) {
            resp.sendRedirect("editSubmission");
            return;
        }
        resp.setContentType("text/xml;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"formulaire.xml\"");
        resp.getWriter().write(xml);
    }

    private void handleDeployConvertedXml(Connection conn, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String xml = req.getParameter("xml");
        String formTitle = req.getParameter("formTitle");
        if (xml == null || xml.trim().isEmpty()) {
            resp.sendRedirect("editSubmission");
            return;
        }
        if (formTitle == null || formTitle.trim().isEmpty()) {
            formTitle = "Formulaire importé";
        }
        byte[] xmlBytes = xml.getBytes("UTF-8");

        Set<String> before;
        try {
            before = FormSchemaUtil.listAllFormIds(conn);
        } catch (SQLException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderXlsFormResult(resp.getWriter(), xmlBytes, formTitle, "Erreur : " + e.getMessage());
            return;
        }

        FormUploadUtil.UploadResult result;
        try {
            result = FormUploadUtil.uploadFormXml(req, xmlBytes, "formulaire.xml", formTitle);
        } catch (IOException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderXlsFormResult(resp.getWriter(), xmlBytes, formTitle, "Échec de la connexion vers l'import ODK : " + e.getMessage());
            return;
        }

        if (result.isSuccess()) {
            try {
                Set<String> after = FormSchemaUtil.listAllFormIds(conn);
                after.removeAll(before);
                if (after.size() == 1) {
                    String newFormId = after.iterator().next();
                    ActivityLog.record(conn, "form.import", "formulaire", newFormId, "Import via XLSForm (Excel)");
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(newFormId) + "&created=1");
                } else {
                    resp.sendRedirect("editSubmission");
                }
            } catch (SQLException e) {
                resp.sendRedirect("editSubmission");
            }
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");
        String message = result.body == null || result.body.trim().isEmpty()
                ? "Échec du déploiement (code " + result.status + ")."
                : result.body.trim();
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        renderXlsFormResult(resp.getWriter(), xmlBytes, formTitle, message);
    }

    private void renderSubmissionList(Connection conn, PrintWriter out, String formId, int offset, String notice,
            boolean canManage, String statusFilter) throws SQLException {
        writeHeader(out, "Soumissions — " + formId);
        out.println("<p class='back-link'><a href='editSubmission'>&laquo; Formulaires</a></p>");
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }

        String modelUri = resolveSubmissionDataModelUri(conn, formId);
        String coreTable = modelUri == null ? null : resolveCoreTable(conn, modelUri);
        if (coreTable == null) {
            out.println("<p class='err'>Formulaire introuvable : " + htmlEscape(formId) + "</p>");
            writeFooter(out);
            return;
        }

        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        boolean hasGeoField = !findGeoFields(columns).isEmpty();

        out.println("<div class='toolbar'>");
        if (canManage) {
            out.println("<div class='dropdown'>");
            out.println("<button type='button' class='btn-link dropdown-trigger'>" + ICON_PLUS + " Ajouter soumission" + ICON_CARET
                    + "</button>");
            out.println("<div class='dropdown-menu'>");
            out.println("<a href='editSubmission?formId=" + urlEncode(formId) + "&action=new'>" + ICON_PLUS
                    + " Nouvelle soumission</a>");
            out.println("<a href='editSubmission?formId=" + urlEncode(formId) + "&action=csv'>" + ICON_UPLOAD
                    + " Importer CSV</a>");
            out.println("<a href='editSubmission?formId=" + urlEncode(formId) + "&action=csvUpdate'>" + ICON_UPLOAD
                    + " Mettre à jour via CSV</a>");
            out.println("</div>");
            out.println("</div>");
        }
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=map'>" + ICON_MAP
                + " Carte</a>");
        // Version des téléchargements (nettoyée / brute / les deux) : un seul sélecteur, appliqué par
        // le script plus bas à tous les liens et boutons marqués data-export au moment du clic.
        out.println("<label style='display:inline-flex;align-items:center;gap:6px;'>Données "
                + "<select id='exportVersion' title='Version des données téléchargées'>"
                + "<option value='" + EXPORT_CLEAN + "'>nettoyées</option>"
                + "<option value='" + EXPORT_RAW + "'>brutes (terrain)</option>"
                + "<option value='" + EXPORT_BOTH + "'>brutes + nettoyées</option>"
                + "</select></label>");
        out.println("<label style='display:inline-flex;align-items:center;gap:6px;'>Statut "
                + "<select id='exportStatus' title='Soumissions incluses dans « Télécharger tout »'>"
                + "<option value=''>toutes</option>"
                + "<option value='" + STATUS_VALID + "'>validées seulement</option>"
                + "<option value='" + STATUS_NOT_REJECTED + "'>sauf rejetées</option>"
                + "</select></label>");
        out.println("<div class='dropdown'>");
        out.println("<button type='button' class='btn-link dropdown-trigger'>" + ICON_DOWNLOAD + " Télécharger tout" + ICON_CARET
                + "</button>");
        out.println("<div class='dropdown-menu'>");
        out.println("<a data-export href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAll'>" + ICON_DOWNLOAD + " CSV</a>");
        out.println("<a data-export href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAllJson'>" + ICON_DOWNLOAD + " JSON</a>");
        out.println("<a data-export href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAllXlsx'>" + ICON_DOWNLOAD + " Excel</a>");
        if (hasGeoField) {
            out.println("<a data-export href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAllKml'>" + ICON_DOWNLOAD + " KML</a>");
        }
        out.println("</div>");
        out.println("</div>");
        out.println("<a class='btn-link' href='formXml?formId=" + urlEncode(formId) + "'>" + ICON_DOWNLOAD
                + " Télécharger le formulaire (XML)</a>");
        if (canManage) {
            int trashCount = countTrashedSubmissions(conn, formId);
            out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=trash'>"
                    + ICON_TRASH + " Corbeille" + (trashCount > 0 ? " (" + trashCount + ")" : "") + "</a>");
            out.println("<a class='btn-link danger' href='editSubmission?formId=" + urlEncode(formId) + "&action=deleteForm'>"
                    + ICON_TRASH + " Supprimer ce formulaire</a>");
        }
        out.println("</div>");

        // Avancement du nettoyage : compteurs par statut, chacun servant de filtre.
        Map<String, Integer> statusCounts = countStatuses(conn, coreTable);
        int total = statusCounts.get(STATUS_TO_CHECK) + statusCounts.get(STATUS_VALID) + statusCounts.get(STATUS_REJECTED);
        String base = "editSubmission?formId=" + urlEncode(formId);
        String[][] filters = {
            {null, "Toutes", String.valueOf(total)},
            {STATUS_TO_CHECK, "À vérifier", String.valueOf(statusCounts.get(STATUS_TO_CHECK))},
            {STATUS_VALID, "Validées", String.valueOf(statusCounts.get(STATUS_VALID))},
            {STATUS_REJECTED, "Rejetées", String.valueOf(statusCounts.get(STATUS_REJECTED))},
            {STATUS_NOT_REJECTED, "Sauf rejetées", String.valueOf(total - statusCounts.get(STATUS_REJECTED))},
        };
        StringBuilder filterBar = new StringBuilder("<p class='info'>Statut : ");
        for (int i = 0; i < filters.length; i++) {
            boolean current = filters[i][0] == null ? statusFilter == null : filters[i][0].equals(statusFilter);
            String label = htmlEscape(filters[i][1]) + " (" + filters[i][2] + ")";
            filterBar.append(i == 0 ? "" : " &middot; ").append(current ? "<strong>" + label + "</strong>"
                    : "<a href='" + base + (filters[i][0] == null ? "" : "&statut=" + filters[i][0]) + "'>" + label + "</a>");
        }
        if (total > 0) {
            filterBar.append(" &middot; <span class='muted'>")
                    .append(Math.round(100.0 * (statusCounts.get(STATUS_VALID) + statusCounts.get(STATUS_REJECTED)) / total))
                    .append(" % traitées</span>");
        }
        out.println(filterBar.append("</p>").toString());
        int filteredTotal = statusFilter == null ? total
                : STATUS_NOT_REJECTED.equals(statusFilter) ? total - statusCounts.get(STATUS_REJECTED)
                : statusCounts.get(statusFilter);

        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=exportSelected'>");
        if (canManage) {
            // Motif d'un rejet groupé, rempli par le bouton "Rejeter la sélection" (prompt).
            out.println("<input type='hidden' name='comment' id='bulkComment' value=''>");
            if (statusFilter != null) {
                out.println("<input type='hidden' name='statut' value='" + htmlEscape(statusFilter) + "'>");
            }
        }
        out.println("<div class='table-enhance'>");
        out.println("<input type='text' class='table-search-input' placeholder='Rechercher dans cette page...'>");
        out.println("<div style='overflow-x:auto;'>");
        out.println("<table><thead><tr><th><input type='checkbox' id='selAll'></th><th data-sortable>_URI</th>"
                + "<th data-sortable>Date de soumission</th><th data-sortable>Statut</th>");
        for (ColumnMeta col : columns) {
            out.println("<th data-sortable>" + htmlEscape(col.elementName) + "</th>");
        }
        out.println("<th></th></tr></thead><tbody>");

        StringBuilder selectCols = new StringBuilder("\"_URI\",\"_SUBMISSION_DATE\"");
        for (ColumnMeta col : columns) {
            selectCols.append(",\"").append(col.columnName).append("\"");
        }
        String sql = "SELECT " + selectCols + " FROM odk_prod.\"" + coreTable + "\" "
                + "WHERE " + FormSchemaUtil.CORE_NOT_DELETED + statusFilterSql(statusFilter) + " "
                + "ORDER BY \"_SUBMISSION_DATE\" DESC NULLS LAST LIMIT ? OFFSET ?";
        // Page lue d'abord (brut), puis corrections de ces seules soumissions, puis affichage nettoyé.
        List<String> pageUris = new ArrayList<String>();
        List<Object> pageDates = new ArrayList<Object>();
        List<Map<String, Object>> pageRaw = new ArrayList<Map<String, Object>>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, PAGE_SIZE);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pageUris.add(rs.getString(1));
                    pageDates.add(rs.getObject(2));
                    Map<String, Object> raw = new HashMap<String, Object>();
                    for (ColumnMeta col : columns) {
                        raw.put(col.columnName, rs.getObject(col.columnName));
                    }
                    pageRaw.add(raw);
                }
            }
        }
        Map<String, Map<String, String>> pageCorrections = loadCorrections(conn, formId, pageUris);
        Map<String, SubmissionStatus> pageStatuses = loadStatuses(conn, formId, pageUris);
        int rowCount = pageUris.size();
        {
            for (int r = 0; r < rowCount; r++) {
                {
                    String rowUri = pageUris.get(r);
                    Object subDate = pageDates.get(r);
                    Map<String, String> corrections = correctionsOf(pageCorrections, rowUri);
                    out.println("<tr><td><input type='checkbox' name='uri' class='sel-cb' value='" + htmlEscape(rowUri)
                            + "'></td><td>" + htmlEscape(rowUri)
                            + (corrections.isEmpty() ? "" : "<div class='muted' title='Champs corrigés (le brut est conservé)'>"
                                    + ICON_EDIT + " " + corrections.size() + " correction(s)</div>")
                            + "</td><td>" + htmlEscape(subDate == null ? "" : subDate.toString()) + "</td>");
                    SubmissionStatus st = pageStatuses.get(rowUri);
                    out.println("<td" + (st != null && st.comment != null ? " title='" + htmlEscape(st.comment) + "'" : "") + ">"
                            + statusBadge(statusOf(pageStatuses, rowUri)) + "</td>");
                    for (ColumnMeta col : columns) {
                        Object rawValue = pageRaw.get(r).get(col.columnName);
                        if (corrections.containsKey(col.columnName)) {
                            Object cleaned = typedValue(col, corrections.get(col.columnName));
                            out.println("<td style='" + CORRECTED_CELL_STYLE + "' title='Brut : "
                                    + htmlEscape(rawValue == null ? "(vide)" : formatValueForDisplay(rawValue)) + "'>"
                                    + htmlEscape(formatValueForDisplay(cleaned)) + "</td>");
                        } else {
                            out.println("<td>" + htmlEscape(formatValueForDisplay(rawValue)) + "</td>");
                        }
                    }
                    out.println("<td>"
                            + "<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(rowUri)
                            + "'>" + (canManage ? ICON_EDIT + " Modifier" : ICON_VIEW + " Voir") + "</a> "
                            + (canManage
                                    ? "<a class='btn-link danger' href='editSubmission?formId=" + urlEncode(formId) + "&uri="
                                            + urlEncode(rowUri) + "&action=delete'>" + ICON_TRASH + " Supprimer</a>"
                                    : "")
                            + "</td></tr>");
                }
            }
        }
        out.println("</tbody></table>");
        out.println("</div>");
        out.println("</div>");
        if (rowCount > 0) {
            out.println("<div class='actions'>"
                    + "<div class='dropdown'>"
                    + "<button type='button' class='dropdown-trigger'>" + ICON_DOWNLOAD + " Télécharger la sélection" + ICON_CARET
                    + "</button>"
                    + "<div class='dropdown-menu'>"
                    + "<button type='submit' data-export formaction='editSubmission?formId=" + urlEncode(formId)
                    + "&action=exportSelected'>" + ICON_DOWNLOAD + " CSV</button>"
                    + "<button type='submit' data-export formaction='editSubmission?formId=" + urlEncode(formId)
                    + "&action=exportSelectedJson'>" + ICON_DOWNLOAD + " JSON</button>"
                    + "<button type='submit' data-export formaction='editSubmission?formId=" + urlEncode(formId)
                    + "&action=exportSelectedXlsx'>" + ICON_DOWNLOAD + " Excel</button>"
                    + "</div>"
                    + "</div> "
                    + (canManage
                            ? "<button type='submit' formaction='editSubmission?formId=" + urlEncode(formId)
                                    + "&action=setStatusSelected&status=" + STATUS_VALID + "'>Valider la sélection</button> "
                                    + "<button type='submit' formaction='editSubmission?formId=" + urlEncode(formId)
                                    + "&action=setStatusSelected&status=" + STATUS_REJECTED + "' onclick=\"var m = "
                                    + "prompt('Motif du rejet (facultatif) :', ''); if (m === null) { return false; } "
                                    + "document.getElementById('bulkComment').value = m;\">Rejeter la sélection</button> "
                                    + "<button type='submit' class='btn-link' formaction='editSubmission?formId=" + urlEncode(formId)
                                    + "&action=setStatusSelected&status=" + STATUS_TO_CHECK + "'>Remettre à vérifier</button> "
                                    + "<button type='submit' class='danger' formaction='editSubmission?formId=" + urlEncode(formId)
                                    + "&action=deleteSelectedConfirm'>" + ICON_TRASH + " Supprimer la sélection</button>"
                            : "")
                    + "</div>");
        } else if (statusFilter != null) {
            out.println("<p class='info'>Aucune soumission avec ce statut.</p>");
        }
        out.println("</form>");
        out.println("<script>document.getElementById('selAll').addEventListener('change', function() {"
                + "var boxes = document.getElementsByClassName('sel-cb');"
                + "for (var i = 0; i < boxes.length; i++) { boxes[i].checked = this.checked; }"
                + "});"
                // Ajoute &version=... (sélecteur "Données") à l'URL du lien ou au formaction du bouton cliqué.
                + "document.querySelectorAll('[data-export]').forEach(function(el) {"
                + "el.addEventListener('click', function() {"
                + "var v = document.getElementById('exportVersion').value;"
                + "var s = document.getElementById('exportStatus').value;"
                + "var attr = el.tagName === 'A' ? 'href' : 'formaction';"
                + "var url = el.getAttribute(attr).replace(/&version=[^&]*/, '').replace(/&exportStatut=[^&]*/, '');"
                + "el.setAttribute(attr, url + '&version=' + encodeURIComponent(v)"
                + " + (s ? '&exportStatut=' + encodeURIComponent(s) : ''));"
                + "});});</script>");
        String pageBase = base + (statusFilter == null ? "" : "&statut=" + statusFilter);
        out.println("<p class='pagination'>");
        if (filteredTotal > 0) {
            out.println("<span class='muted'>" + Math.min(offset + 1, filteredTotal) + "–"
                    + Math.min(offset + rowCount, filteredTotal) + " sur " + filteredTotal + "</span> ");
        }
        if (offset > 0) {
            out.println("<a href='" + pageBase + "&offset=" + Math.max(0, offset - PAGE_SIZE) + "'>&laquo; Précédent</a>");
        }
        if (offset + PAGE_SIZE < filteredTotal) {
            out.println("<a href='" + pageBase + "&offset=" + (offset + PAGE_SIZE) + "'>Suivant &raquo;</a>");
        }
        out.println("</p>");
        writeFooter(out);
    }

    private void renderEditForm(Connection conn, PrintWriter out, String formId, String uri, String error, boolean created)
            throws SQLException {
        renderEditForm(conn, out, formId, uri, error, created, false, !isDataOwner());
    }

    /**
     * readOnly (rôle Lecteur de données uniquement, pas Gestionnaire de formulaires) : mêmes
     * données affichées, mais champs désactivés, pas de bouton Enregistrer, pas de lien Supprimer -
     * "il doit juste pouvoir télécharger ou visualiser".
     */
    private void renderEditForm(Connection conn, PrintWriter out, String formId, String uri, String error,
            boolean created, boolean readOnly) throws SQLException {
        renderEditForm(conn, out, formId, uri, error, created, false, readOnly);
    }

    /**
     * duplicated : la soumission affichée vient d'être créée par "Dupliquer" (cf. duplicateSubmission) -
     * message de confirmation distinct, invitant à corriger/renommer la copie avant de sauvegarder.
     */
    private void renderEditForm(Connection conn, PrintWriter out, String formId, String uri, String error,
            boolean created, boolean duplicated, boolean readOnly) throws SQLException {
        renderEditForm(conn, out, formId, uri, error, created, duplicated, readOnly, null);
    }

    /** notice : message de succès libre (ex. après l'annulation d'une modification depuis l'historique). */
    private void renderEditForm(Connection conn, PrintWriter out, String formId, String uri, String error,
            boolean created, boolean duplicated, boolean readOnly, String notice) throws SQLException {
        writeHeader(out, readOnly ? "Consulter la soumission" : "Modifier la soumission");
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");

        String modelUri = resolveSubmissionDataModelUri(conn, formId);
        String coreTable = modelUri == null ? null : resolveCoreTable(conn, modelUri);
        if (coreTable == null) {
            out.println("<p class='err'>Formulaire introuvable : " + htmlEscape(formId) + "</p>");
            writeFooter(out);
            return;
        }

        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);

        Map<String, Object> rawValues = readRawValues(conn, coreTable, columns, uri);
        if (rawValues == null) {
            out.println("<p class='err'>Soumission introuvable (supprimée ou dans la corbeille) : "
                    + htmlEscape(uri) + "</p>");
            writeFooter(out);
            return;
        }
        Map<String, String> corrections = correctionsOf(
                loadCorrections(conn, formId, java.util.Collections.singleton(uri)), uri);
        boolean createdInTool = isCreatedInTool(conn, formId, uri);

        if (error != null) {
            out.println("<p class='err'>Erreur : " + htmlEscape(error) + "</p>");
        } else if (duplicated) {
            out.println("<p class='success'>Soumission dupliquée. Corrigez ou renommez les champs ci-dessous "
                    + "puis enregistrez pour nettoyer cette copie.</p>");
        } else if (created) {
            out.println("<p class='success'>Soumission créée avec succès.</p>");
        } else if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        out.println("<p class='info'>_URI : " + htmlEscape(uri) + "</p>");
        if (createdInTool) {
            out.println("<p class='info'>Soumission créée dans l'outil (saisie, duplication ou import CSV) : "
                    + "ses valeurs d'origine ne viennent pas du terrain.</p>");
        }
        renderStatusBlock(conn, out, formId, uri, readOnly);
        if (!corrections.isEmpty()) {
            out.println("<p class='info'><strong>" + corrections.size() + "</strong> champ(s) corrigé(s), surligné(s) "
                    + "ci-dessous. Les valeurs brutes envoyées du terrain sont conservées telles quelles et restent "
                    + "disponibles dans les exports « Brut ».</p>");
        }
        if (readOnly) {
            out.println("<p class='info'>Lecture seule : votre rôle permet de consulter et télécharger, "
                    + "mais pas de modifier ou supprimer.</p>");
        } else {
            out.println("<div class='toolbar'>");
            out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri)
                    + "&action=duplicate'>" + ICON_DUPLICATE + " Dupliquer</a>");
            if (!corrections.isEmpty()) {
                out.println("<form class='inline' method='post' action='editSubmission?formId=" + urlEncode(formId)
                        + "&action=resetToRaw' onsubmit=\"return confirm('Supprimer toutes les corrections de cette "
                        + "soumission et revenir aux valeurs brutes du terrain ?');\">"
                        + "<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>"
                        + "<button type='submit' class='btn-link'>Tout remettre au brut</button></form>");
            }
            out.println("<a class='btn-link danger' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri)
                    + "&action=delete'>" + ICON_TRASH + " Supprimer cette soumission</a>");
            out.println("</div>");
        }

        out.println("<div class='card'>");
        if (!readOnly) {
            out.println("<form method='post' action='editSubmission'>");
            out.println("<input type='hidden' name='formId' value='" + htmlEscape(formId) + "'>");
            out.println("<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>");
        }
        out.println("<div class='form-grid'>");
        for (ColumnMeta col : columns) {
            Object rawValue = rawValues.get(col.columnName);
            boolean corrected = corrections.containsKey(col.columnName);
            Object value = corrected ? typedValue(col, corrections.get(col.columnName)) : rawValue;
            String inputType = inputTypeFor(col.sqlType);
            out.println("<label>" + htmlEscape(col.elementName) + "</label>");
            String disabled = readOnly ? " disabled" : "";
            out.println("<div" + (corrected ? " style='" + CORRECTED_CELL_STYLE + "padding:6px;border-radius:6px;'" : "") + ">");
            if ("checkbox".equals(inputType)) {
                boolean checked = Boolean.TRUE.equals(value);
                out.println("<input type='checkbox' name='" + htmlEscape(col.columnName) + "' value='true'"
                        + (checked ? " checked" : "") + disabled + ">");
            } else {
                String textValue = value == null ? "" : formatValueForInput(value, inputType);
                out.println("<input type='" + inputType + "' name='" + htmlEscape(col.columnName) + "' value='"
                        + htmlEscape(textValue) + "'" + ("datetime-local".equals(inputType) ? " step='1'" : "")
                        + disabled + ">");
            }
            if (corrected) {
                // Bouton dans le même <form> (formaction) : pas de formulaire imbriqué.
                out.println("<div class='muted' style='margin-top:4px;'>Brut : "
                        + (rawValue == null ? "(vide)" : htmlEscape(formatValueForDisplay(rawValue)))
                        + (readOnly ? "" : " &middot; <button type='submit' class='btn-link' formnovalidate "
                                + "formaction='editSubmission?formId=" + urlEncode(formId) + "&action=resetToRaw&column="
                                + urlEncode(col.columnName) + "'>Revenir au brut</button>")
                        + "</div>");
            }
            out.println("</div>");
        }
        out.println("</div>");
        if (!readOnly) {
            out.println("<div class='actions'><button type='submit'>Enregistrer</button></div>");
            out.println("</form>");
        }
        out.println("</div>");
        renderSubmissionHistory(conn, out, formId, uri, !readOnly);
        writeFooter(out);
    }

    // ---- Duplication (nettoyage : copier une soumission avant de la corriger/renommer) -----------

    private void renderDuplicateConfirm(Connection conn, PrintWriter out, String formId, String uri, String error)
            throws SQLException {
        writeHeader(out, "Dupliquer la soumission");
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri)
                + "'>&laquo; Retour à la soumission</a></p>");
        if (error != null) {
            out.println("<p class='err'>Erreur : " + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p>Créer une copie de la soumission <strong>" + htmlEscape(uri) + "</strong> avec un nouvel "
                + "identifiant ? Vous arriverez ensuite directement sur la copie pour corriger ou renommer les champs "
                + "voulus, sans toucher à l'originale.</p>");
        out.println("<p class='info'>Les pièces jointes (photos, fichiers) ne sont pas dupliquées, seules les "
                + "valeurs des champs le sont.</p>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=duplicateConfirm'>");
        out.println("<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>");
        out.println("<div class='actions'><button type='submit'>" + ICON_DUPLICATE + " Dupliquer</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    /**
     * Copie toutes les valeurs de champs (hors métadonnées et pièces jointes BINARY, absentes de
     * listEditableColumns) d'une soumission existante vers une nouvelle ligne, nouvel _URI. Sert à
     * "nettoyer" des données : dupliquer une soumission douteuse puis corriger/renommer la copie
     * (ex. le champ instanceName quand le formulaire en définit un) avant, éventuellement, de
     * supprimer l'originale - plutôt que de perdre la trace en modifiant l'originale directement.
     * _SUBMISSION_DATE/_CREATION_DATE de la copie = maintenant (c'est une nouvelle ligne du point de
     * vue de la base), au même titre qu'une création manuelle (cf. createSubmission).
     */
    private String duplicateSubmission(Connection conn, String formId, String uri) throws Exception {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        final List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        final Map<String, ColumnMeta> byColumnName = new java.util.HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byColumnName.put(col.columnName, col);
        }
        // La copie reprend les valeurs NETTOYÉES (brut + corrections) de l'originale.
        final Map<String, Object> currentValues = readRawValues(conn, coreTable, columns, uri);
        if (currentValues == null) {
            throw new SQLException("Soumission introuvable : " + uri);
        }
        Map<String, String> corrections = correctionsOf(
                loadCorrections(conn, formId, java.util.Collections.singleton(uri)), uri);
        for (ColumnMeta col : columns) {
            if (corrections.containsKey(col.columnName)) {
                currentValues.put(col.columnName, typedValue(col, corrections.get(col.columnName)));
            }
        }
        return insertSubmissionRow(conn, coreTable, columns, currentUserName(), new ValueSource() {
            public String get(String columnName) {
                ColumnMeta col = byColumnName.get(columnName);
                Object value = currentValues.get(columnName);
                if (col == null || value == null) {
                    return null;
                }
                String inputType = inputTypeFor(col.sqlType);
                if ("checkbox".equals(inputType)) {
                    return Boolean.TRUE.equals(value) ? "true" : "false";
                }
                return formatValueForInput(value, inputType);
            }
        });
    }

    // ---- Suppression (une soumission ou une selection) ------------------------------------------

    private void renderDeleteConfirm(Connection conn, PrintWriter out, String formId, String uri, String error)
            throws SQLException {
        writeHeader(out, "Supprimer la soumission");
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");
        if (error != null) {
            out.println("<p class='err'>Erreur : " + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p>Voulez-vous vraiment supprimer la soumission <strong>" + htmlEscape(uri) + "</strong> ? "
                + "Elle sera placée dans la <a href='editSubmission?formId=" + urlEncode(formId) + "&action=trash'>corbeille</a> "
                + "de ce formulaire, d'où vous pourrez la restaurer.</p>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=deleteConfirm'>");
        out.println("<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>");
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Mettre à la corbeille</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void renderDeleteConfirmMultiple(PrintWriter out, String formId, String[] uris, String error) {
        writeHeader(out, "Supprimer des soumissions");
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");
        if (error != null) {
            out.println("<p class='err'>Erreur : " + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        if (uris == null || uris.length == 0) {
            out.println("<p class='info'>Aucune soumission sélectionnée.</p>");
            out.println("</div>");
            writeFooter(out);
            return;
        }
        out.println("<p>Voulez-vous vraiment supprimer ces <strong>" + uris.length + "</strong> soumission(s) ? "
                + "Elles seront placées dans la corbeille de ce formulaire, d'où vous pourrez les restaurer.</p>");
        out.println("<ul class='list'>");
        for (String u : uris) {
            out.println("<li>" + htmlEscape(u) + "</li>");
        }
        out.println("</ul>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=deleteSelected'>");
        for (String u : uris) {
            out.println("<input type='hidden' name='uri' value='" + htmlEscape(u) + "'>");
        }
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Mettre à la corbeille</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    // ---- Corbeille des soumissions (suppression logique, cf. FormSchemaUtil.notDeletedSql) ------

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        return sb.toString();
    }

    private void deleteSubmission(Connection conn, String formId, String uri) throws SQLException {
        if (deleteSubmissions(conn, formId, new String[] {uri}) == 0) {
            throw new SQLException("Soumission introuvable ou déjà dans la corbeille : " + uri);
        }
    }

    /**
     * Met les soumissions à la corbeille : une ligne dans odk_prod.app_submission_deleted par _URI,
     * la ligne ODK elle-même n'est pas touchée. Le SELECT sur la table core garantit qu'on ne marque
     * que des _URI qui existent vraiment pour ce formulaire ; ON CONFLICT ignore celles déjà marquées.
     */
    private int deleteSubmissions(Connection conn, String formId, String[] uris) throws SQLException {
        if (uris == null || uris.length == 0) {
            return 0;
        }
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        String sql = "INSERT INTO odk_prod.app_submission_deleted(submission_uri, form_id, deleted_by) "
                + "SELECT \"_URI\", ?, ? FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" IN (" + placeholders(uris.length) + ") "
                + "ON CONFLICT (submission_uri) DO NOTHING RETURNING submission_uri";
        List<String> marked = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            ps.setString(2, currentUserName());
            for (int i = 0; i < uris.length; i++) {
                ps.setString(i + 3, uris[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marked.add(rs.getString(1));
                }
            }
        }
        for (String u : marked) {
            recordHistory(conn, formId, u, HISTORY_DELETE, null, null);
        }
        return marked.size();
    }

    /** Restaure : retire simplement la marque de corbeille, la soumission redevient visible partout. */
    private static int restoreSubmissions(Connection conn, String formId, String[] uris) throws SQLException {
        if (uris == null || uris.length == 0) {
            return 0;
        }
        String sql = "DELETE FROM odk_prod.app_submission_deleted WHERE form_id = ? AND submission_uri IN ("
                + placeholders(uris.length) + ") RETURNING submission_uri";
        List<String> restored = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            for (int i = 0; i < uris.length; i++) {
                ps.setString(i + 2, uris[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    restored.add(rs.getString(1));
                }
            }
        }
        for (String u : restored) {
            recordHistory(conn, formId, u, HISTORY_RESTORE, null, null);
        }
        return restored.size();
    }

    /**
     * Suppression définitive (vrai DELETE dans la table core), uniquement pour des soumissions déjà
     * à la corbeille - puis retrait de leur marque. Les deux dans une même transaction.
     */
    private static int purgeSubmissions(Connection conn, String formId, String[] uris) throws SQLException {
        if (uris == null || uris.length == 0) {
            return 0;
        }
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        String inList = placeholders(uris.length);
        String deleteCore = "DELETE FROM odk_prod.\"" + coreTable + "\" c WHERE c.\"_URI\" IN (" + inList + ") "
                + "AND EXISTS (SELECT 1 FROM odk_prod.app_submission_deleted d "
                + "WHERE d.submission_uri = c.\"_URI\" AND d.form_id = ?) RETURNING c.\"_URI\"";
        String deleteMarks = "DELETE FROM odk_prod.app_submission_deleted WHERE form_id = ? AND submission_uri IN (" + inList + ")";
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            List<String> purged = new ArrayList<String>();
            try (PreparedStatement ps = conn.prepareStatement(deleteCore)) {
                int idx = 1;
                for (String u : uris) {
                    ps.setString(idx++, u);
                }
                ps.setString(idx, formId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        purged.add(rs.getString(1));
                    }
                }
            }
            // L'historique est conservé après la suppression définitive (trace d'audit) ; les
            // corrections, elles, n'ont plus de soumission à corriger.
            for (String u : purged) {
                recordHistory(conn, formId, u, HISTORY_PURGE, null, null);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM odk_prod.app_submission_correction WHERE submission_uri = ?")) {
                    ps.setString(1, u);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM odk_prod.app_submission_status WHERE submission_uri = ?")) {
                    ps.setString(1, u);
                    ps.executeUpdate();
                }
            }
            int count = purged.size();
            try (PreparedStatement ps = conn.prepareStatement(deleteMarks)) {
                ps.setString(1, formId);
                for (int i = 0; i < uris.length; i++) {
                    ps.setString(i + 2, uris[i]);
                }
                ps.executeUpdate();
            }
            conn.commit();
            return count;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    private static int countTrashedSubmissions(Connection conn, String formId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM odk_prod.app_submission_deleted WHERE form_id = ?")) {
            ps.setString(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Nombre de colonnes de données affichées dans la corbeille pour reconnaître une soumission. */
    private static final int TRASH_PREVIEW_COLUMNS = 3;

    private void renderSubmissionTrash(Connection conn, PrintWriter out, String formId, String notice, String error)
            throws SQLException {
        writeHeader(out, "Corbeille — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }
        List<ColumnMeta> preview = columns.subList(0, Math.min(TRASH_PREVIEW_COLUMNS, columns.size()));
        out.println("<p class='info'>Les soumissions supprimées restent ici, invisibles partout ailleurs (liste, exports, "
                + "carte, graphiques, médias). <strong>Restaurer</strong> les remet en place telles quelles ; "
                + "<strong>Supprimer définitivement</strong> les efface de la base, sans retour possible.</p>");

        StringBuilder selectCols = new StringBuilder("c.\"_URI\", c.\"_SUBMISSION_DATE\", d.deleted_at, d.deleted_by");
        for (ColumnMeta col : preview) {
            selectCols.append(", c.\"").append(col.columnName).append("\"");
        }
        String sql = "SELECT " + selectCols + " FROM odk_prod.app_submission_deleted d "
                + "JOIN odk_prod.\"" + coreTable + "\" c ON c.\"_URI\" = d.submission_uri "
                + "WHERE d.form_id = ? ORDER BY d.deleted_at DESC";
        List<String[]> rows = new ArrayList<String[]>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String[] row = new String[4 + preview.size()];
                    row[0] = rs.getString(1);
                    Object subDate = rs.getObject(2);
                    row[1] = subDate == null ? "" : subDate.toString();
                    Object delDate = rs.getObject(3);
                    row[2] = delDate == null ? "" : delDate.toString();
                    String by = rs.getString(4);
                    row[3] = by == null ? "" : FormSchemaUtil.extractLocalUsername(by);
                    for (int i = 0; i < preview.size(); i++) {
                        row[4 + i] = formatValueForDisplay(rs.getObject(5 + i));
                    }
                    rows.add(row);
                }
            }
        }
        if (rows.isEmpty()) {
            out.println("<p class='info'>La corbeille de ce formulaire est vide.</p>");
            writeFooter(out);
            return;
        }

        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=restoreSelected'>");
        out.println("<div class='table-enhance'>");
        out.println("<input type='text' class='table-search-input' placeholder='Rechercher dans la corbeille...'>");
        out.println("<div style='overflow-x:auto;'>");
        out.println("<table><thead><tr><th><input type='checkbox' id='selAll'></th><th data-sortable>_URI</th>"
                + "<th data-sortable>Date de soumission</th>");
        for (ColumnMeta col : preview) {
            out.println("<th data-sortable>" + htmlEscape(col.elementName) + "</th>");
        }
        out.println("<th data-sortable>Supprimée le</th><th data-sortable>Par</th></tr></thead><tbody>");
        for (String[] row : rows) {
            out.print("<tr><td><input type='checkbox' name='uri' class='sel-cb' value='" + htmlEscape(row[0]) + "'></td>"
                    + "<td>" + htmlEscape(row[0]) + "</td><td>" + htmlEscape(row[1]) + "</td>");
            for (int i = 0; i < preview.size(); i++) {
                out.print("<td>" + htmlEscape(row[4 + i]) + "</td>");
            }
            out.println("<td>" + htmlEscape(row[2]) + "</td><td>" + htmlEscape(row[3]) + "</td></tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");
        out.println("</div>");
        out.println("<div class='actions'>"
                + "<button type='submit'>Restaurer la sélection</button> "
                + "<button type='submit' class='danger' formaction='editSubmission?formId=" + urlEncode(formId)
                + "&action=purgeSelected' onclick=\"return confirm('Supprimer définitivement les soumissions "
                + "sélectionnées ? Cette action est irréversible.');\">" + ICON_TRASH
                + " Supprimer définitivement la sélection</button>"
                + "</div>");
        out.println("</form>");
        out.println("<script>document.getElementById('selAll').addEventListener('change', function() {"
                + "var boxes = document.getElementsByClassName('sel-cb');"
                + "for (var i = 0; i < boxes.length; i++) { boxes[i].checked = this.checked; }"
                + "});</script>");
        writeFooter(out);
    }

    // ---- Suppression d'un formulaire entier (definition + toutes ses soumissions) ---------------

    private void renderDeleteFormConfirm(Connection conn, PrintWriter out, String formId, String error) throws SQLException {
        writeHeader(out, "Supprimer le formulaire", "formulaires");
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        long count = 0;
        try {
            count = FormSchemaUtil.submissionStats(conn, formId).count;
        } catch (SQLException e) {
            out.println("<p class='err'>Formulaire introuvable : " + htmlEscape(formId) + "</p>");
            writeFooter(out);
            return;
        }
        out.println("<div class='card'>");
        out.println("<p class='err'>Cette action supprime <strong>définitivement</strong> le formulaire "
                + "<strong>" + htmlEscape(formId) + "</strong> et ses <strong>" + count + "</strong> soumission(s). "
                + "Aucune récupération possible.</p>");
        out.println("<p>Pour confirmer, retapez l'identifiant exact du formulaire (<code>" + htmlEscape(formId)
                + "</code>) ci-dessous :</p>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=deleteFormConfirm'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Identifiant du formulaire</label><input type='text' name='confirmFormId' required>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH
                + " Supprimer définitivement le formulaire</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void handleDeleteFormConfirm(Connection conn, HttpServletRequest req, HttpServletResponse resp, String formId)
            throws IOException, ServletException {
        String confirmFormId = req.getParameter("confirmFormId");
        resp.setContentType("text/html;charset=UTF-8");
        if (confirmFormId == null || !confirmFormId.equals(formId)) {
            try {
                renderDeleteFormConfirm(conn, resp.getWriter(), formId,
                        "L'identifiant saisi ne correspond pas exactement à « " + formId + " ». Rien n'a été supprimé.");
            } catch (SQLException e) {
                throw new ServletException(e);
            }
            return;
        }
        try {
            CallingContext cc = ContextFactory.getCallingContext(this, req);
            FormDeletionUtil.deleteForm(formId, cc);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM odk_prod.app_project_form WHERE form_id = ?")) {
                ps.setString(1, formId);
                ps.executeUpdate();
            }
            // Ses soumissions ont disparu avec le formulaire : leurs marques de corbeille aussi.
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM odk_prod.app_submission_deleted WHERE form_id = ?")) {
                ps.setString(1, formId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM odk_prod.app_submission_correction WHERE form_id = ?")) {
                ps.setString(1, formId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM odk_prod.app_submission_status WHERE form_id = ?")) {
                ps.setString(1, formId);
                ps.executeUpdate();
            }
            ActivityLog.record(conn, "form.delete", "formulaire", formId, "Formulaire et toutes ses soumissions supprimés");
            resp.sendRedirect("editSubmission?deletedForm=1");
        } catch (Exception e) {
            try {
                renderDeleteFormConfirm(conn, resp.getWriter(), formId, "Erreur : " + e.getMessage());
            } catch (SQLException e2) {
                throw new ServletException(e2);
            }
        }
    }

    private static String inputTypeFor(String sqlType) {
        if (sqlType == null) {
            return "text";
        }
        switch (sqlType) {
            case "integer":
            case "bigint":
            case "smallint":
            case "numeric":
            case "double precision":
            case "real":
                return "number";
            case "timestamp without time zone":
            case "timestamp with time zone":
                return "datetime-local";
            case "boolean":
                return "checkbox";
            default:
                return "text";
        }
    }

    private static int sqlTypeCodeFor(String sqlType) {
        if (sqlType == null) {
            return java.sql.Types.VARCHAR;
        }
        switch (sqlType) {
            case "integer":
            case "bigint":
            case "smallint":
                return java.sql.Types.BIGINT;
            case "numeric":
            case "double precision":
            case "real":
                return java.sql.Types.NUMERIC;
            case "timestamp without time zone":
            case "timestamp with time zone":
                return java.sql.Types.TIMESTAMP;
            case "boolean":
                return java.sql.Types.BOOLEAN;
            default:
                return java.sql.Types.VARCHAR;
        }
    }

    private static String formatValueForInput(Object value, String inputType) {
        if ("datetime-local".equals(inputType) && value instanceof java.util.Date) {
            // Secondes incluses (input step='1') : sinon un simple "Enregistrer" sans rien toucher
            // tronquerait les secondes et créerait une fausse correction.
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format((java.util.Date) value);
        }
        return value.toString();
    }

    private static String formatValueForDisplay(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "Oui" : "Non";
        }
        return value.toString();
    }

    private void saveSubmission(Connection conn, HttpServletRequest req, String formId, String uri) throws Exception {
        String modelUri = resolveSubmissionDataModelUri(conn, formId);
        String coreTable = modelUri == null ? null : resolveCoreTable(conn, modelUri);
        if (coreTable == null) {
            throw new SQLException("Formulaire introuvable : " + formId);
        }
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        final HttpServletRequest request = req;
        int updated = updateWithHistory(conn, formId, coreTable, columns, columns, uri, new ValueSource() {
            public String get(String columnName) {
                return request.getParameter(columnName);
            }
        }, HISTORY_UPDATE, null);
        if (updated == 0) {
            throw new SQLException("Soumission introuvable ou dans la corbeille : " + uri);
        }
    }

    // ---- Historique des modifications (odk_prod.app_submission_history) --------------------------

    private static final String HISTORY_UPDATE = "UPDATE";
    private static final String HISTORY_UPDATE_CSV = "UPDATE_CSV";
    private static final String HISTORY_REVERT = "REVERT";
    private static final String HISTORY_DELETE = "DELETE";
    private static final String HISTORY_RESTORE = "RESTORE";
    private static final String HISTORY_PURGE = "PURGE";
    private static final String HISTORY_RESET = "RESET";
    private static final String HISTORY_CREATE = "CREATE";
    private static final String HISTORY_DUPLICATE = "DUPLICATE";
    private static final String HISTORY_IMPORT_CSV = "IMPORT_CSV";

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /** Un champ modifié : valeurs avant/après au format texte (relu tel quel par bindValue pour un retour arrière). */
    private static class FieldChange {
        String column;
        String label;
        String before;
        String after;
    }

    private static final java.lang.reflect.Type FIELD_CHANGE_LIST =
            new com.google.gson.reflect.TypeToken<List<FieldChange>>() {}.getType();

    /** Valeur d'une colonne au format texte réinjectable via bindValue (null conservé). */
    private static String historyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof java.util.Date && !(value instanceof Timestamp)) {
            return new Timestamp(((java.util.Date) value).getTime()).toString();
        }
        return value.toString();
    }

    // ---- Statut de validation (odk_prod.app_submission_status) ----------------------------------
    //
    // Pas de ligne = "à vérifier" (état de toute soumission reçue). VALIDEE / REJETEE sont posés par
    // un gestionnaire, avec un commentaire facultatif (motif du rejet). Les corrections ne changent
    // pas le statut : valider = "j'ai vérifié cette soumission telle qu'elle est maintenant".

    /** Bloc "Statut de validation" de la fiche : état actuel, et pour un gestionnaire, les boutons pour le changer. */
    private void renderStatusBlock(Connection conn, PrintWriter out, String formId, String uri, boolean readOnly)
            throws SQLException {
        SubmissionStatus st = loadStatuses(conn, formId, java.util.Collections.singleton(uri)).get(uri);
        String status = st == null ? STATUS_TO_CHECK : st.status;
        out.println("<div class='card' style='margin-bottom:12px;'>");
        out.println("<div><strong>Statut de validation :</strong> " + statusBadge(status)
                + (st == null ? "" : "<span class='muted'> &middot; "
                        + htmlEscape(st.updatedAt == null ? "" : st.updatedAt.toString().replaceFirst("\\.\\d+$", ""))
                        + (st.updatedBy == null ? "" : " &middot; " + htmlEscape(FormSchemaUtil.extractLocalUsername(st.updatedBy)))
                        + "</span>")
                + "</div>");
        if (st != null && st.comment != null) {
            out.println("<p class='info' style='margin:6px 0 0;'>" + (STATUS_REJECTED.equals(status) ? "Motif : " : "Commentaire : ")
                    + htmlEscape(st.comment) + "</p>");
        }
        if (!readOnly) {
            out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=setStatus' "
                    + "style='margin-top:8px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;'>");
            out.println("<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>");
            out.println("<input type='text' name='comment' placeholder='Commentaire / motif du rejet (facultatif)' "
                    + "style='flex:1;min-width:220px;' value='" + htmlEscape(st == null || st.comment == null ? "" : st.comment) + "'>");
            if (!STATUS_VALID.equals(status) || (st != null && st.comment != null)) {
                out.println("<button type='submit' name='status' value='" + STATUS_VALID + "'>Valider</button>");
            }
            out.println("<button type='submit' name='status' value='" + STATUS_REJECTED + "' class='danger'>"
                    + (STATUS_REJECTED.equals(status) ? "Mettre à jour le rejet" : "Rejeter") + "</button>");
            if (!STATUS_TO_CHECK.equals(status)) {
                out.println("<button type='submit' name='status' value='" + STATUS_TO_CHECK + "' class='btn-link'>"
                        + "Remettre à vérifier</button>");
            }
            out.println("</form>");
        }
        out.println("</div>");
    }

    private static final String STATUS_TO_CHECK = "A_VERIFIER";
    private static final String STATUS_VALID = "VALIDEE";
    private static final String STATUS_REJECTED = "REJETEE";
    /** Filtre d'export / de liste : tout sauf les rejetées. */
    private static final String STATUS_NOT_REJECTED = "NON_REJETEE";
    private static final String HISTORY_STATUS = "STATUS";

    private static class SubmissionStatus {
        String status = STATUS_TO_CHECK;
        String comment;
        Timestamp updatedAt;
        String updatedBy;
    }

    private static String statusLabel(String status) {
        if (STATUS_VALID.equals(status)) {
            return "Validée";
        }
        if (STATUS_REJECTED.equals(status)) {
            return "Rejetée";
        }
        return "À vérifier";
    }

    /** Pastille de statut, couleurs translucides lisibles en thème clair comme sombre. */
    private static String statusBadge(String status) {
        String bg = STATUS_VALID.equals(status) ? "rgba(46,160,67,.20)"
                : STATUS_REJECTED.equals(status) ? "rgba(218,54,51,.20)" : "rgba(128,128,128,.18)";
        return "<span style='display:inline-block;padding:2px 8px;border-radius:10px;font-size:.85em;white-space:nowrap;"
                + "background:" + bg + ";'>" + htmlEscape(statusLabel(status)) + "</span>";
    }

    /** Valeur de filtre reconnue (liste / exports), ou null = toutes. */
    private static String normalizeStatusFilter(String raw) {
        if (STATUS_TO_CHECK.equals(raw) || STATUS_VALID.equals(raw) || STATUS_REJECTED.equals(raw)
                || STATUS_NOT_REJECTED.equals(raw)) {
            return raw;
        }
        return null;
    }

    /** Condition SQL (préfixée de " AND ") sur une table core, selon le filtre de statut ; "" si aucun filtre. */
    private static String statusFilterSql(String filter) {
        if (filter == null) {
            return "";
        }
        String sub = "SELECT 1 FROM odk_prod.app_submission_status st WHERE st.submission_uri = \"_URI\"";
        if (STATUS_TO_CHECK.equals(filter)) {
            return " AND NOT EXISTS (" + sub + ")";
        }
        if (STATUS_NOT_REJECTED.equals(filter)) {
            return " AND NOT EXISTS (" + sub + " AND st.status = '" + STATUS_REJECTED + "')";
        }
        return " AND EXISTS (" + sub + " AND st.status = '" + (STATUS_VALID.equals(filter) ? STATUS_VALID : STATUS_REJECTED) + "')";
    }

    /** Statuts d'un formulaire (uris == null : tous), uri -> statut. Absent = à vérifier. */
    private static Map<String, SubmissionStatus> loadStatuses(Connection conn, String formId, java.util.Collection<String> uris)
            throws SQLException {
        Map<String, SubmissionStatus> result = new HashMap<String, SubmissionStatus>();
        if (uris != null && uris.isEmpty()) {
            return result;
        }
        String sql = "SELECT submission_uri, status, comment, updated_at, updated_by FROM odk_prod.app_submission_status "
                + "WHERE form_id = ?" + (uris == null ? "" : " AND submission_uri IN (" + placeholders(uris.size()) + ")");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            if (uris != null) {
                int idx = 2;
                for (String u : uris) {
                    ps.setString(idx++, u);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SubmissionStatus s = new SubmissionStatus();
                    s.status = rs.getString(2);
                    s.comment = rs.getString(3);
                    s.updatedAt = rs.getTimestamp(4);
                    s.updatedBy = rs.getString(5);
                    result.put(rs.getString(1), s);
                }
            }
        }
        return result;
    }

    private static String statusOf(Map<String, SubmissionStatus> statuses, String uri) {
        SubmissionStatus s = statuses.get(uri);
        return s == null ? STATUS_TO_CHECK : s.status;
    }

    /** Nombre de soumissions actives (hors corbeille) par statut, pour les compteurs de la liste. */
    private static Map<String, Integer> countStatuses(Connection conn, String coreTable) throws SQLException {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put(STATUS_TO_CHECK, 0);
        counts.put(STATUS_VALID, 0);
        counts.put(STATUS_REJECTED, 0);
        String sql = "SELECT COALESCE(st.status, '" + STATUS_TO_CHECK + "'), COUNT(*) FROM odk_prod.\"" + coreTable + "\" "
                + "LEFT JOIN odk_prod.app_submission_status st ON st.submission_uri = \"_URI\" "
                + "WHERE " + FormSchemaUtil.CORE_NOT_DELETED + " GROUP BY 1";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    /**
     * Pose un statut sur des soumissions actives de ce formulaire (À VÉRIFIER = suppression de la
     * ligne). Ignore les _URI inexistantes ou à la corbeille, et celles dont le statut et le
     * commentaire sont déjà ceux demandés. Trace chaque changement dans l'historique. Retourne le
     * nombre de soumissions dont le statut a changé.
     */
    private static int setStatus(Connection conn, String formId, String[] uris, String status, String comment)
            throws SQLException {
        if (uris == null || uris.length == 0) {
            return 0;
        }
        if (!STATUS_VALID.equals(status) && !STATUS_REJECTED.equals(status)) {
            status = STATUS_TO_CHECK;
        }
        String cleanComment = comment == null || comment.trim().isEmpty() ? null : comment.trim();
        if (STATUS_TO_CHECK.equals(status)) {
            cleanComment = null;
        }
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        Set<String> active = new HashSet<String>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT \"_URI\" FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" IN ("
                + placeholders(uris.length) + ") AND " + FormSchemaUtil.CORE_NOT_DELETED)) {
            for (int i = 0; i < uris.length; i++) {
                ps.setString(i + 1, uris[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    active.add(rs.getString(1));
                }
            }
        }
        Map<String, SubmissionStatus> previous = loadStatuses(conn, formId, active);
        int changed = 0;
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (String uri : active) {
                SubmissionStatus prev = previous.get(uri);
                String prevStatus = prev == null ? STATUS_TO_CHECK : prev.status;
                String prevComment = prev == null ? null : prev.comment;
                boolean sameComment = cleanComment == null ? prevComment == null : cleanComment.equals(prevComment);
                if (prevStatus.equals(status) && sameComment) {
                    continue;
                }
                if (STATUS_TO_CHECK.equals(status)) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM odk_prod.app_submission_status WHERE submission_uri = ?")) {
                        ps.setString(1, uri);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO odk_prod.app_submission_status(submission_uri, form_id, status, comment, updated_by) "
                                    + "VALUES (?,?,?,?,?) ON CONFLICT (submission_uri) DO UPDATE SET status = EXCLUDED.status, "
                                    + "comment = EXCLUDED.comment, updated_at = now(), updated_by = EXCLUDED.updated_by")) {
                        ps.setString(1, uri);
                        ps.setString(2, formId);
                        ps.setString(3, status);
                        ps.setString(4, cleanComment);
                        ps.setString(5, currentUserName());
                        ps.executeUpdate();
                    }
                }
                recordHistory(conn, formId, uri, HISTORY_STATUS, null, statusLabel(prevStatus) + " → " + statusLabel(status)
                        + (cleanComment == null ? "" : " : " + cleanComment));
                changed++;
            }
            conn.commit();
            return changed;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    // ---- Couche de corrections (odk_prod.app_submission_correction) -----------------------------
    //
    // La table core d'ODK n'est plus jamais modifiée par le nettoyage : elle garde le BRUT tel que
    // reçu du terrain. Chaque correction remplace la valeur d'un champ à l'affichage et dans les
    // exports "nettoyés". Valeur nettoyée = correction si elle existe, sinon brut.

    /**
     * Convertit un texte (saisie du formulaire, cellule CSV ou valeur canonique stockée) en valeur
     * typée selon la colonne - mêmes règles que bindValue. Chaîne vide = null.
     */
    private static Object parseInputValue(ColumnMeta col, String rawValue) throws SQLException {
        String inputType = inputTypeFor(col.sqlType);
        if ("checkbox".equals(inputType)) {
            return Boolean.valueOf("true".equals(rawValue == null ? null : rawValue.trim()));
        }
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }
        try {
            switch (col.sqlType) {
                case "integer":
                case "bigint":
                case "smallint":
                    return Long.valueOf(rawValue.trim());
                case "numeric":
                case "double precision":
                case "real":
                    return new BigDecimal(rawValue.trim());
                case "timestamp without time zone":
                case "timestamp with time zone":
                    return parseTimestamp(rawValue.trim());
                default:
                    return rawValue;
            }
        } catch (NumberFormatException e) {
            throw new SQLException("Valeur invalide pour " + col.elementName + " : " + rawValue);
        }
    }

    /** Valeur typée d'une correction stockée (texte canonique produit par historyValue). */
    private static Object typedValue(ColumnMeta col, String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return parseInputValue(col, stored);
        } catch (SQLException e) {
            return stored;
        }
    }

    /**
     * Égalité "métier" de deux valeurs d'une même colonne : nombres comparés numériquement
     * (12.50 == 12.5), dates à la seconde près (le formulaire ne transmet pas les millisecondes),
     * case à cocher vide == non cochée, texte vide == null.
     */
    private static boolean sameValue(ColumnMeta col, Object a, Object b) {
        if ("checkbox".equals(inputTypeFor(col.sqlType))) {
            return Boolean.TRUE.equals(a) == Boolean.TRUE.equals(b);
        }
        if (a instanceof String && ((String) a).trim().isEmpty()) {
            a = null;
        }
        if (b instanceof String && ((String) b).trim().isEmpty()) {
            b = null;
        }
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (a instanceof java.util.Date && b instanceof java.util.Date) {
            return ((java.util.Date) a).getTime() / 1000 == ((java.util.Date) b).getTime() / 1000;
        }
        return a.toString().equals(b.toString());
    }

    /**
     * Corrections d'un formulaire : uri -> (colonne -> valeur stockée). uris == null : toutes celles
     * du formulaire (exports), sinon seulement celles des soumissions données (une page de liste).
     */
    private static Map<String, Map<String, String>> loadCorrections(Connection conn, String formId,
            java.util.Collection<String> uris) throws SQLException {
        Map<String, Map<String, String>> result = new HashMap<String, Map<String, String>>();
        if (uris != null && uris.isEmpty()) {
            return result;
        }
        String sql = "SELECT submission_uri, column_name, value FROM odk_prod.app_submission_correction WHERE form_id = ?"
                + (uris == null ? "" : " AND submission_uri IN (" + placeholders(uris.size()) + ")");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            if (uris != null) {
                int idx = 2;
                for (String u : uris) {
                    ps.setString(idx++, u);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> byColumn = result.get(rs.getString(1));
                    if (byColumn == null) {
                        byColumn = new HashMap<String, String>();
                        result.put(rs.getString(1), byColumn);
                    }
                    byColumn.put(rs.getString(2), rs.getString(3));
                }
            }
        }
        return result;
    }

    private static Map<String, String> correctionsOf(Map<String, Map<String, String>> all, String uri) {
        Map<String, String> c = all.get(uri);
        return c == null ? java.util.Collections.<String, String>emptyMap() : c;
    }

    /** Valeur d'un champ : nettoyée (correction si elle existe) ou brute (table ODK). */
    private static Object fieldValue(ResultSet rs, ColumnMeta col, Map<String, String> corrections, boolean cleaned)
            throws SQLException {
        if (cleaned && corrections.containsKey(col.columnName)) {
            return typedValue(col, corrections.get(col.columnName));
        }
        return rs.getObject(col.columnName);
    }

    private static void upsertCorrection(Connection conn, String formId, String uri, String column, String value)
            throws SQLException {
        String sql = "INSERT INTO odk_prod.app_submission_correction(submission_uri, column_name, form_id, value, corrected_by) "
                + "VALUES (?,?,?,?,?) ON CONFLICT (submission_uri, column_name) DO UPDATE SET value = EXCLUDED.value, "
                + "corrected_at = now(), corrected_by = EXCLUDED.corrected_by";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uri);
            ps.setString(2, column);
            ps.setString(3, formId);
            ps.setString(4, value);
            ps.setString(5, currentUserName());
            ps.executeUpdate();
        }
    }

    private static void deleteCorrection(Connection conn, String uri, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM odk_prod.app_submission_correction WHERE submission_uri = ? AND column_name = ?")) {
            ps.setString(1, uri);
            ps.setString(2, column);
            ps.executeUpdate();
        }
    }

    /** Valeurs BRUTES des colonnes d'une soumission active, ou null si introuvable / à la corbeille. */
    private static Map<String, Object> readRawValues(Connection conn, String coreTable, List<ColumnMeta> columns, String uri)
            throws SQLException {
        String sql = "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" = ? AND " + FormSchemaUtil.CORE_NOT_DELETED;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uri);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> values = new HashMap<String, Object>();
                for (ColumnMeta col : columns) {
                    values.put(col.columnName, rs.getObject(col.columnName));
                }
                return values;
            }
        }
    }

    /** Remet un champ (ou tous si column == null) à sa valeur brute en supprimant la/les correction(s). */
    private void resetToRaw(Connection conn, String formId, String uri, String column) throws SQLException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Map<String, Object> raw = readRawValues(conn, coreTable, columns, uri);
            if (raw == null) {
                throw new SQLException("Soumission introuvable ou dans la corbeille : " + uri);
            }
            Map<String, String> corrections = correctionsOf(loadCorrections(conn, formId, java.util.Collections.singleton(uri)), uri);
            List<FieldChange> changes = new ArrayList<FieldChange>();
            for (ColumnMeta col : columns) {
                if ((column == null || column.equals(col.columnName)) && corrections.containsKey(col.columnName)) {
                    FieldChange c = new FieldChange();
                    c.column = col.columnName;
                    c.label = col.elementName;
                    c.before = corrections.get(col.columnName);
                    c.after = historyValue(raw.get(col.columnName));
                    changes.add(c);
                    deleteCorrection(conn, uri, col.columnName);
                }
            }
            if (!changes.isEmpty()) {
                recordHistory(conn, formId, uri, HISTORY_RESET, GSON.toJson(changes), null);
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    private static void recordHistory(Connection conn, String formId, String uri, String action, String changesJson,
            String note) throws SQLException {
        String sql = "INSERT INTO odk_prod.app_submission_history(form_id, submission_uri, action, changes, note, changed_by) "
                + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            ps.setString(2, uri);
            ps.setString(3, action);
            ps.setString(4, changesJson);
            ps.setString(5, note);
            ps.setString(6, currentUserName());
            ps.executeUpdate();
        }
    }

    /**
     * Point unique de modification (nettoyage) d'une soumission. La table ODK n'est PAS modifiée :
     * pour chaque champ, la nouvelle valeur devient une correction - ou la correction est supprimée
     * si la nouvelle valeur est égale au brut. Seuls les champs dont la valeur nettoyée change
     * réellement sont enregistrés dans l'historique. Le tout dans une transaction. Retourne 1, ou 0 si
     * la soumission est introuvable / à la corbeille (rien n'est écrit). allColumns est conservé pour
     * la symétrie des appels (toutes les colonnes du formulaire).
     */
    private static int updateWithHistory(Connection conn, String formId, String coreTable, List<ColumnMeta> allColumns,
            List<ColumnMeta> updateColumns, String uri, ValueSource values, String action, String note) throws SQLException {
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Map<String, Object> raw = readRawValues(conn, coreTable, updateColumns, uri);
            if (raw == null) {
                conn.rollback();
                return 0;
            }
            Map<String, String> corrections = correctionsOf(
                    loadCorrections(conn, formId, java.util.Collections.singleton(uri)), uri);
            List<FieldChange> changes = new ArrayList<FieldChange>();
            for (ColumnMeta col : updateColumns) {
                Object rawValue = raw.get(col.columnName);
                boolean corrected = corrections.containsKey(col.columnName);
                Object current = corrected ? typedValue(col, corrections.get(col.columnName)) : rawValue;
                Object wanted = parseInputValue(col, values.get(col.columnName));
                if (sameValue(col, wanted, current)) {
                    continue;
                }
                if (sameValue(col, wanted, rawValue)) {
                    deleteCorrection(conn, uri, col.columnName);
                } else {
                    upsertCorrection(conn, formId, uri, col.columnName, historyValue(wanted));
                }
                FieldChange c = new FieldChange();
                c.column = col.columnName;
                c.label = col.elementName;
                c.before = historyValue(current);
                c.after = historyValue(sameValue(col, wanted, rawValue) ? rawValue : wanted);
                changes.add(c);
            }
            if (!changes.isEmpty()) {
                recordHistory(conn, formId, uri, action, GSON.toJson(changes), note);
            }
            conn.commit();
            return 1;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    private static class HistoryEntry {
        int id;
        String action;
        List<FieldChange> changes;
        String note;
        Timestamp changedAt;
        String changedBy;
    }

    private static List<HistoryEntry> listHistory(Connection conn, String formId, String uri) throws SQLException {
        String sql = "SELECT id, action, changes, note, changed_at, changed_by FROM odk_prod.app_submission_history "
                + "WHERE form_id = ? AND submission_uri = ? ORDER BY changed_at DESC, id DESC";
        List<HistoryEntry> entries = new ArrayList<HistoryEntry>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            ps.setString(2, uri);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HistoryEntry e = new HistoryEntry();
                    e.id = rs.getInt(1);
                    e.action = rs.getString(2);
                    String json = rs.getString(3);
                    List<FieldChange> parsed = json == null ? null : GSON.<List<FieldChange>>fromJson(json, FIELD_CHANGE_LIST);
                    e.changes = parsed == null ? new ArrayList<FieldChange>() : parsed;
                    e.note = rs.getString(4);
                    e.changedAt = rs.getTimestamp(5);
                    e.changedBy = rs.getString(6);
                    entries.add(e);
                }
            }
        }
        return entries;
    }

    /** Vrai si la soumission a été créée dans l'outil (saisie, duplication, import CSV) et non reçue du terrain. */
    private static boolean isCreatedInTool(Connection conn, String formId, String uri) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM odk_prod.app_submission_history "
                + "WHERE form_id = ? AND submission_uri = ? AND action IN (?,?,?) LIMIT 1")) {
            ps.setString(1, formId);
            ps.setString(2, uri);
            ps.setString(3, HISTORY_CREATE);
            ps.setString(4, HISTORY_DUPLICATE);
            ps.setString(5, HISTORY_IMPORT_CSV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static HistoryEntry findHistoryEntry(Connection conn, String formId, String uri, int historyId)
            throws SQLException {
        for (HistoryEntry e : listHistory(conn, formId, uri)) {
            if (e.id == historyId) {
                return e;
            }
        }
        return null;
    }

    /**
     * Retour arrière : remet les champs d'une modification à leur valeur "avant". Les champs modifiés
     * depuis par une autre opération sont eux aussi écrasés (l'utilisateur confirme). Le retour est
     * lui-même une modification enregistrée (action REVERT), donc annulable à son tour.
     */
    private void revertHistoryEntry(Connection conn, String formId, String uri, int historyId) throws SQLException {
        HistoryEntry entry = findHistoryEntry(conn, formId, uri, historyId);
        if (entry == null || entry.changes.isEmpty()) {
            throw new SQLException("Entrée d'historique introuvable ou sans modification de champ.");
        }
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        Map<String, ColumnMeta> byName = new HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byName.put(col.columnName, col);
        }
        List<ColumnMeta> updateColumns = new ArrayList<ColumnMeta>();
        final Map<String, String> beforeValues = new HashMap<String, String>();
        for (FieldChange c : entry.changes) {
            ColumnMeta col = byName.get(c.column);
            if (col != null) {
                updateColumns.add(col);
                beforeValues.put(c.column, c.before);
            }
        }
        if (updateColumns.isEmpty()) {
            throw new SQLException("Les champs de cette modification n'existent plus dans le formulaire.");
        }
        String note = "Retour à l'état d'avant la modification du "
                + (entry.changedAt == null ? "?" : entry.changedAt.toString().replaceFirst("\\.\\d+$", ""));
        int updated = updateWithHistory(conn, formId, coreTable, columns, updateColumns, uri, new ValueSource() {
            public String get(String columnName) {
                return beforeValues.get(columnName);
            }
        }, HISTORY_REVERT, note);
        if (updated == 0) {
            throw new SQLException("Soumission introuvable ou dans la corbeille : " + uri);
        }
    }

    private static String historyActionLabel(String action) {
        if (HISTORY_UPDATE.equals(action)) {
            return "Modification";
        }
        if (HISTORY_UPDATE_CSV.equals(action)) {
            return "Mise à jour CSV";
        }
        if (HISTORY_REVERT.equals(action)) {
            return "Retour arrière";
        }
        if (HISTORY_DELETE.equals(action)) {
            return "Mise à la corbeille";
        }
        if (HISTORY_RESTORE.equals(action)) {
            return "Restauration";
        }
        if (HISTORY_PURGE.equals(action)) {
            return "Suppression définitive";
        }
        if (HISTORY_RESET.equals(action)) {
            return "Retour au brut";
        }
        if (HISTORY_CREATE.equals(action)) {
            return "Création dans l'outil";
        }
        if (HISTORY_DUPLICATE.equals(action)) {
            return "Duplication";
        }
        if (HISTORY_IMPORT_CSV.equals(action)) {
            return "Création par import CSV";
        }
        if (HISTORY_STATUS.equals(action)) {
            return "Statut";
        }
        return action;
    }

    private void renderSubmissionHistory(Connection conn, PrintWriter out, String formId, String uri, boolean canRevert)
            throws SQLException {
        List<HistoryEntry> entries = listHistory(conn, formId, uri);
        out.println("<h2 style='margin-top:32px;'>Historique des modifications</h2>");
        if (entries.isEmpty()) {
            out.println("<p class='info'>Aucune modification enregistrée pour cette soumission "
                    + "(l'historique démarre avec cette fonctionnalité).</p>");
            return;
        }
        for (HistoryEntry e : entries) {
            String by = e.changedBy == null ? "" : FormSchemaUtil.extractLocalUsername(e.changedBy);
            String when = e.changedAt == null ? "" : e.changedAt.toString().replaceFirst("\\.\\d+$", "");
            out.println("<div class='card' style='margin-bottom:12px;'>");
            out.println("<div style='display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;'>"
                    + "<div><strong>" + htmlEscape(historyActionLabel(e.action)) + "</strong>"
                    + "<span class='muted'> &middot; " + htmlEscape(when) + (by.isEmpty() ? "" : " &middot; " + htmlEscape(by))
                    + "</span></div>");
            if (canRevert && !e.changes.isEmpty()) {
                out.println("<form class='inline' method='post' action='editSubmission?formId=" + urlEncode(formId)
                        + "&action=revertHistory' onsubmit=\"return confirm('Remettre ces champs à leur valeur d\\'avant "
                        + "cette modification ? Les changements faits depuis sur ces mêmes champs seront écrasés.');\">"
                        + "<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>"
                        + "<input type='hidden' name='historyId' value='" + e.id + "'>"
                        + "<button type='submit' class='btn-link'>Annuler cette modification</button></form>");
            }
            out.println("</div>");
            if (e.note != null && !e.note.isEmpty()) {
                out.println("<p class='info' style='margin:6px 0 0;'>" + htmlEscape(e.note) + "</p>");
            }
            if (!e.changes.isEmpty()) {
                out.println("<div style='overflow-x:auto;'><table style='margin-top:8px;'><thead><tr><th>Champ</th>"
                        + "<th>Avant</th><th>Après</th></tr></thead><tbody>");
                for (FieldChange c : e.changes) {
                    out.println("<tr><td>" + htmlEscape(c.label != null ? c.label : c.column) + "</td>"
                            + "<td>" + (c.before == null ? "<span class='muted'>(vide)</span>" : htmlEscape(c.before)) + "</td>"
                            + "<td>" + (c.after == null ? "<span class='muted'>(vide)</span>" : htmlEscape(c.after)) + "</td></tr>");
                }
                out.println("</tbody></table></div>");
            }
            out.println("</div>");
        }
    }

    private static void bindValue(PreparedStatement ps, int idx, ColumnMeta col, String rawValue) throws SQLException {
        String inputType = inputTypeFor(col.sqlType);
        if ("checkbox".equals(inputType)) {
            ps.setBoolean(idx, "true".equals(rawValue));
            return;
        }
        if (rawValue == null || rawValue.trim().isEmpty()) {
            ps.setNull(idx, sqlTypeCodeFor(col.sqlType));
            return;
        }
        try {
            switch (col.sqlType) {
                case "integer":
                case "bigint":
                case "smallint":
                    ps.setLong(idx, Long.parseLong(rawValue.trim()));
                    break;
                case "numeric":
                case "double precision":
                case "real":
                    ps.setBigDecimal(idx, new BigDecimal(rawValue.trim()));
                    break;
                case "timestamp without time zone":
                case "timestamp with time zone":
                    ps.setTimestamp(idx, parseTimestamp(rawValue.trim()));
                    break;
                case "boolean":
                    ps.setBoolean(idx, Boolean.parseBoolean(rawValue.trim()));
                    break;
                default:
                    ps.setString(idx, rawValue);
            }
        } catch (NumberFormatException e) {
            throw new SQLException("Valeur invalide pour " + col.elementName + " : " + rawValue);
        }
    }

    // ---- Direct data entry (new submission) ----------------------------------------------------

    private void renderCreateForm(Connection conn, PrintWriter out, String formId, String error) throws SQLException {
        writeHeader(out, "Nouvelle soumission — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");

        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }

        if (error != null) {
            out.println("<p class='err'>Erreur : " + htmlEscape(error) + "</p>");
        }

        out.println("<div class='card'>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=create'>");
        out.println("<div class='form-grid'>");
        for (ColumnMeta col : columns) {
            String inputType = inputTypeFor(col.sqlType);
            out.println("<label>" + htmlEscape(col.elementName) + "</label>");
            if ("checkbox".equals(inputType)) {
                out.println("<input type='checkbox' name='" + htmlEscape(col.columnName) + "' value='true'>");
            } else {
                out.println("<input type='" + inputType + "' name='" + htmlEscape(col.columnName) + "'>");
            }
        }
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>" + ICON_PLUS + " Créer la soumission</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private String createSubmission(Connection conn, final HttpServletRequest req, String formId) throws Exception {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        return insertSubmissionRow(conn, coreTable, columns, currentUserName(), null, new ValueSource() {
            public String get(String columnName) {
                return req.getParameter(columnName);
            }
        });
    }

    private String insertSubmissionRow(Connection conn, String coreTable, List<ColumnMeta> columns, String currentUser,
            ValueSource values) throws SQLException {
        return insertSubmissionRow(conn, coreTable, columns, currentUser, null, values);
    }

    /**
     * submissionDate : si non nul, utilisé pour _SUBMISSION_DATE au lieu de l'horodatage actuel -
     * pour un import CSV qui doit préserver la date de soumission d'origine du fichier plutôt que
     * de la remplacer par la date du jour (cf. insertCsvRows). _CREATION_DATE/_LAST_UPDATE_DATE
     * restent toujours l'horodatage actuel : ils reflètent quand la ligne a été créée dans cette
     * base, ce qui est réellement maintenant, que la donnée elle-même date d'avant ou non.
     */
    private String insertSubmissionRow(Connection conn, String coreTable, List<ColumnMeta> columns, String currentUser,
            Timestamp submissionDate, ValueSource values) throws SQLException {
        String newUri = "uuid:" + java.util.UUID.randomUUID().toString();
        StringBuilder cols = new StringBuilder("\"_URI\",\"_CREATOR_URI_USER\",\"_CREATION_DATE\",\"_LAST_UPDATE_URI_USER\","
                + "\"_LAST_UPDATE_DATE\",\"_IS_COMPLETE\",\"_SUBMISSION_DATE\",\"_MARKED_AS_COMPLETE_DATE\",\"META_INSTANCE_ID\"");
        StringBuilder placeholders = new StringBuilder("?,?,?,?,?,?,?,?,?");
        for (ColumnMeta col : columns) {
            cols.append(",\"").append(col.columnName).append("\"");
            placeholders.append(",?");
        }
        String sql = "INSERT INTO odk_prod.\"" + coreTable + "\" (" + cols + ") VALUES (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            Timestamp now = new Timestamp(System.currentTimeMillis());
            ps.setString(idx++, newUri);
            ps.setString(idx++, currentUser);
            ps.setTimestamp(idx++, now);
            ps.setString(idx++, currentUser);
            ps.setTimestamp(idx++, now);
            ps.setBoolean(idx++, true);
            ps.setTimestamp(idx++, submissionDate != null ? submissionDate : now);
            ps.setTimestamp(idx++, now);
            ps.setString(idx++, newUri);
            for (ColumnMeta col : columns) {
                bindValue(ps, idx++, col, values.get(col.columnName));
            }
            ps.executeUpdate();
        }
        return newUri;
    }

    // ---- Bulk CSV import -------------------------------------------------------------------------

    private void renderCsvUploadForm(Connection conn, PrintWriter out, String formId, String error) throws SQLException {
        writeHeader(out, "Importer des soumissions (CSV) — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p class='info'>Le fichier CSV doit contenir une ligne d'en-tête avec les noms de colonnes exacts du "
                + "formulaire. <a href='editSubmission?formId=" + urlEncode(formId)
                + "&action=csvTemplate'>Télécharger un modèle CSV vide</a> pour obtenir les bons en-têtes.</p>");
        out.println("<p class='info'>Si le fichier contient une colonne <strong>_SUBMISSION_DATE</strong> (par exemple "
                + "issu d'un export), sa valeur est utilisée comme date de soumission pour chaque ligne ; sinon, "
                + "la date du jour est utilisée.</p>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId)
                + "&action=csvUpload' enctype='multipart/form-data'>");
        out.println("<p><input type='file' name='csvFile' accept='.csv,text/csv' required></p>");
        out.println("<div class='actions'><button type='submit'>" + ICON_UPLOAD + " Importer</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void streamCsvTemplate(Connection conn, HttpServletResponse resp, String formId) throws SQLException, IOException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\""
                + formId.replaceAll("[^A-Za-z0-9_-]", "_") + "_modele.csv\"");
        PrintWriter out = resp.getWriter();
        out.print('﻿');
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                header.append(',');
            }
            header.append(csvEscape(columns.get(i).columnName));
        }
        out.println(header.toString());
        out.flush();
    }

    /** Étape 1 après l'upload du fichier : analyse le CSV, montre un aperçu et demande confirmation avant d'écrire quoi que ce soit. */
    private void renderCsvPreview(Connection conn, HttpServletRequest req, PrintWriter out, String formId)
            throws SQLException {
        writeHeader(out, "Aperçu de l'import CSV — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "&action=csv'>&laquo; Choisir un autre fichier</a></p>");

        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }

        java.util.Map<String, ColumnMeta> byColumnName = new java.util.HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byColumnName.put(col.columnName.toUpperCase(java.util.Locale.ROOT), col);
        }

        String csvContent = null;
        try {
            if (ServletFileUpload.isMultipartContent(req)) {
                ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
                List<FileItem> items = upload.parseRequest(req);
                for (FileItem item : items) {
                    if (!item.isFormField() && "csvFile".equals(item.getFieldName())) {
                        csvContent = item.getString("UTF-8");
                    }
                }
            }
        } catch (Exception e) {
            out.println("<p class='err'>Erreur de lecture du fichier : " + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }

        if (csvContent == null || csvContent.trim().isEmpty()) {
            out.println("<p class='err'>Aucun fichier CSV reçu.</p>");
            writeFooter(out);
            return;
        }
        if (csvContent.charAt(0) == '﻿') {
            csvContent = csvContent.substring(1);
        }

        List<String[]> rows = parseCsv(csvContent);
        if (rows.isEmpty()) {
            out.println("<p class='err'>Fichier CSV vide.</p>");
            writeFooter(out);
            return;
        }

        String[] header = rows.get(0);
        ColumnMeta[] columnForIndex = new ColumnMeta[header.length];
        List<String> recognized = new ArrayList<String>();
        List<String> unrecognized = new ArrayList<String>();
        boolean[] isMetaIndex = new boolean[header.length];
        boolean hasSubmissionDateColumn = false;
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].trim().toUpperCase(java.util.Locale.ROOT);
            if ("_SUBMISSION_DATE".equals(h)) {
                hasSubmissionDateColumn = true;
                isMetaIndex[i] = true;
                continue;
            }
            if (isProtectedMetaColumn(h)) {
                // Colonne technique (ex. _URI, présente si le fichier vient d'un export) : pas une
                // donnée du formulaire, ni recensée ni signalée comme "non reconnue".
                isMetaIndex[i] = true;
                continue;
            }
            ColumnMeta col = byColumnName.get(h);
            columnForIndex[i] = col;
            if (col != null) {
                recognized.add(header[i]);
            } else if (!h.isEmpty()) {
                unrecognized.add(header[i]);
            }
        }

        int dataRowCount = 0;
        int blankRowCount = 0;
        for (int r = 1; r < rows.size(); r++) {
            boolean blank = true;
            for (String cell : rows.get(r)) {
                if (cell != null && !cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                blankRowCount++;
            } else {
                dataRowCount++;
            }
        }

        out.println("<div class='card'>");
        out.println("<p class='info'><strong>" + dataRowCount + "</strong> soumission(s) seront créées"
                + (blankRowCount > 0 ? " (" + blankRowCount + " ligne(s) vide(s) ignorée(s))" : "") + ".</p>");
        if (!recognized.isEmpty()) {
            out.println("<p class='success'>Colonnes reconnues : " + htmlEscape(String.join(", ", recognized)) + "</p>");
        }
        if (!unrecognized.isEmpty()) {
            out.println("<p class='err'>Colonnes ignorées (non reconnues par ce formulaire) : "
                    + htmlEscape(String.join(", ", unrecognized)) + "</p>");
        }
        if (recognized.isEmpty()) {
            out.println("<p class='err'>Aucune colonne du fichier ne correspond à ce formulaire. Vérifiez l'en-tête ou "
                    + "téléchargez le modèle CSV.</p>");
        }
        out.println("<p class='info'>Date de soumission : " + (hasSubmissionDateColumn
                ? "prise dans la colonne <strong>_SUBMISSION_DATE</strong> du fichier (ligne par ligne)."
                : "date du jour (le fichier ne contient pas de colonne _SUBMISSION_DATE).") + "</p>");
        out.println("</div>");

        int totalDataRows = rows.size() - 1;
        out.println("<h3>Aperçu (" + Math.min(20, totalDataRows) + " première(s) ligne(s) sur " + totalDataRows + ")</h3>");
        out.println("<div style='overflow-x:auto;'>");
        out.println("<table><thead><tr>");
        for (int i = 0; i < header.length; i++) {
            out.println("<th" + (columnForIndex[i] == null && !isMetaIndex[i] ? " class='err'" : "") + ">"
                    + htmlEscape(header[i]) + "</th>");
        }
        out.println("</tr></thead><tbody>");
        int shown = 0;
        for (int r = 1; r < rows.size() && shown < 20; r++) {
            String[] rowData = rows.get(r);
            boolean blank = true;
            for (String cell : rowData) {
                if (cell != null && !cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            shown++;
            out.println("<tr>");
            for (int i = 0; i < header.length; i++) {
                String v = i < rowData.length ? rowData[i] : "";
                out.println("<td>" + htmlEscape(v) + "</td>");
            }
            out.println("</tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");

        if (dataRowCount > 0 && !recognized.isEmpty()) {
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=csvConfirm'>");
            out.println("<input type='hidden' name='csvData' value='" + htmlEscape(encoded) + "'>");
            out.println("<div class='actions'>"
                    + "<button type='submit'>" + ICON_UPLOAD + " Confirmer l'import de " + dataRowCount + " soumission(s)</button> "
                    + "<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=csv'>Annuler</a>"
                    + "</div>");
            out.println("</form>");
        }
        writeFooter(out);
    }

    /** Étape 2 : appelée seulement après confirmation explicite ; décode le CSV validé à l'étape 1 et écrit les soumissions. */
    private void handleCsvConfirm(Connection conn, HttpServletRequest req, PrintWriter out, String formId) throws SQLException {
        String encoded = req.getParameter("csvData");
        String csvContent;
        if (encoded == null || encoded.trim().isEmpty()) {
            writeHeader(out, "Import CSV — " + formId);
            out.println("<p class='err'>Données CSV manquantes. Merci de recommencer l'import.</p>");
            out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                    + "&action=csv'>&laquo; Recommencer</a></p>");
            writeFooter(out);
            return;
        }
        try {
            csvContent = new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            writeHeader(out, "Import CSV — " + formId);
            out.println("<p class='err'>Données CSV invalides. Merci de recommencer l'import.</p>");
            out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                    + "&action=csv'>&laquo; Recommencer</a></p>");
            writeFooter(out);
            return;
        }
        insertCsvRows(conn, csvContent, out, formId);
    }

    /** Écrit réellement les soumissions décrites par csvContent (appelé uniquement après confirmation de l'aperçu). */
    private void insertCsvRows(Connection conn, String csvContent, PrintWriter out, String formId) throws SQLException {
        writeHeader(out, "Import CSV — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");

        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }

        java.util.Map<String, ColumnMeta> byColumnName = new java.util.HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byColumnName.put(col.columnName.toUpperCase(java.util.Locale.ROOT), col);
        }

        if (csvContent.length() > 0 && csvContent.charAt(0) == '﻿') {
            csvContent = csvContent.substring(1);
        }

        List<String[]> rows = parseCsv(csvContent);
        if (rows.isEmpty()) {
            out.println("<p class='err'>Fichier CSV vide.</p>");
            writeFooter(out);
            return;
        }

        String[] header = rows.get(0);
        ColumnMeta[] columnForIndex = new ColumnMeta[header.length];
        int submissionDateIndex = -1;
        List<String> unrecognized = new ArrayList<String>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].trim().toUpperCase(java.util.Locale.ROOT);
            if ("_SUBMISSION_DATE".equals(h)) {
                submissionDateIndex = i;
                continue;
            }
            if (isProtectedMetaColumn(h)) {
                continue;
            }
            ColumnMeta col = byColumnName.get(h);
            columnForIndex[i] = col;
            if (col == null && !h.isEmpty()) {
                unrecognized.add(header[i]);
            }
        }

        String currentUser = currentUserName();
        int success = 0;
        List<String> errors = new ArrayList<String>();
        for (int r = 1; r < rows.size(); r++) {
            String[] rowData = rows.get(r);
            boolean blank = true;
            for (String cell : rowData) {
                if (cell != null && !cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            final java.util.Map<String, String> valueMap = new java.util.HashMap<String, String>();
            for (int i = 0; i < columnForIndex.length && i < rowData.length; i++) {
                if (columnForIndex[i] != null) {
                    valueMap.put(columnForIndex[i].columnName, rowData[i]);
                }
            }
            // Date de soumission prise dans le fichier (colonne _SUBMISSION_DATE) si presente et
            // valide, plutot que d'utiliser systematiquement la date du jour - utile pour un import
            // qui doit preserver des dates de soumission d'origine (ex. reimport d'un export).
            Timestamp submissionDate = null;
            if (submissionDateIndex >= 0 && submissionDateIndex < rowData.length) {
                String raw = rowData[submissionDateIndex];
                if (raw != null && !raw.trim().isEmpty()) {
                    try {
                        submissionDate = parseTimestamp(raw.trim());
                    } catch (SQLException e) {
                        errors.add("Ligne " + (r + 1) + " : date de soumission invalide (" + raw.trim()
                                + "), soumission créée avec la date du jour.");
                    }
                }
            }
            try {
                String newUri = insertSubmissionRow(conn, coreTable, columns, currentUser, submissionDate, new ValueSource() {
                    public String get(String columnName) {
                        return valueMap.get(columnName);
                    }
                });
                recordHistory(conn, formId, newUri, HISTORY_IMPORT_CSV, null, null);
                success++;
            } catch (Exception e) {
                errors.add("Ligne " + (r + 1) + " : " + e.getMessage());
            }
        }
        if (success > 0) {
            ActivityLog.record(conn, "submission.import_csv", "formulaire", formId, success + " soumission(s) créée(s) via import CSV");
        }

        out.println("<div class='card'>");
        out.println("<p class='success'><strong>" + success + "</strong> soumission(s) créée(s) avec succès.</p>");
        if (!unrecognized.isEmpty()) {
            out.println("<p class='err'>Colonnes ignorées (non reconnues) : " + htmlEscape(String.join(", ", unrecognized)) + "</p>");
        }
        if (!errors.isEmpty()) {
            out.println("<p class='err'>" + errors.size() + " ligne(s) en erreur :</p><ul>");
            for (String err : errors) {
                out.println("<li>" + htmlEscape(err) + "</li>");
            }
            out.println("</ul>");
        }
        out.println("</div>");
        writeFooter(out);
    }

    // ---- Bulk CSV update (download, edit, re-import as an update instead of new rows) -----------

    /**
     * Vrai pour toute colonne technique ODK (_SUBMISSION_DATE, _LAST_UPDATE_DATE,
     * _LAST_UPDATE_URI_USER, _CREATOR_URI_USER, _CREATION_DATE, _IS_COMPLETE,
     * _MARKED_AS_COMPLETE_DATE, META_INSTANCE_ID...) - jamais des données du formulaire lui-même,
     * qui commencent toujours par le nom de l'élément, pas par un underscore. _URI est exclu ici
     * car géré séparément (il identifie la ligne à mettre à jour, cf. appelants). h doit déjà être
     * en majuscules/trim (voir la boucle d'analyse d'en-tête dans renderCsvUpdatePreview/applyCsvUpdateRows).
     */
    private static boolean isProtectedMetaColumn(String h) {
        return h.startsWith("_") || "META_INSTANCE_ID".equals(h);
    }

    private void renderCsvUpdateForm(Connection conn, PrintWriter out, String formId, String error) throws SQLException {
        writeHeader(out, "Mettre à jour des soumissions (CSV) — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p class='info'>1. Téléchargez d'abord les données actuelles avec « "
                + "<a href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAll'>Télécharger tout (CSV)</a> » "
                + "sur la liste des soumissions.<br>"
                + "2. Modifiez les valeurs à corriger dans le fichier, <strong>sans supprimer ni modifier la colonne _URI</strong> "
                + "(elle identifie quelle soumission mettre à jour).<br>"
                + "3. Importez le fichier modifié ci-dessous : seules les colonnes présentes dans le fichier seront changées, "
                + "les lignes dont le _URI ne correspond à aucune soumission seront ignorées et signalées.</p>");
        out.println("<p class='info'>Les colonnes techniques (comme <strong>_SUBMISSION_DATE</strong>, la date de "
                + "soumission) sont toujours ignorées, même présentes dans le fichier : une mise à jour ne les modifie "
                + "jamais.</p>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId)
                + "&action=csvUpdateUpload' enctype='multipart/form-data'>");
        out.println("<p><input type='file' name='csvFile' accept='.csv,text/csv' required></p>");
        out.println("<div class='actions'><button type='submit'>" + ICON_UPLOAD + " Analyser le fichier</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    /** Étape 1 : analyse le CSV (doit contenir _URI), montre combien de soumissions seraient mises à jour, et demande confirmation. */
    private void renderCsvUpdatePreview(Connection conn, HttpServletRequest req, PrintWriter out, String formId)
            throws SQLException {
        writeHeader(out, "Aperçu de la mise à jour CSV — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "&action=csvUpdate'>&laquo; Choisir un autre fichier</a></p>");

        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }
        Map<String, ColumnMeta> byColumnName = new HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byColumnName.put(col.columnName.toUpperCase(java.util.Locale.ROOT), col);
        }

        String csvContent = null;
        try {
            if (ServletFileUpload.isMultipartContent(req)) {
                ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
                List<FileItem> items = upload.parseRequest(req);
                for (FileItem item : items) {
                    if (!item.isFormField() && "csvFile".equals(item.getFieldName())) {
                        csvContent = item.getString("UTF-8");
                    }
                }
            }
        } catch (Exception e) {
            out.println("<p class='err'>Erreur de lecture du fichier : " + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }
        if (csvContent == null || csvContent.trim().isEmpty()) {
            out.println("<p class='err'>Aucun fichier CSV reçu.</p>");
            writeFooter(out);
            return;
        }
        if (csvContent.charAt(0) == '﻿') {
            csvContent = csvContent.substring(1);
        }

        List<String[]> rows = parseCsv(csvContent);
        if (rows.isEmpty()) {
            out.println("<p class='err'>Fichier CSV vide.</p>");
            writeFooter(out);
            return;
        }

        String[] header = rows.get(0);
        int uriIndex = -1;
        ColumnMeta[] columnForIndex = new ColumnMeta[header.length];
        List<String> recognized = new ArrayList<String>();
        List<String> unrecognized = new ArrayList<String>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].trim().toUpperCase(java.util.Locale.ROOT);
            if ("_URI".equals(h)) {
                uriIndex = i;
                continue;
            }
            if (isProtectedMetaColumn(h)) {
                // Colonne technique ODK (_SUBMISSION_DATE, _LAST_UPDATE_DATE, etc., presente si le
                // fichier vient d'un export) : jamais modifiable via une mise a jour CSV, toujours
                // ignoree silencieusement (ni recensee, ni signalee comme "non reconnue").
                continue;
            }
            ColumnMeta col = byColumnName.get(h);
            columnForIndex[i] = col;
            if (col != null) {
                recognized.add(header[i]);
            } else if (!h.isEmpty()) {
                unrecognized.add(header[i]);
            }
        }

        if (uriIndex < 0) {
            out.println("<div class='card'><p class='err'>Colonne _URI manquante : ce fichier doit provenir d'un export "
                    + "(« Télécharger tout (CSV) ») pour identifier quelles soumissions mettre à jour.</p></div>");
            writeFooter(out);
            return;
        }

        Set<String> existingUris = new HashSet<String>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT \"_URI\" FROM odk_prod.\"" + coreTable + "\" WHERE "
                + FormSchemaUtil.CORE_NOT_DELETED);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                existingUris.add(rs.getString(1));
            }
        }

        int willUpdate = 0;
        int notFound = 0;
        int missingUri = 0;
        for (int r = 1; r < rows.size(); r++) {
            String[] rowData = rows.get(r);
            boolean blank = true;
            for (String cell : rowData) {
                if (cell != null && !cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            String uri = uriIndex < rowData.length ? rowData[uriIndex] : null;
            if (uri == null || uri.trim().isEmpty()) {
                missingUri++;
            } else if (existingUris.contains(uri.trim())) {
                willUpdate++;
            } else {
                notFound++;
            }
        }

        out.println("<div class='card'>");
        out.println("<p class='info'><strong>" + willUpdate + "</strong> soumission(s) seront mises à jour"
                + (notFound > 0 ? ", <strong>" + notFound + "</strong> _URI introuvable(s) (ignorée(s))" : "")
                + (missingUri > 0 ? ", <strong>" + missingUri + "</strong> ligne(s) sans _URI (ignorée(s))" : "") + ".</p>");
        if (!recognized.isEmpty()) {
            out.println("<p class='success'>Colonnes qui seront modifiées : " + htmlEscape(String.join(", ", recognized)) + "</p>");
        }
        if (!unrecognized.isEmpty()) {
            out.println("<p class='err'>Colonnes ignorées (non reconnues par ce formulaire) : "
                    + htmlEscape(String.join(", ", unrecognized)) + "</p>");
        }
        if (recognized.isEmpty()) {
            out.println("<p class='err'>Aucune colonne du fichier (en dehors de _URI) ne correspond à ce formulaire : "
                    + "rien ne serait modifié.</p>");
        }
        out.println("</div>");

        int totalDataRows = rows.size() - 1;
        out.println("<h3>Aperçu (" + Math.min(20, totalDataRows) + " première(s) ligne(s) sur " + totalDataRows + ")</h3>");
        out.println("<div style='overflow-x:auto;'>");
        out.println("<table><thead><tr><th>Statut</th>");
        for (int i = 0; i < header.length; i++) {
            boolean isMeta = i == uriIndex;
            out.println("<th" + (!isMeta && columnForIndex[i] == null ? " class='err'" : "") + ">" + htmlEscape(header[i])
                    + "</th>");
        }
        out.println("</tr></thead><tbody>");
        int shown = 0;
        for (int r = 1; r < rows.size() && shown < 20; r++) {
            String[] rowData = rows.get(r);
            boolean blank = true;
            for (String cell : rowData) {
                if (cell != null && !cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            shown++;
            String uri = uriIndex < rowData.length ? rowData[uriIndex] : null;
            String status = (uri == null || uri.trim().isEmpty()) ? "<span class='badge muted'>_URI manquant</span>"
                    : existingUris.contains(uri.trim()) ? "<span class='badge success'>Sera mise à jour</span>"
                    : "<span class='badge muted'>_URI introuvable</span>";
            out.println("<tr><td>" + status + "</td>");
            for (int i = 0; i < header.length; i++) {
                String v = i < rowData.length ? rowData[i] : "";
                out.println("<td>" + htmlEscape(v) + "</td>");
            }
            out.println("</tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");

        if (willUpdate > 0 && !recognized.isEmpty()) {
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=csvUpdateConfirm'>");
            out.println("<input type='hidden' name='csvData' value='" + htmlEscape(encoded) + "'>");
            out.println("<div class='actions'>"
                    + "<button type='submit'>" + ICON_UPLOAD + " Confirmer la mise à jour de " + willUpdate + " soumission(s)</button> "
                    + "<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=csvUpdate'>Annuler</a>"
                    + "</div>");
            out.println("</form>");
        }
        writeFooter(out);
    }

    /** Étape 2 : appelée seulement après confirmation explicite ; décode le CSV validé à l'étape 1 et applique les mises à jour. */
    private void handleCsvUpdateConfirm(Connection conn, HttpServletRequest req, PrintWriter out, String formId) throws SQLException {
        String encoded = req.getParameter("csvData");
        String csvContent;
        if (encoded == null || encoded.trim().isEmpty()) {
            writeHeader(out, "Mise à jour CSV — " + formId);
            out.println("<p class='err'>Données CSV manquantes. Merci de recommencer.</p>");
            out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                    + "&action=csvUpdate'>&laquo; Recommencer</a></p>");
            writeFooter(out);
            return;
        }
        try {
            csvContent = new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            writeHeader(out, "Mise à jour CSV — " + formId);
            out.println("<p class='err'>Données CSV invalides. Merci de recommencer.</p>");
            out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                    + "&action=csvUpdate'>&laquo; Recommencer</a></p>");
            writeFooter(out);
            return;
        }
        applyCsvUpdateRows(conn, csvContent, out, formId);
    }

    /** Écrit réellement les mises à jour décrites par csvContent (appelé uniquement après confirmation de l'aperçu). */
    private void applyCsvUpdateRows(Connection conn, String csvContent, PrintWriter out, String formId) throws SQLException {
        writeHeader(out, "Mise à jour CSV — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");

        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }
        Map<String, ColumnMeta> byColumnName = new HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byColumnName.put(col.columnName.toUpperCase(java.util.Locale.ROOT), col);
        }

        if (csvContent.length() > 0 && csvContent.charAt(0) == '﻿') {
            csvContent = csvContent.substring(1);
        }
        List<String[]> rows = parseCsv(csvContent);
        if (rows.isEmpty()) {
            out.println("<p class='err'>Fichier CSV vide.</p>");
            writeFooter(out);
            return;
        }

        String[] header = rows.get(0);
        int uriIndex = -1;
        ColumnMeta[] columnForIndex = new ColumnMeta[header.length];
        List<String> unrecognized = new ArrayList<String>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].trim().toUpperCase(java.util.Locale.ROOT);
            if ("_URI".equals(h)) {
                uriIndex = i;
                continue;
            }
            if (isProtectedMetaColumn(h)) {
                continue;
            }
            ColumnMeta col = byColumnName.get(h);
            columnForIndex[i] = col;
            if (col == null && !h.isEmpty()) {
                unrecognized.add(header[i]);
            }
        }
        if (uriIndex < 0) {
            out.println("<p class='err'>Colonne _URI manquante : impossible d'identifier les soumissions à mettre à jour.</p>");
            writeFooter(out);
            return;
        }

        List<ColumnMeta> updateColumns = new ArrayList<ColumnMeta>();
        List<Integer> updateIndexes = new ArrayList<Integer>();
        for (int i = 0; i < header.length; i++) {
            if (columnForIndex[i] != null) {
                updateColumns.add(columnForIndex[i]);
                updateIndexes.add(i);
            }
        }
        if (updateColumns.isEmpty()) {
            out.println("<p class='err'>Aucune colonne du fichier (en dehors de _URI) ne correspond à ce formulaire.</p>");
            writeFooter(out);
            return;
        }

        int success = 0;
        List<String> errors = new ArrayList<String>();
        for (int r = 1; r < rows.size(); r++) {
            String[] rowData = rows.get(r);
            boolean blank = true;
            for (String cell : rowData) {
                if (cell != null && !cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            String uri = uriIndex < rowData.length ? rowData[uriIndex] : null;
            if (uri == null || uri.trim().isEmpty()) {
                errors.add("Ligne " + (r + 1) + " : _URI manquant.");
                continue;
            }
            final Map<String, String> rowValues = new HashMap<String, String>();
            for (int k = 0; k < updateColumns.size(); k++) {
                int csvIdx = updateIndexes.get(k);
                rowValues.put(updateColumns.get(k).columnName, csvIdx < rowData.length ? rowData[csvIdx] : null);
            }
            try {
                int updated = updateWithHistory(conn, formId, coreTable, columns, updateColumns, uri.trim(),
                        new ValueSource() {
                            public String get(String columnName) {
                                return rowValues.get(columnName);
                            }
                        }, HISTORY_UPDATE_CSV, null);
                if (updated == 0) {
                    errors.add("Ligne " + (r + 1) + " : _URI introuvable ou dans la corbeille (" + uri.trim() + ").");
                } else {
                    success++;
                }
            } catch (SQLException e) {
                errors.add("Ligne " + (r + 1) + " : " + e.getMessage());
            }
        }
        if (success > 0) {
            ActivityLog.record(conn, "submission.update_csv", "formulaire", formId, success + " soumission(s) mise(s) à jour via CSV");
        }

        out.println("<div class='card'>");
        out.println("<p class='success'><strong>" + success + "</strong> soumission(s) mise(s) à jour.</p>");
        if (!unrecognized.isEmpty()) {
            out.println("<p class='err'>Colonnes ignorées (non reconnues) : " + htmlEscape(String.join(", ", unrecognized)) + "</p>");
        }
        if (!errors.isEmpty()) {
            out.println("<p class='err'>" + errors.size() + " ligne(s) en erreur :</p><ul>");
            for (String err : errors) {
                out.println("<li>" + htmlEscape(err) + "</li>");
            }
            out.println("</ul>");
        }
        out.println("</div>");
        writeFooter(out);
    }

    // ---- CSV export (full form or manual selection) --------------------------------------------

    // Version des données exportées : nettoyées (brut + corrections, par défaut), brutes (telles
    // que reçues du terrain), ou les deux côte à côte (colonne + colonne "__BRUT").
    private static final String EXPORT_CLEAN = "nettoye";
    private static final String EXPORT_RAW = "brut";
    private static final String EXPORT_BOTH = "deux";
    private static final String RAW_SUFFIX = "__BRUT";

    private static String exportVersion(HttpServletRequest req) {
        String v = req.getParameter("version");
        return EXPORT_RAW.equals(v) || EXPORT_BOTH.equals(v) ? v : EXPORT_CLEAN;
    }

    private static String exportFileName(String formId, String version, String extension) {
        return formId.replaceAll("[^A-Za-z0-9_-]", "_")
                + (EXPORT_RAW.equals(version) ? "_brut" : EXPORT_BOTH.equals(version) ? "_brut_et_nettoye" : "")
                + "." + extension;
    }

    /** En-têtes communs à tous les formats. En version "deux" : nb de corrections, puis champ + champ__BRUT. */
    private static List<String> exportHeaders(List<ColumnMeta> columns, String version) {
        List<String> headers = new ArrayList<String>();
        headers.add("_URI");
        headers.add("_SUBMISSION_DATE");
        // Commence par "_" : ignorée à la réimport CSV (isProtectedMetaColumn), comme les autres métadonnées.
        headers.add("_STATUT");
        if (EXPORT_BOTH.equals(version)) {
            headers.add("_NB_CORRECTIONS");
        }
        for (ColumnMeta col : columns) {
            headers.add(col.columnName);
            if (EXPORT_BOTH.equals(version)) {
                headers.add(col.columnName + RAW_SUFFIX);
            }
        }
        return headers;
    }

    /** Une ligne d'export, alignée sur exportHeaders. */
    private static Object[] exportRow(ResultSet rs, List<ColumnMeta> columns, Map<String, String> corrections, String version,
            String status) throws SQLException {
        List<Object> row = new ArrayList<Object>();
        row.add(rs.getString("_URI"));
        Object subDate = rs.getObject("_SUBMISSION_DATE");
        row.add(subDate == null ? null : subDate.toString());
        row.add(statusLabel(status));
        if (EXPORT_BOTH.equals(version)) {
            row.add(Integer.valueOf(corrections.size()));
        }
        for (ColumnMeta col : columns) {
            row.add(fieldValue(rs, col, corrections, !EXPORT_RAW.equals(version)));
            if (EXPORT_BOTH.equals(version)) {
                row.add(rs.getObject(col.columnName));
            }
        }
        return row.toArray();
    }

    private void streamCsvExport(Connection conn, HttpServletResponse resp, String formId, String[] selectedUris,
            String version, String statusFilter) throws SQLException, IOException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        Map<String, Map<String, String>> allCorrections = loadCorrections(conn, formId, null);
        Map<String, SubmissionStatus> allStatuses = loadStatuses(conn, formId, null);

        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + exportFileName(formId, version, "csv") + "\"");
        PrintWriter out = resp.getWriter();
        out.print('﻿');

        StringBuilder header = new StringBuilder();
        for (String h : exportHeaders(columns, version)) {
            header.append(header.length() == 0 ? "" : ",").append(csvEscape(h));
        }
        out.println(header.toString());

        String sql = selectionSql(coreTable, selectedUris, statusFilter);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindSelection(ps, selectedUris);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder line = new StringBuilder();
                    Object[] row = exportRow(rs, columns, correctionsOf(allCorrections, rs.getString("_URI")), version,
                            statusOf(allStatuses, rs.getString("_URI")));
                    for (int i = 0; i < row.length; i++) {
                        line.append(i == 0 ? "" : ",").append(csvEscape(row[i] == null ? "" : row[i].toString()));
                    }
                    out.println(line.toString());
                }
            }
        }
        out.flush();
    }

    // ---- Export JSON, KML et Excel (mêmes filtres/sélection que le CSV ci-dessus) ----------------

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Un objet JSON valide par valeur : nombres/booléens non guillemetés, tout le reste en texte échappé, null si absent. */
    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Double || value instanceof Float) {
            return value.toString();
        }
        return "\"" + jsonEscape(value.toString()) + "\"";
    }

    private void streamJsonExport(Connection conn, HttpServletResponse resp, String formId, String[] selectedUris,
            String version, String statusFilter) throws SQLException, IOException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        Map<String, Map<String, String>> allCorrections = loadCorrections(conn, formId, null);
        Map<String, SubmissionStatus> allStatuses = loadStatuses(conn, formId, null);
        List<String> headers = exportHeaders(columns, version);

        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + exportFileName(formId, version, "json") + "\"");
        PrintWriter out = resp.getWriter();

        String sql = selectionSql(coreTable, selectedUris, statusFilter);
        out.print('[');
        boolean first = true;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindSelection(ps, selectedUris);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) {
                        out.print(',');
                    }
                    first = false;
                    Object[] row = exportRow(rs, columns, correctionsOf(allCorrections, rs.getString("_URI")), version,
                            statusOf(allStatuses, rs.getString("_URI")));
                    out.print('{');
                    for (int i = 0; i < row.length; i++) {
                        out.print((i == 0 ? "" : ",") + "\"" + jsonEscape(headers.get(i)) + "\":" + jsonValue(row[i]));
                    }
                    out.print('}');
                }
            }
        }
        out.print(']');
        out.flush();
    }

    private void streamXlsxExport(Connection conn, HttpServletResponse resp, String formId, String[] selectedUris,
            String version, String statusFilter) throws SQLException, IOException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        Map<String, Map<String, String>> allCorrections = loadCorrections(conn, formId, null);
        Map<String, SubmissionStatus> allStatuses = loadStatuses(conn, formId, null);
        List<String> headers = exportHeaders(columns, version);

        List<Object[]> rows = new ArrayList<Object[]>();
        String sql = selectionSql(coreTable, selectedUris, statusFilter);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindSelection(ps, selectedUris);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(exportRow(rs, columns, correctionsOf(allCorrections, rs.getString("_URI")), version,
                            statusOf(allStatuses, rs.getString("_URI"))));
                }
            }
        }

        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + exportFileName(formId, version, "xlsx") + "\"");
        XlsxWriter.write(resp.getOutputStream(), formId, headers, rows);
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** KML (Google Earth) : un Placemark par soumission géolocalisée, avec les autres champs en ExtendedData. */
    private void streamKmlExport(Connection conn, HttpServletResponse resp, String formId, String version,
            String statusFilter)
            throws SQLException, IOException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        List<GeoField> geoFields = findGeoFields(columns);
        if (geoFields.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Ce formulaire ne contient aucun champ de géolocalisation.");
            return;
        }
        GeoField geo = geoFields.get(0);
        Map<String, ColumnMeta> byName = new HashMap<String, ColumnMeta>();
        for (ColumnMeta col : columns) {
            byName.put(col.columnName, col);
        }
        // Le KML n'a qu'une position par point : "deux" utilise la position nettoyée + les champs bruts en plus.
        boolean cleaned = !EXPORT_RAW.equals(version);
        Map<String, Map<String, String>> allCorrections = loadCorrections(conn, formId, null);
        Map<String, SubmissionStatus> allStatuses = loadStatuses(conn, formId, null);

        resp.setContentType("application/vnd.google-earth.kml+xml;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + exportFileName(formId, version, "kml") + "\"");
        PrintWriter out = resp.getWriter();
        out.println("<?xml version='1.0' encoding='UTF-8'?>");
        out.println("<kml xmlns='http://www.opengis.net/kml/2.2'><Document>");
        out.println("<name>" + htmlEscape(formId) + "</name>");

        // Pas de filtre "IS NOT NULL" en SQL : une correction peut rendre valide une position brute vide.
        String sql = "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE " + FormSchemaUtil.CORE_NOT_DELETED
                + statusFilterSql(statusFilter);
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String rowUri = rs.getString("_URI");
                Map<String, String> corrections = correctionsOf(allCorrections, rowUri);
                BigDecimal lat = toBigDecimal(fieldValue(rs, byName.get(geo.latColumn), corrections, cleaned));
                BigDecimal lng = toBigDecimal(fieldValue(rs, byName.get(geo.lngColumn), corrections, cleaned));
                if (lat == null || lng == null) {
                    continue;
                }
                Object subDate = rs.getObject("_SUBMISSION_DATE");
                out.println("<Placemark>");
                out.println("<name>" + htmlEscape(rowUri) + "</name>");
                if (subDate != null) {
                    out.println("<description>" + htmlEscape(subDate.toString()) + "</description>");
                }
                out.println("<ExtendedData>");
                out.println("<Data name='_STATUT'><value>" + htmlEscape(statusLabel(statusOf(allStatuses, rowUri)))
                        + "</value></Data>");
                for (ColumnMeta col : columns) {
                    Object v = fieldValue(rs, col, corrections, cleaned);
                    if (v != null) {
                        out.println("<Data name='" + htmlEscape(col.columnName) + "'><value>"
                                + htmlEscape(v.toString()) + "</value></Data>");
                    }
                    if (EXPORT_BOTH.equals(version) && corrections.containsKey(col.columnName)) {
                        Object rawV = rs.getObject(col.columnName);
                        out.println("<Data name='" + htmlEscape(col.columnName + RAW_SUFFIX) + "'><value>"
                                + htmlEscape(rawV == null ? "" : rawV.toString()) + "</value></Data>");
                    }
                }
                out.println("</ExtendedData>");
                out.println("<Point><coordinates>" + lng.toPlainString() + "," + lat.toPlainString() + "</coordinates></Point>");
                out.println("</Placemark>");
            }
        }
        out.println("</Document></kml>");
    }

    /** Filtre de statut des exports « Télécharger tout » (paramètre exportStatut), ou null = toutes. */
    private static String exportStatusFilter(HttpServletRequest req) {
        return normalizeStatusFilter(req.getParameter("exportStatut"));
    }

    /**
     * statusFilter ne s'applique qu'à l'export complet : une sélection manuelle est exportée telle
     * que l'utilisateur l'a choisie, quel que soit le statut.
     */
    private static String selectionSql(String coreTable, String[] selectedUris, String statusFilter) {
        boolean hasSelection = selectedUris != null && selectedUris.length > 0;
        if (hasSelection) {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < selectedUris.length; i++) {
                if (i > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
            }
            return "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" IN (" + placeholders + ") "
                    + "AND " + FormSchemaUtil.CORE_NOT_DELETED + " ORDER BY \"_SUBMISSION_DATE\" DESC NULLS LAST";
        }
        return "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE " + FormSchemaUtil.CORE_NOT_DELETED
                + statusFilterSql(statusFilter) + " ORDER BY \"_SUBMISSION_DATE\" DESC NULLS LAST";
    }

    private static void bindSelection(PreparedStatement ps, String[] selectedUris) throws SQLException {
        if (selectedUris != null) {
            for (int i = 0; i < selectedUris.length; i++) {
                ps.setString(i + 1, selectedUris[i]);
            }
        }
    }

    // ---- Map view (Leaflet / OpenStreetMap, no API key required) -------------------------------

    private void renderMap(Connection conn, PrintWriter out, String formId) throws SQLException {
        writeHeader(out, "Carte — " + formId);
        out.println("<p class='back-link'><a href='editSubmission?formId=" + urlEncode(formId)
                + "'>&laquo; Liste des soumissions</a></p>");

        String coreTable;
        List<ColumnMeta> columns;
        try {
            coreTable = resolveCoreTableOrThrow(conn, formId);
            columns = listEditableColumns(conn, coreTable);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }

        List<GeoField> geoFields = findGeoFields(columns);
        if (geoFields.isEmpty()) {
            out.println("<p class='err'>Ce formulaire ne contient aucun champ de géolocalisation.</p>");
            writeFooter(out);
            return;
        }
        GeoField geo = geoFields.get(0);

        List<String> markers = new ArrayList<String>();
        ColumnMeta latCol = null;
        ColumnMeta lngCol = null;
        for (ColumnMeta col : columns) {
            if (col.columnName.equals(geo.latColumn)) {
                latCol = col;
            } else if (col.columnName.equals(geo.lngColumn)) {
                lngCol = col;
            }
        }
        // Carte = positions nettoyées (une correction de coordonnées déplace le point).
        Map<String, Map<String, String>> allCorrections = loadCorrections(conn, formId, null);
        String sql = "SELECT \"_URI\", \"" + geo.latColumn + "\", \"" + geo.lngColumn + "\" FROM odk_prod.\"" + coreTable + "\" "
                + "WHERE " + FormSchemaUtil.CORE_NOT_DELETED;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String rowUri = rs.getString(1);
                Map<String, String> corrections = correctionsOf(allCorrections, rowUri);
                BigDecimal lat = toBigDecimal(fieldValue(rs, latCol, corrections, true));
                BigDecimal lng = toBigDecimal(fieldValue(rs, lngCol, corrections, true));
                if (lat == null || lng == null) {
                    continue;
                }
                String editUrl = "editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(rowUri);
                markers.add("[" + lat.toPlainString() + "," + lng.toPlainString() + ",\""
                        + rowUri.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\""
                        + editUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
            }
        }

        out.println("<p class='info'>" + markers.size() + " soumission(s) géolocalisée(s) — champ : " + htmlEscape(geo.label) + "</p>");
        out.println("<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>");
        out.println("<div id='map' style='height:70vh;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);'></div>");
        out.println("<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>");
        out.println("<script>");
        out.println("var markers = [" + String.join(",", markers) + "];");
        out.println("var map = L.map('map');");
        out.println("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', "
                + "{maxZoom:19, attribution:'&copy; OpenStreetMap contributors'}).addTo(map);");
        out.println("var group = [];");
        out.println("markers.forEach(function(m) { "
                + "var mk = L.marker([m[0], m[1]]).addTo(map).bindPopup('<a href=\"' + m[3] + '\">Modifier ' + m[2] + '</a>'); "
                + "group.push(mk); });");
        out.println("if (group.length > 0) { map.fitBounds(L.featureGroup(group).getBounds().pad(0.2)); } "
                + "else { map.setView([12.3714, -1.5197], 6); }");
        out.println("</script>");
        writeFooter(out);
    }

    private static Timestamp parseTimestamp(String value) throws SQLException {
        String v = value.trim();
        // Format JDBC "yyyy-MM-dd HH:mm:ss[.fffffffff]" - celui produit par Timestamp.toString()
        // et donc utilisé par l'export CSV (streamCsvExport) : nécessaire pour que le cycle
        // export -> modification -> réimport (mise à jour en masse) fonctionne sans y retoucher.
        try {
            return Timestamp.valueOf(v);
        } catch (IllegalArgumentException ignored) {
        }
        // Format ISO "yyyy-MM-dd'T'HH:mm[:ss]" - celui envoyé par <input type='datetime-local'>
        // du formulaire d'édition d'une soumission.
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"};
        for (String pattern : patterns) {
            try {
                return new Timestamp(new SimpleDateFormat(pattern).parse(v).getTime());
            } catch (ParseException ignored) {
            }
        }
        throw new SQLException("Date/heure invalide : " + value);
    }
}
