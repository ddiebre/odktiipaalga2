package odk.media;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.FormSchemaUtil;
import odk.common.NavBar;

/**
 * Onglet /media (section "medias", ROLE_DATA_OWNER comme /editSubmission) : parcourir les photos,
 * vidéos et enregistrements audio envoyés avec les soumissions. Ne réimplémente pas la logique de
 * soumission d'ODK : lit directement le schéma de persistance standard d'un champ BINARY (une table
 * "&lt;nom&gt;_BN" par question média, dont chaque ligne référence des fragments dans "&lt;nom&gt;_REF"
 * pointant vers les octets bruts dans "&lt;nom&gt;_BLB" - vérifié en base avant implémentation), plutôt
 * que de passer par /view/binaryData qui attend une SubmissionKey ODK complexe à reconstruire.
 */
public class MediaServlet extends HttpServlet {

    private static final int MAX_ITEMS_PER_ELEMENT = 60;

    private static class MediaItem {
        final String uri;
        final String tableName;
        final String elementName;
        final String contentType;
        final String fileName;
        final Integer contentLength;
        final Timestamp creationDate;
        final String submissionUri;

        MediaItem(String uri, String tableName, String elementName, String contentType, String fileName,
                Integer contentLength, Timestamp creationDate, String submissionUri) {
            this.uri = uri;
            this.tableName = tableName;
            this.elementName = elementName;
            this.contentType = contentType;
            this.fileName = fileName;
            this.contentLength = contentLength;
            this.creationDate = creationDate;
            this.submissionUri = submissionUri;
        }
    }

    private static final String ICON_IMAGE =
            "<svg viewBox='0 0 24 24'><rect x='3' y='3' width='18' height='18' rx='2'/>"
            + "<circle cx='9' cy='9' r='2'/><path d='M21 15l-5-5L5 21'/></svg>";
    private static final String ICON_DOWNLOAD =
            "<svg viewBox='0 0 24 24'><path d='M12 3v12'/><path d='M7 10l5 5 5-5'/><path d='M5 21h14'/></svg>";
    private static final String ICON_EDIT =
            "<svg viewBox='0 0 24 24'><path d='M4 20h4L18.5 9.5a2.121 2.121 0 0 0-3-3L5 17v3z'/><path d='M13.5 6.5l4 4'/></svg>";

    private static String htmlEscape(String s) {
        return FormSchemaUtil.htmlEscape(s);
    }

    private static String urlEncode(String s) {
        return FormSchemaUtil.urlEncode(s);
    }

