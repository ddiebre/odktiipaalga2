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

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import odk.common.FormSchemaUtil;
import odk.common.FormUploadUtil;
import odk.common.XlsFormConverter;

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

    private static final String ICON_PLUS = "<svg viewBox='0 0 24 24'><path d='M12 5v14M5 12h14'/></svg>";
    private static final String ICON_EDIT =
            "<svg viewBox='0 0 24 24'><path d='M4 20h4L18.5 9.5a2.121 2.121 0 0 0-3-3L5 17v3z'/><path d='M13.5 6.5l4 4'/></svg>";
    private static final String ICON_TRASH =
            "<svg viewBox='0 0 24 24'><path d='M4 7h16'/><path d='M9 7V4h6v3'/>"
            + "<path d='M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13'/><path d='M10 11v6M14 11v6'/></svg>";
    private static final String ICON_UPLOAD =
            "<svg viewBox='0 0 24 24'><path d='M12 16V4'/><path d='M7 9l5-5 5 5'/><path d='M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3'/></svg>";
    private static final String ICON_DOWNLOAD =
            "<svg viewBox='0 0 24 24'><path d='M12 4v12'/><path d='M7 11l5 5 5-5'/><path d='M4 20h16'/></svg>";
    private static final String ICON_MAP =
            "<svg viewBox='0 0 24 24'><path d='M12 21s7-6.5 7-11a7 7 0 1 0-14 0c0 4.5 7 11 7 11z'/><circle cx='12' cy='10' r='2.4'/></svg>";

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css'>");
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
            if (formId == null && "importForm".equals(action)) {
                renderImportFormPage(out, null);
            } else if (formId == null && "importXlsForm".equals(action)) {
                renderImportXlsFormPage(out, null);
            } else if (formId == null) {
                renderFormList(conn, out);
            } else if ("new".equals(action)) {
                renderCreateForm(conn, out, formId, null);
            } else if ("csv".equals(action)) {
                renderCsvUploadForm(conn, out, formId, null);
            } else if ("map".equals(action)) {
                renderMap(conn, out, formId);
            } else if ("delete".equals(action) && uri != null) {
                renderDeleteConfirm(conn, out, formId, uri, null);
            } else if (uri == null) {
                int offset = 0;
                try {
                    offset = Integer.parseInt(req.getParameter("offset"));
                } catch (NumberFormatException ignored) {
                }
                String deleted = req.getParameter("deleted");
                String notice = deleted == null ? null
                        : "1".equals(deleted) ? "Soumission supprimée."
                        : deleted + " soumission(s) supprimée(s).";
                renderSubmissionList(conn, out, formId, offset, notice);
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
            if ("deleteConfirm".equals(action)) {
                String error = null;
                try {
                    deleteSubmission(conn, formId, uri);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (error == null) {
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&deleted=1");
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderDeleteConfirm(conn, resp.getWriter(), formId, uri, error);
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
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(formId) + "&deleted=" + count);
                    return;
                }
                resp.setContentType("text/html;charset=UTF-8");
                renderDeleteConfirmMultiple(resp.getWriter(), formId, uris, error);
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
        out.println("<div class='toolbar'>");
        out.println("<a class='btn-link' href='editSubmission?action=importForm'>" + ICON_UPLOAD
                + " Importer un formulaire XML</a>");
        out.println("<a class='btn-link' href='editSubmission?action=importXlsForm'>" + ICON_UPLOAD
                + " Importer un XLSForm (Excel)</a>");
        out.println("</div>");
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

    // ---- Import direct d'un formulaire XML (delegue a l'endpoint ODK existant /formUpload) -----

    private void renderImportFormPage(PrintWriter out, String error) {
        writeHeader(out, "Importer un formulaire XML");
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
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(after.iterator().next()) + "&created=1");
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
        writeHeader(out, "Importer un XLSForm (Excel)");
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
        writeHeader(out, "Formulaire converti");
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
                    resp.sendRedirect("editSubmission?formId=" + urlEncode(after.iterator().next()) + "&created=1");
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

    private void renderSubmissionList(Connection conn, PrintWriter out, String formId, int offset, String notice)
            throws SQLException {
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

        out.println("<div class='toolbar'>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=new'>" + ICON_PLUS
                + " Nouvelle soumission</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=csv'>" + ICON_UPLOAD
                + " Importer CSV</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=map'>" + ICON_MAP
                + " Carte</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=exportAll'>" + ICON_DOWNLOAD
                + " Télécharger tout (CSV)</a>");
        out.println("<a class='btn-link' href='formXml?formId=" + urlEncode(formId) + "'>" + ICON_DOWNLOAD
                + " Télécharger le formulaire (XML)</a>");
        out.println("</div>");

        List<ColumnMeta> columns = listEditableColumns(conn, coreTable);

        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=exportSelected'>");
        out.println("<div style='overflow-x:auto;'>");
        out.println("<table><tr><th><input type='checkbox' id='selAll'></th><th>_URI</th><th>Date de soumission</th>");
        for (ColumnMeta col : columns) {
            out.println("<th>" + htmlEscape(col.elementName) + "</th>");
        }
        out.println("<th></th></tr>");

        StringBuilder selectCols = new StringBuilder("\"_URI\",\"_SUBMISSION_DATE\"");
        for (ColumnMeta col : columns) {
            selectCols.append(",\"").append(col.columnName).append("\"");
        }
        String sql = "SELECT " + selectCols + " FROM odk_prod.\"" + coreTable + "\" "
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
                            + htmlEscape(subDate == null ? "" : subDate.toString()) + "</td>");
                    int idx = 3;
                    for (ColumnMeta col : columns) {
                        Object value = rs.getObject(idx++);
                        out.println("<td>" + htmlEscape(formatValueForDisplay(value)) + "</td>");
                    }
                    out.println("<td>"
                            + "<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(rowUri)
                            + "'>" + ICON_EDIT + " Modifier</a> "
                            + "<a class='btn-link danger' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(rowUri)
                            + "&action=delete'>" + ICON_TRASH + " Supprimer</a></td></tr>");
                }
            }
        }
        out.println("</table>");
        out.println("</div>");
        if (rowCount > 0) {
            out.println("<div class='actions'>"
                    + "<button type='submit' formaction='editSubmission?formId=" + urlEncode(formId)
                    + "&action=exportSelected'>" + ICON_DOWNLOAD + " Télécharger la sélection (CSV)</button> "
                    + "<button type='submit' class='danger' formaction='editSubmission?formId=" + urlEncode(formId)
                    + "&action=deleteSelectedConfirm'>" + ICON_TRASH + " Supprimer la sélection</button>"
                    + "</div>");
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
        out.println("<div class='toolbar'>");
        out.println("<a class='btn-link danger' href='editSubmission?formId=" + urlEncode(formId) + "&uri=" + urlEncode(uri)
                + "&action=delete'>" + ICON_TRASH + " Supprimer cette soumission</a>");
        out.println("</div>");

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
                + "Cette action est irréversible.</p>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=deleteConfirm'>");
        out.println("<input type='hidden' name='uri' value='" + htmlEscape(uri) + "'>");
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Supprimer définitivement</button></div>");
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
                + "Cette action est irréversible.</p>");
        out.println("<ul class='list'>");
        for (String u : uris) {
            out.println("<li>" + htmlEscape(u) + "</li>");
        }
        out.println("</ul>");
        out.println("<form method='post' action='editSubmission?formId=" + urlEncode(formId) + "&action=deleteSelected'>");
        for (String u : uris) {
            out.println("<input type='hidden' name='uri' value='" + htmlEscape(u) + "'>");
        }
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Supprimer définitivement</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void deleteSubmission(Connection conn, String formId, String uri) throws SQLException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        String sql = "DELETE FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uri);
            int deleted = ps.executeUpdate();
            if (deleted == 0) {
                throw new SQLException("Aucune soumission supprimée pour _URI=" + uri);
            }
        }
    }

    private int deleteSubmissions(Connection conn, String formId, String[] uris) throws SQLException {
        if (uris == null || uris.length == 0) {
            return 0;
        }
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < uris.length; i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }
        String sql = "DELETE FROM odk_prod.\"" + coreTable + "\" WHERE \"_URI\" IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < uris.length; i++) {
                ps.setString(i + 1, uris[i]);
            }
            return ps.executeUpdate();
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
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format((java.util.Date) value);
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
        out.println("<div class='actions'><button type='submit'>" + ICON_PLUS + " Créer la soumission</button></div>");
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
