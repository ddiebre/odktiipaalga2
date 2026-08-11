package odk.editsubmission;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.FormSchemaUtil;

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

    private static void checkIdentifier(String identifier) throws SQLException {
        FormSchemaUtil.checkIdentifier(identifier);
    }

    private static String resolveSubmissionDataModelUri(Connection conn, String formId) throws SQLException {
        return FormSchemaUtil.resolveSubmissionDataModelUri(conn, formId);
    }

    private static String resolveCoreTable(Connection conn, String submissionDataModelUri) throws SQLException {
        return FormSchemaUtil.resolveCoreTable(conn, submissionDataModelUri);
    }

    private static List<ColumnMeta> listEditableColumns(Connection conn, String coreTable) throws SQLException {
        checkIdentifier(coreTable);
        String sql = "SELECT dm.\"ELEMENT_NAME\", dm.\"PERSIST_AS_COLUMN_NAME\", c.data_type "
                + "FROM odk_prod.\"_form_data_model\" dm "
                + "JOIN information_schema.columns c "
                + "  ON c.table_schema = 'odk_prod' AND c.table_name = dm.\"PERSIST_AS_TABLE_NAME\" "
                + "  AND c.column_name = dm.\"PERSIST_AS_COLUMN_NAME\" "
                + "WHERE dm.\"PERSIST_AS_TABLE_NAME\" = ? "
                + "  AND dm.\"PERSIST_AS_COLUMN_NAME\" IS NOT NULL "
                + "  AND dm.\"ELEMENT_TYPE\" NOT IN ('GROUP','REPEAT','BINARY') "
                + "ORDER BY dm.\"ORDINAL_NUMBER\"";
        List<ColumnMeta> columns = new ArrayList<ColumnMeta>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, coreTable);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString(2);
                    checkIdentifier(columnName);
                    columns.add(new ColumnMeta(rs.getString(1), columnName, rs.getString(3)));
                }
            }
        }
        return columns;
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

    private static final String PAGE_CSS =
            "body{margin:0;background:#f4f6f9;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1f2430;}"
            + "*{box-sizing:border-box;}"
            + ".topbar{background:#1e3a5f;color:#fff;padding:14px 24px;font-size:16px;font-weight:600;letter-spacing:.2px;}"
            + ".topbar-link{color:#fff;text-decoration:none;opacity:.85;}"
            + ".topbar-link:hover{opacity:1;text-decoration:underline;}"
            + ".container{max-width:960px;margin:24px auto;padding:0 16px 48px;}"
            + "h2{font-size:20px;font-weight:600;margin:0 0 16px;color:#1e3a5f;}"
            + ".card{background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);padding:20px 24px;margin-bottom:16px;}"
            + "a{color:#2563eb;text-decoration:none;}"
            + "a:hover{text-decoration:underline;}"
            + ".back-link{display:inline-block;margin-bottom:14px;font-size:13px;}"
            + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08);}"
            + "th,td{text-align:left;padding:10px 14px;border-bottom:1px solid #eef0f3;font-size:14px;}"
            + "th{background:#f0f3f8;font-weight:600;color:#374151;}"
            + "tr:last-child td{border-bottom:none;}"
            + "tr:hover td{background:#f8fafc;}"
            + "ul.list{list-style:none;padding:0;margin:0;background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);overflow:hidden;}"
            + "ul.list li{border-bottom:1px solid #eef0f3;}"
            + "ul.list li:last-child{border-bottom:none;}"
            + "ul.list li a{display:block;padding:12px 16px;}"
            + "ul.list li a:hover{background:#f8fafc;text-decoration:none;}"
            + ".form-grid{display:grid;grid-template-columns:220px 1fr;gap:12px 16px;align-items:center;}"
            + "label{font-weight:600;font-size:13px;color:#374151;}"
            + "input[type=text],input[type=number],input[type=datetime-local]{width:100%;max-width:360px;padding:7px 10px;"
            + "border:1px solid #d1d5db;border-radius:6px;font-size:14px;font-family:inherit;}"
            + "input:focus{outline:none;border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,.15);}"
            + "input[type=checkbox]{width:18px;height:18px;}"
            + "button{background:#2563eb;color:#fff;border:none;padding:9px 22px;border-radius:6px;font-size:14px;"
            + "font-weight:600;cursor:pointer;}"
            + "button:hover{background:#1d4ed8;}"
            + ".err{background:#fef2f2;color:#b91c1c;border:1px solid #fecaca;padding:10px 14px;border-radius:6px;"
            + "margin-bottom:14px;font-size:14px;}"
            + ".info{color:#6b7280;font-size:13px;margin-bottom:14px;}"
            + ".pagination{margin-top:14px;font-size:14px;}"
            + ".pagination a{margin-right:12px;}"
            + ".btn-link{display:inline-block;padding:5px 12px;background:#eef2ff;color:#2563eb;border-radius:5px;font-size:13px;}"
            + ".btn-link:hover{background:#e0e7ff;text-decoration:none;}"
            + ".actions{margin-top:18px;}"
            + ".toolbar{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px;}"
            + ".success{background:#f0fdf4;color:#15803d;border:1px solid #bbf7d0;padding:10px 14px;border-radius:6px;"
            + "margin-bottom:14px;font-size:14px;}"
            + "input[type=file]{font-size:14px;}";

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<style>" + PAGE_CSS + "</style>");
        out.println("</head><body>");
        out.println("<div class='topbar'><a href='home' class='topbar-link'>&laquo; Menu</a> &middot; ODK Tiipaalga &middot; Modifier une soumission</div>");
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
            if (formId != null && "exportAll".equals(action)) {
                streamCsvExport(conn, resp, formId, null);
                return;
            }
            if (formId != null && "csvTemplate".equals(action)) {
                streamCsvTemplate(conn, resp, formId);
                return;
            }
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            if (formId == null) {
                renderFormList(conn, out);
            } else if ("new".equals(action)) {
                renderCreateForm(conn, out, formId, null);
            } else if ("csv".equals(action)) {
                renderCsvUploadForm(conn, out, formId, null);
            } else if ("map".equals(action)) {
                renderMap(conn, out, formId);
            } else if (uri == null) {
                int offset = 0;
                try {
                    offset = Integer.parseInt(req.getParameter("offset"));
                } catch (NumberFormatException ignored) {
                }
                renderSubmissionList(conn, out, formId, offset);
            } else {
                renderEditForm(conn, out, formId, uri, null, "1".equals(req.getParameter("created")));
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
            if ("exportSelected".equals(action)) {
                streamCsvExport(conn, resp, formId, req.getParameterValues("uri"));
                return;
            }
            if ("csvUpload".equals(action)) {
                resp.setContentType("text/html;charset=UTF-8");
                renderCsvUploadResult(conn, req, resp.getWriter(), formId);
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
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(newUri) + "&created=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderCreateForm(conn, resp.getWriter(), formId, error);
                return;
            }
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            String error = null;
            try {
                saveSubmission(conn, req, formId, uri);
            } catch (Exception e) {
                error = e.getMessage();
            }
            renderEditForm(conn, out, formId, uri, error, false);
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private void renderFormList(Connection conn, PrintWriter out) throws SQLException {
        writeHeader(out, "Choisir un formulaire");
        out.println("<p class='info'>Sélectionnez le formulaire dont vous voulez modifier une soumission.</p>");
        out.println("<ul class='list'>");
        String sql = "SELECT \"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_info_submission_association\" "
                + "WHERE \"IS_SUBMISSION_ALLOWED\" = true AND \"URI_SUBMISSION_DATA_MODEL\" IS NOT NULL "
                + "ORDER BY \"SUBMISSION_FORM_ID\"";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String formId = rs.getString(1);
                out.println("<li><a href='editSubmission?formId=" + urlEncode(formId) + "'>"
                        + htmlEscape(formId) + "</a></li>");
            }
        }
        out.println("</ul>");
        writeFooter(out);
    }

    private void renderSubmissionList(Connection conn, PrintWriter out, String formId, int offset) throws SQLException {
        writeHeader(out, "Soumissions — " + formId);
        out.println("<p class='back-link'><a href='editSubmission'>&laquo; Formulaires</a></p>");

        String modelUri = resolveSubmissionDataModelUri(conn, formId);
        String coreTable = modelUri == null ? null : resolveCoreTable(conn, modelUri);
        if (coreTable == null) {
            out.println("<p class='err'>Formulaire introuvable : " + htmlEscape(formId) + "</p>");
            writeFooter(out);
            return;
        }

        out.println("<div class='toolbar'>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=new'>+ Nouvelle soumission</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=csv'>Importer CSV</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=map'>Carte</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAll'>Télécharger tout (CSV)</a>");
        out.println("</div>");

        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=exportSelected'>");
        out.println("<table><tr><th><input type='checkbox' id='selAll'></th><th>_URI</th><th>Date de soumission</th><th></th></tr>");
        String sql = "SELECT \"_URI\", \"_SUBMISSION_DATE\" FROM odk_prod.\"" + coreTable + "\" "
                + "ORDER BY \"_SUBMISSION_DATE\" DESC NULLS LAST LIMIT ? OFFSET ?";
        int rowCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, PAGE_SIZE);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowCount++;
                    String rowUri = rs.getString(1);
                    Object subDate = rs.getObject(2);
                    out.println("<tr><td><input type='checkbox' name='uri' class='sel-cb' value='" + htmlEscape(rowUri)
                            + "'></td><td>" + htmlEscape(rowUri) + "</td><td>"
                            + htmlEscape(subDate == null ? "" : subDate.toString()) + "</td><td>"
                            + "<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(rowUri)
                            + "'>Modifier</a></td></tr>");
                }
            }
        }
        out.println("</table>");
        if (rowCount > 0) {
            out.println("<div class='actions'><button type='submit'>Télécharger la sélection (CSV)</button></div>");
        }
        out.println("</form>");
        out.println("<script>document.getElementById('selAll').addEventListener('change', function() {"
                + "var boxes = document.getElementsByClassName('sel-cb');"
                + "for (var i = 0; i < boxes.length; i++) { boxes[i].checked = this.checked; }"
                + "});</script>");
        out.println("<p class='pagination'>");
        if (offset > 0) {
            out.println("<a href='editSubmission?formId=" + urlEncode(formId) + "&offset=" + Math.max(0, offset - PAGE_SIZE)
                    + "'>&laquo; Précédent</a>");
        }
        out.println("<a href='editSubmission?formId=" + urlEncode(formId) + "&offset=" + (offset + PAGE_SIZE)
                + "'>Suivant &raquo;</a></p>");
        writeFooter(out);
    }

    private void renderEditForm(Connection conn, PrintWriter out, String formId, String uri, String error, boolean created)
            throws SQLException {
        writeHeader(out, "Modifier la soumission");
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

        java.util.Map<String, Object> currentValues = new java.util.HashMap<String, Object>();
        String sql = "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uri);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (ColumnMeta col : columns) {
                        currentValues.put(col.columnName, rs.getObject(col.columnName));
                    }
                } else {
                    out.println("<p class='err'>Soumission introuvable : " + htmlEscape(uri) + "</p>");
                    writeFooter(out);
                    return;
                }
            }
        }

        if (error != null) {
            out.println("<p class='err'>Erreur : " + htmlEscape(error) + "</p>");
        } else if (created) {
            out.println("<p class='success'>Soumission créée avec succès.</p>");
        }
        out.println("<p class='info'>_URI : " + htmlEscape(uri) + "</p>");

        out.println("<div class='card'>");
        out.println("<form method='post' action='editSubmission'>");
        out.println("<input type='hidden' name='formId' value='" + htmlEscape(formId) + "'>");
        out.println("<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>");
        out.println("<div class='form-grid'>");
        for (ColumnMeta col : columns) {
            Object value = currentValues.get(col.columnName);
            String inputType = inputTypeFor(col.sqlType);
            out.println("<label>" + htmlEscape(col.elementName) + "</label>");
            if ("checkbox".equals(inputType)) {
                boolean checked = Boolean.TRUE.equals(value);
                out.println("<input type='checkbox' name='" + htmlEscape(col.columnName) + "' value='true'"
                        + (checked ? " checked" : "") + ">");
            } else {
                String textValue = value == null ? "" : formatValueForInput(value, inputType);
                out.println("<input type='" + inputType + "' name='" + htmlEscape(col.columnName) + "' value='"
                        + htmlEscape(textValue) + "'>");
            }
        }
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>Enregistrer</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
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
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format((java.util.Date) value);
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

        StringBuilder sql = new StringBuilder("UPDATE odk_prod.\"" + coreTable + "\" SET ");
        for (ColumnMeta col : columns) {
            sql.append("\"").append(col.columnName).append("\" = ?, ");
        }
        sql.append("\"_LAST_UPDATE_DATE\" = now(), \"_LAST_UPDATE_URI_USER\" = ? WHERE \"_URI\" = ?");

        String currentUser = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : "unknown";

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (ColumnMeta col : columns) {
                bindValue(ps, idx++, col, req.getParameter(col.columnName));
            }
            ps.setString(idx++, currentUser);
            ps.setString(idx, uri);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new SQLException("Aucune ligne mise à jour pour _URI=" + uri);
            }
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
        out.println("<div class='actions'><button type='submit'>Créer la soumission</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private String createSubmission(Connection conn, final HttpServletRequest req, String formId) throws Exception {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);
        return insertSubmissionRow(conn, coreTable, columns, currentUserName(), new ValueSource() {
            public String get(String columnName) {
                return req.getParameter(columnName);
            }
        });
    }

    private String insertSubmissionRow(Connection conn, String coreTable, List<ColumnMeta> columns, String currentUser,
            ValueSource values) throws SQLException {
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
            ps.setTimestamp(idx++, now);
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
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId)
                + "&action=csvUpload' enctype='multipart/form-data'>");
        out.println("<p><input type='file' name='csvFile' accept='.csv,text/csv' required></p>");
        out.println("<div class='actions'><button type='submit'>Importer</button></div>");
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

    private void renderCsvUploadResult(Connection conn, HttpServletRequest req, PrintWriter out, String formId)
            throws SQLException {
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
        List<String> unrecognized = new ArrayList<String>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].trim().toUpperCase(java.util.Locale.ROOT);
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
            try {
                insertSubmissionRow(conn, coreTable, columns, currentUser, new ValueSource() {
                    public String get(String columnName) {
                        return valueMap.get(columnName);
                    }
                });
                success++;
            } catch (Exception e) {
                errors.add("Ligne " + (r + 1) + " : " + e.getMessage());
            }
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

    // ---- CSV export (full form or manual selection) --------------------------------------------

    private void streamCsvExport(Connection conn, HttpServletResponse resp, String formId, String[] selectedUris)
            throws SQLException, IOException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);

        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\""
                + formId.replaceAll("[^A-Za-z0-9_-]", "_") + ".csv\"");
        PrintWriter out = resp.getWriter();
        out.print('﻿');

        StringBuilder header = new StringBuilder("_URI,_SUBMISSION_DATE");
        for (ColumnMeta col : columns) {
            header.append(',').append(csvEscape(col.columnName));
        }
        out.println(header.toString());

        boolean hasSelection = selectedUris != null && selectedUris.length > 0;
        String sql;
        if (hasSelection) {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < selectedUris.length; i++) {
                if (i > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
            }
            sql = "SELECT * FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" IN (" + placeholders + ") "
                    + "ORDER BY \"_SUBMISSION_DATE\" DESC NULLS LAST";
        } else {
            sql = "SELECT * FROM odk_prod.\"" + coreTable + "\" ORDER BY \"_SUBMISSION_DATE\" DESC NULLS LAST";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (hasSelection) {
                for (int i = 0; i < selectedUris.length; i++) {
                    ps.setString(i + 1, selectedUris[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder line = new StringBuilder();
                    line.append(csvEscape(rs.getString("_URI")));
                    Object subDate = rs.getObject("_SUBMISSION_DATE");
                    line.append(',').append(csvEscape(subDate == null ? "" : subDate.toString()));
                    for (ColumnMeta col : columns) {
                        Object value = rs.getObject(col.columnName);
                        line.append(',').append(csvEscape(value == null ? "" : value.toString()));
                    }
                    out.println(line.toString());
                }
            }
        }
        out.flush();
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
        String sql = "SELECT \"_URI\", \"" + geo.latColumn + "\", \"" + geo.lngColumn + "\" FROM odk_prod.\"" + coreTable + "\" "
                + "WHERE \"" + geo.latColumn + "\" IS NOT NULL AND \"" + geo.lngColumn + "\" IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String rowUri = rs.getString(1);
                BigDecimal lat = rs.getBigDecimal(2);
                BigDecimal lng = rs.getBigDecimal(3);
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
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"};
        for (String pattern : patterns) {
            try {
                return new Timestamp(new SimpleDateFormat(pattern).parse(value).getTime());
            } catch (ParseException ignored) {
            }
        }
        throw new SQLException("Date/heure invalide : " + value);
    }
}