    private DataSource getDataSource() {
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        return (DataSource) ctx.getBean("dataSource");
    }

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), "medias");
        out.println("<div class='container'>");
        out.println("<h2>" + htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    /**
     * ROLE_DATA_VIEWER (Lecteur de données) peut désormais accéder à /media (voir l'intercept-url),
     * mais reste restreint aux formulaires rattachés à un projet auquel il est affecté (même
     * mécanisme que EditSubmissionServlet/ProjectServlet) - un ROLE_DATA_OWNER n'est pas restreint.
     */
    private boolean isDataOwner() {
        return FormSchemaUtil.currentUserHasRole("ROLE_DATA_OWNER", getServletContext());
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        try (Connection conn = getDataSource().getConnection()) {
            boolean canManage = isDataOwner();
            if ("blob".equals(action)) {
                streamBlob(conn, req, resp, canManage);
                return;
            }
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            String formId = req.getParameter("formId");
            if (formId != null && !canManage
                    && !FormSchemaUtil.isFormAccessibleToViewer(conn, formId, FormSchemaUtil.currentLocalUsername())) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Ce formulaire n'appartient à aucun projet auquel vous êtes affecté.");
                return;
            }
            if (formId == null) {
                renderFormList(conn, out, canManage);
            } else {
                renderGallery(conn, out, formId);
            }
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private static class ProjectNode {
        final int id;
        final String name;
        final Integer parentId;

        ProjectNode(int id, String name, Integer parentId) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
        }
    }

    private static List<ProjectNode> listAllProjects(Connection conn) throws SQLException {
        List<ProjectNode> result = new ArrayList<ProjectNode>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, parent_id FROM odk_prod.app_project WHERE deleted = false ORDER BY name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int parentId = rs.getInt(3);
                result.add(new ProjectNode(rs.getInt(1), rs.getString(2), rs.wasNull() ? null : Integer.valueOf(parentId)));
            }
        }
        return result;
    }

    /** Les form_id rattachés à l'un des projets donnés (odk_prod.app_project_form). */
    private static Set<String> attachedFormIds(Connection conn, Set<Integer> projectIds) throws SQLException {
        Set<String> forms = new HashSet<String>();
        for (Integer projectId : projectIds) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT form_id FROM odk_prod.app_project_form WHERE project_id = ?")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        forms.add(rs.getString(1));
                    }
                }
            }
        }
        return forms;
    }

    /**
     * Répartie par projet : un groupe par projet racine (agrégeant ses sous-projets - même principe
     * que ProjectServlet/ChartsServlet), plus un groupe "Sans projet" pour les formulaires à média
     * non rattachés. Un formulaire rattaché à plusieurs projets apparaît dans chacun.
     */
    private void renderFormList(Connection conn, PrintWriter out, boolean canManage) throws SQLException {
        writeHeader(out, "Médias");
        out.println("<p class='info'>Photos, vidéos et enregistrements audio envoyés avec les soumissions, "
                + "réparties par projet.</p>");

        Set<String> visibleForms = canManage ? null
                : FormSchemaUtil.listFormsVisibleToViewer(conn, FormSchemaUtil.currentLocalUsername());
        List<String> formsWithMedia = new ArrayList<String>();
        java.util.Map<String, Integer> mediaCounts = new java.util.LinkedHashMap<String, Integer>();
        for (String formId : FormSchemaUtil.listAllFormIds(conn)) {
            if (visibleForms != null && !visibleForms.contains(formId)) {
                continue;
            }
            List<FormSchemaUtil.MediaElement> elements = FormSchemaUtil.listMediaElements(conn, formId);
            if (elements.isEmpty()) {
                continue;
            }
            int total = 0;
            for (FormSchemaUtil.MediaElement el : elements) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM odk_prod.\"" + el.tableName + "\" WHERE "
                                + FormSchemaUtil.notDeletedSql("\"_TOP_LEVEL_AURI\""));
                        ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total += rs.getInt(1);
                    }
                }
            }
            formsWithMedia.add(formId);
            mediaCounts.put(formId, total);
        }

        if (formsWithMedia.isEmpty()) {
            out.println("<p class='info'>Aucun formulaire ne contient de question photo/vidéo/audio pour le moment.</p>");
            writeFooter(out);
            return;
        }

        List<ProjectNode> topLevelProjects = new ArrayList<ProjectNode>();
        for (ProjectNode p : listAllProjects(conn)) {
            if (p.parentId == null) {
                topLevelProjects.add(p);
            }
        }

        Set<String> remaining = new LinkedHashSet<String>(formsWithMedia);
        for (ProjectNode top : topLevelProjects) {
            Set<Integer> branch = FormSchemaUtil.descendantProjectIds(conn, top.id);
            Set<String> branchForms = attachedFormIds(conn, branch);
            List<String> section = new ArrayList<String>();
            for (String formId : formsWithMedia) {
                if (branchForms.contains(formId)) {
                    section.add(formId);
                }
            }
            if (section.isEmpty()) {
                continue;
            }
            remaining.removeAll(section);
            renderFormMediaSection(out, top.name + (branch.size() > 1 ? " (+ sous-projets)" : ""), section, mediaCounts);
        }
        if (!remaining.isEmpty()) {
            renderFormMediaSection(out, "Sans projet", new ArrayList<String>(remaining), mediaCounts);
        }
        writeFooter(out);
    }

    private void renderFormMediaSection(PrintWriter out, String heading, List<String> formIds,
            java.util.Map<String, Integer> mediaCounts) {
        out.println("<h3 style='margin-top:24px;'>" + htmlEscape(heading) + "</h3>");
        out.println("<div class='table-enhance'>");
        out.println("<input type='text' class='table-search-input' placeholder='Rechercher un formulaire...'>");
        out.println("<table><thead><tr><th data-sortable>Formulaire</th><th data-sortable>Fichiers</th><th></th></tr></thead><tbody>");
        for (String formId : formIds) {
            out.println("<tr><td>" + htmlEscape(formId) + "</td><td>" + mediaCounts.get(formId) + "</td>"
                    + "<td><a class='btn-link' href='media?formId=" + urlEncode(formId) + "'>" + ICON_IMAGE
                    + " Voir les médias</a></td></tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");
    }

    private void renderGallery(Connection conn, PrintWriter out, String formId) throws SQLException {
        writeHeader(out, "Médias — " + formId);
        out.println("<p class='back-link'><a href='media'>&laquo; Formulaires</a></p>");

        List<FormSchemaUtil.MediaElement> elements = FormSchemaUtil.listMediaElements(conn, formId);
        if (elements.isEmpty()) {
            out.println("<p class='info'>Ce formulaire ne contient aucune question photo/vidéo/audio.</p>");
            writeFooter(out);
            return;
        }

        List<MediaItem> items = new ArrayList<MediaItem>();
        boolean truncated = false;
        for (FormSchemaUtil.MediaElement el : elements) {
            String sql = "SELECT \"_URI\",\"UNROOTED_FILE_PATH\",\"CONTENT_TYPE\",\"CONTENT_LENGTH\","
                    + "\"_CREATION_DATE\",\"_TOP_LEVEL_AURI\" FROM odk_prod.\"" + el.tableName + "\" "
                    + "WHERE " + FormSchemaUtil.notDeletedSql("\"_TOP_LEVEL_AURI\"") + " "
                    + "ORDER BY \"_CREATION_DATE\" DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, MAX_ITEMS_PER_ELEMENT + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        count++;
                        if (count > MAX_ITEMS_PER_ELEMENT) {
                            truncated = true;
                            break;
                        }
                        Object lengthObj = rs.getObject(4);
                        items.add(new MediaItem(rs.getString(1), el.tableName, el.elementName, rs.getString(3),
                                rs.getString(2), lengthObj == null ? null : rs.getInt(4), rs.getTimestamp(5),
                                rs.getString(6)));
                    }
                }
            }
        }

        if (items.isEmpty()) {
            out.println("<p class='info'>Aucun fichier envoyé pour l'instant pour ce formulaire.</p>");
            writeFooter(out);
            return;
        }

        items.sort(new java.util.Comparator<MediaItem>() {
            public int compare(MediaItem a, MediaItem b) {
                if (a.creationDate == null || b.creationDate == null) {
                    return 0;
                }
                return b.creationDate.compareTo(a.creationDate);
            }
        });

        out.println("<p class='info'><strong>" + items.size() + "</strong> fichier(s)"
                + (truncated ? " (affichage limité aux " + MAX_ITEMS_PER_ELEMENT + " plus récents par question)" : "")
                + ".</p>");
        out.println("<div class='media-grid'>");
        for (MediaItem item : items) {
            String type = mediaType(item.contentType);
            String blobUrl = "media?action=blob&table=" + urlEncode(item.tableName) + "&uri=" + urlEncode(item.uri);
            out.println("<div class='media-card'>");
            out.println("<div class='media-preview media-preview-" + type + "'>");
            if ("image".equals(type)) {
                out.println("<img src='" + blobUrl + "' alt='" + htmlEscape(item.elementName) + "' loading='lazy'>");
            } else if ("video".equals(type)) {
                out.println("<video controls preload='metadata'><source src='" + blobUrl + "'"
                        + (item.contentType != null ? " type='" + htmlEscape(item.contentType) + "'" : "") + "></video>");
            } else if ("audio".equals(type)) {
                out.println("<div class='media-audio-badge'>&#9835;</div>"
                        + "<audio controls preload='none' style='width:100%;'><source src='" + blobUrl + "'"
                        + (item.contentType != null ? " type='" + htmlEscape(item.contentType) + "'" : "") + "></audio>");
            } else {
                out.println("<div class='media-audio-badge'>?</div>");
            }
            out.println("</div>");
            out.println("<div class='media-meta'>"
                    + "<strong>" + htmlEscape(item.elementName) + "</strong><br>"
                    + htmlEscape(item.creationDate == null ? "" : item.creationDate.toString())
                    + (item.contentLength != null ? " · " + formatBytes(item.contentLength) : "")
                    + "</div>");
            out.println("<div class='media-actions'>");
            if (item.submissionUri != null) {
                out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&uri="
                        + urlEncode(item.submissionUri) + "'>" + ICON_EDIT + " Soumission</a>");
            }
            out.println("<a class='btn-link' href='" + blobUrl + "&download=1'>" + ICON_DOWNLOAD + " Télécharger</a>");
            out.println("</div>");
            out.println("</div>");
        }
        out.println("</div>");
        writeFooter(out);
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "other";
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/")) {
            return "image";
        }
        if (ct.startsWith("video/")) {
            return "video";
        }
        if (ct.startsWith("audio/")) {
            return "audio";
        }
        return "other";
    }

    private static String formatBytes(int bytes) {
        if (bytes < 1024) {
            return bytes + " o";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " Ko";
        }
        return String.format(Locale.ROOT, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    /**
     * Reconstruit les octets d'un fichier média a partir du schema de persistance ODK standard
     * d'un champ BINARY : la table "&lt;table&gt;" (deja verifiee BINARY) donne le CONTENT_TYPE/nom
     * de fichier, "&lt;base&gt;_REF" liste les fragments (ordre = PART) et "&lt;base&gt;_BLB" contient
     * les octets de chaque fragment.
     */
    private void streamBlob(Connection conn, HttpServletRequest req, HttpServletResponse resp, boolean canManage)
            throws IOException, SQLException, ServletException {
        String table = req.getParameter("table");
        String uri = req.getParameter("uri");
        if (table == null || uri == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Paramètres manquants.");
            return;
        }
        try {
            FormSchemaUtil.checkIdentifier(table);
        } catch (SQLException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Table invalide.");
            return;
        }
        if (!table.endsWith("_BN") || !FormSchemaUtil.isKnownBinaryTable(conn, table)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Table non autorisée.");
            return;
        }
        if (!canManage) {
            String owningFormId = FormSchemaUtil.resolveFormIdForBinaryTable(conn, table);
            if (owningFormId == null
                    || !FormSchemaUtil.isFormAccessibleToViewer(conn, owningFormId, FormSchemaUtil.currentLocalUsername())) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Fichier non accessible.");
                return;
            }
        }
        String base = table.substring(0, table.length() - 3);
        String refTable = base + "_REF";
        String blbTable = base + "_BLB";
        FormSchemaUtil.checkIdentifier(refTable);
        FormSchemaUtil.checkIdentifier(blbTable);

        String contentType = null;
        String fileName = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT \"CONTENT_TYPE\", \"UNROOTED_FILE_PATH\" FROM odk_prod.\"" + table + "\" WHERE \"_URI\" = ?")) {
            ps.setString(1, uri);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Fichier introuvable.");
                    return;
                }
                contentType = rs.getString(1);
                fileName = rs.getString(2);
            }
        }

        resp.setContentType(contentType != null ? contentType : "application/octet-stream");
        if ("1".equals(req.getParameter("download"))) {
            String safeName = (fileName == null || fileName.trim().isEmpty())
                    ? "media" : fileName.replaceAll("[\\\\/]", "_");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        }

        String sql = "SELECT r.\"PART\", b.\"VALUE\" FROM odk_prod.\"" + refTable + "\" r "
                + "JOIN odk_prod.\"" + blbTable + "\" b ON b.\"_URI\" = r.\"_SUB_AURI\" "
                + "WHERE r.\"_DOM_AURI\" = ? ORDER BY r.\"PART\"";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uri);
            try (ResultSet rs = ps.executeQuery(); OutputStream os = resp.getOutputStream()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    byte[] chunk = rs.getBytes(2);
                    if (chunk != null) {
                        os.write(chunk);
                    }
                }
                os.flush();
                if (!any) {
                    // Rien a diffuser (fichier orphelin) - la reponse reste vide avec le content-type deja fixe.
                }
            }
        }
    }
}
