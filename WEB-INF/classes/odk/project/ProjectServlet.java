package odk.project;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.FormSchemaUtil;

/**
 * Admin-only tool (see /project intercept-url, ROLE_DATA_OWNER) regroupant des
 * formulaires ODK existants sous des "projets" et affichant, pour chaque
 * formulaire rattache, un suivi du nombre de soumissions. S'appuie sur les
 * outils existants (/editSubmission) pour la consultation/edition detaillee
 * des soumissions - ce servlet ne fait que la gestion des projets et de leurs
 * associations avec des formulaires, dans ses propres tables (odk_prod.app_project,
 * odk_prod.app_project_form), separees des tables internes d'ODK.
 */
public class ProjectServlet extends HttpServlet {

    private static class ProjectSummary {
        final int id;
        final String name;
        final String description;
        final Timestamp createdAt;
        final int formCount;

        ProjectSummary(int id, String name, String description, Timestamp createdAt, int formCount) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.createdAt = createdAt;
            this.formCount = formCount;
        }
    }

    private static class Project {
        final int id;
        final String name;
        final String description;

        Project(int id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    private DataSource getDataSource() {
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        return (DataSource) ctx.getBean("dataSource");
    }

    @Override
    public void init() throws ServletException {
        try (Connection conn = getDataSource().getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_project ("
                    + "id SERIAL PRIMARY KEY,"
                    + "name VARCHAR(255) NOT NULL,"
                    + "description TEXT,"
                    + "created_by VARCHAR(255),"
                    + "created_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT now())");
            st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_project_form ("
                    + "id SERIAL PRIMARY KEY,"
                    + "project_id INTEGER NOT NULL REFERENCES odk_prod.app_project(id) ON DELETE CASCADE,"
                    + "form_id VARCHAR(255) NOT NULL,"
                    + "added_by VARCHAR(255),"
                    + "added_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "CONSTRAINT app_project_form_unique UNIQUE (project_id, form_id))");
            st.execute("CREATE INDEX IF NOT EXISTS app_project_form_project_id_idx "
                    + "ON odk_prod.app_project_form(project_id)");
            st.execute("CREATE INDEX IF NOT EXISTS app_project_form_form_id_idx "
                    + "ON odk_prod.app_project_form(form_id)");
            getServletContext().log("ProjectServlet: tables odk_prod.app_project / app_project_form prêtes.");
        } catch (SQLException e) {
            getServletContext().log("ProjectServlet: échec de la création des tables app_project", e);
        }
    }

    private static String currentUserName() {
        return SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : "unknown";
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
            + "ul.list li{border-bottom:1px solid #eef0f3;padding:14px 16px;}"
            + "ul.list li:last-child{border-bottom:none;}"
            + "ul.list li a{display:block;}"
            + "ul.list li .muted{color:#6b7280;font-size:13px;margin-top:2px;}"
            + ".form-grid{display:grid;grid-template-columns:160px 1fr;gap:12px 16px;align-items:start;}"
            + "label{font-weight:600;font-size:13px;color:#374151;}"
            + "input[type=text],textarea{width:100%;max-width:480px;padding:7px 10px;"
            + "border:1px solid #d1d5db;border-radius:6px;font-size:14px;font-family:inherit;}"
            + "textarea{min-height:80px;resize:vertical;}"
            + "input:focus,textarea:focus{outline:none;border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,.15);}"
            + "button{background:#2563eb;color:#fff;border:none;padding:9px 22px;border-radius:6px;font-size:14px;"
            + "font-weight:600;cursor:pointer;}"
            + "button:hover{background:#1d4ed8;}"
            + "button.danger{background:#dc2626;}"
            + "button.danger:hover{background:#b91c1c;}"
            + ".err{background:#fef2f2;color:#b91c1c;border:1px solid #fecaca;padding:10px 14px;border-radius:6px;"
            + "margin-bottom:14px;font-size:14px;}"
            + ".success{background:#f0fdf4;color:#15803d;border:1px solid #bbf7d0;padding:10px 14px;border-radius:6px;"
            + "margin-bottom:14px;font-size:14px;}"
            + ".info{color:#6b7280;font-size:13px;margin-bottom:14px;}"
            + ".btn-link{display:inline-block;padding:5px 12px;background:#eef2ff;color:#2563eb;border-radius:5px;font-size:13px;"
            + "border:none;cursor:pointer;}"
            + ".btn-link:hover{background:#e0e7ff;text-decoration:none;}"
            + ".actions{margin-top:18px;}"
            + ".toolbar{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px;}"
            + "form.inline{display:inline;}";

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<style>" + PAGE_CSS + "</style>");
        out.println("</head><body>");
        out.println("<div class='topbar'><a href='home' class='topbar-link'>&laquo; Menu</a> &middot; ODK Tiipaalga &middot; Projets</div>");
        out.println("<div class='container'>");
        out.println("<h2>" + htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        Integer projectId = parseProjectId(req.getParameter("projectId"));
        try (Connection conn = getDataSource().getConnection()) {
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            if (projectId == null) {
                if ("new".equals(action)) {
                    renderProjectForm(out, null, null);
                } else {
                    renderProjectList(conn, out);
                }
                return;
            }
            Project project = getProject(conn, projectId);
            if (project == null) {
                out.println("<p class='err'>Projet introuvable.</p>");
                return;
            }
            if ("edit".equals(action)) {
                renderProjectForm(out, project, null);
            } else if ("addForm".equals(action)) {
                renderAddFormPicker(conn, out, project);
            } else if ("importXml".equals(action)) {
                renderImportXmlForm(out, project, null);
            } else if ("delete".equals(action)) {
                renderDeleteConfirm(conn, out, project);
            } else {
                String imported = req.getParameter("imported");
                String notice = "1".equals(imported) ? "Formulaire importé et rattaché au projet." : null;
                String err = "partial".equals(imported)
                        ? "Le formulaire a été importé dans ODK mais n'a pas pu être rattaché automatiquement. "
                                + "Utilisez « Ajouter un formulaire » pour le sélectionner."
                        : null;
                renderProjectDetail(conn, out, project, err, notice);
            }
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        Integer projectId = parseProjectId(req.getParameter("projectId"));
        try (Connection conn = getDataSource().getConnection()) {
            if ("create".equals(action)) {
                String name = req.getParameter("name");
                String description = req.getParameter("description");
                if (name == null || name.trim().isEmpty()) {
                    resp.setContentType("text/html;charset=UTF-8");
                    renderProjectForm(resp.getWriter(), null, "Le nom du projet est obligatoire.");
                    return;
                }
                int newId = insertProject(conn, name.trim(), description, currentUserName());
                resp.sendRedirect("project?projectId=" + newId);
                return;
            }
            if (projectId == null) {
                resp.sendRedirect("project");
                return;
            }
            Project project = getProject(conn, projectId);
            if (project == null) {
                resp.sendRedirect("project");
                return;
            }
            if ("update".equals(action)) {
                String name = req.getParameter("name");
                String description = req.getParameter("description");
                if (name == null || name.trim().isEmpty()) {
                    resp.setContentType("text/html;charset=UTF-8");
                    renderProjectForm(resp.getWriter(), project, "Le nom du projet est obligatoire.");
                    return;
                }
                updateProject(conn, projectId, name.trim(), description);
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("addForm".equals(action)) {
                String formId = req.getParameter("formId");
                if (formId != null && !formId.trim().isEmpty()) {
                    addFormToProject(conn, projectId, formId.trim(), currentUserName());
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("removeForm".equals(action)) {
                String formId = req.getParameter("formId");
                if (formId != null) {
                    removeFormFromProject(conn, projectId, formId);
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("importXml".equals(action)) {
                handleImportXml(conn, req, resp, project);
            } else if ("deleteConfirm".equals(action)) {
                deleteProject(conn, projectId);
                resp.sendRedirect("project");
            } else {
                resp.sendRedirect("project?projectId=" + projectId);
            }
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private static Integer parseProjectId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Acces donnees -----------------------------------------------------------------------

    private static List<ProjectSummary> listProjects(Connection conn) throws SQLException {
        String sql = "SELECT p.id, p.name, p.description, p.created_at, COUNT(f.id) "
                + "FROM odk_prod.app_project p "
                + "LEFT JOIN odk_prod.app_project_form f ON f.project_id = p.id "
                + "GROUP BY p.id, p.name, p.description, p.created_at "
                + "ORDER BY p.name";
        List<ProjectSummary> result = new ArrayList<ProjectSummary>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new ProjectSummary(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4), rs.getInt(5)));
            }
        }
        return result;
    }

    private static Project getProject(Connection conn, int id) throws SQLException {
        String sql = "SELECT id, name, description FROM odk_prod.app_project WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Project(rs.getInt(1), rs.getString(2), rs.getString(3));
                }
            }
        }
        return null;
    }

    private static List<String> listAttachedForms(Connection conn, int projectId) throws SQLException {
        String sql = "SELECT form_id FROM odk_prod.app_project_form WHERE project_id = ? ORDER BY form_id";
        List<String> forms = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    forms.add(rs.getString(1));
                }
            }
        }
        return forms;
    }

    private static List<String> listAddableForms(Connection conn, int projectId) throws SQLException {
        String sql = "SELECT a.\"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_info_submission_association\" a "
                + "WHERE a.\"IS_SUBMISSION_ALLOWED\" = true AND a.\"URI_SUBMISSION_DATA_MODEL\" IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM odk_prod.app_project_form pf "
                + "WHERE pf.project_id = ? AND pf.form_id = a.\"SUBMISSION_FORM_ID\") "
                + "ORDER BY a.\"SUBMISSION_FORM_ID\"";
        List<String> forms = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    forms.add(rs.getString(1));
                }
            }
        }
        return forms;
    }

    private static int insertProject(Connection conn, String name, String description, String creator) throws SQLException {
        String sql = "INSERT INTO odk_prod.app_project(name, description, created_by) VALUES (?,?,?) RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, creator);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void updateProject(Connection conn, int id, String name, String description) throws SQLException {
        String sql = "UPDATE odk_prod.app_project SET name = ?, description = ?, updated_at = now() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    private static void addFormToProject(Connection conn, int projectId, String formId, String addedBy) throws SQLException {
        String sql = "INSERT INTO odk_prod.app_project_form(project_id, form_id, added_by) VALUES (?,?,?) "
                + "ON CONFLICT (project_id, form_id) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            ps.setString(2, formId);
            ps.setString(3, addedBy);
            ps.executeUpdate();
        }
    }

    private static void removeFormFromProject(Connection conn, int projectId, String formId) throws SQLException {
        String sql = "DELETE FROM odk_prod.app_project_form WHERE project_id = ? AND form_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            ps.setString(2, formId);
            ps.executeUpdate();
        }
    }

    private static void deleteProject(Connection conn, int id) throws SQLException {
        String sql = "DELETE FROM odk_prod.app_project WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private static Set<String> listAllFormIds(Connection conn) throws SQLException {
        String sql = "SELECT \"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_info_submission_association\"";
        Set<String> ids = new HashSet<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    // ---- Import direct d'un formulaire XML (delegue a l'endpoint ODK existant /formUpload) ----

    private static String deriveTitleFromFileName(String fileName) {
        if (fileName == null) {
            return "Formulaire importé";
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        return name.isEmpty() ? "Formulaire importé" : name;
    }

    /**
     * Delegue la creation du formulaire a l'endpoint ODK existant (/formUpload,
     * meme logique de parsing/validation XForms que l'import natif d'ODK), en
     * rejouant le cookie de session de l'utilisateur courant sur un appel local.
     * Le formulaire nouvellement cree est identifie par difference entre l'ensemble
     * des formId avant/apres l'appel, puis rattache automatiquement au projet.
     */
    private void handleImportXml(Connection conn, HttpServletRequest req, HttpServletResponse resp, Project project)
            throws IOException, SQLException {
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
            renderImportXmlForm(resp.getWriter(), project, "Erreur de lecture du fichier : " + e.getMessage());
            return;
        }

        if (xmlBytes == null || xmlBytes.length == 0) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportXmlForm(resp.getWriter(), project, "Aucun fichier XML reçu.");
            return;
        }
        if (formTitle == null || formTitle.trim().isEmpty()) {
            formTitle = deriveTitleFromFileName(xmlFileName);
        }

        Set<String> before = listAllFormIds(conn);

        int status;
        String responseBody;
        try {
            String uploadUrl = req.getScheme() + "://127.0.0.1:" + req.getLocalPort() + req.getContextPath() + "/formUpload";
            RequestConfig config = RequestConfig.custom().setConnectTimeout(15000).setSocketTimeout(15000).build();
            try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
                HttpPost post = new HttpPost(uploadUrl);
                String cookie = req.getHeader("Cookie");
                if (cookie != null) {
                    post.setHeader("Cookie", cookie);
                }
                HttpEntity entity = MultipartEntityBuilder.create()
                        .addBinaryBody("form_def_file", xmlBytes, ContentType.APPLICATION_XML,
                                xmlFileName != null ? xmlFileName : "form.xml")
                        .addTextBody("form_name", formTitle, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                        .build();
                post.setEntity(entity);
                try (CloseableHttpResponse httpResp = client.execute(post)) {
                    status = httpResp.getStatusLine().getStatusCode();
                    responseBody = httpResp.getEntity() != null ? EntityUtils.toString(httpResp.getEntity(), "UTF-8") : "";
                }
            }
        } catch (IOException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportXmlForm(resp.getWriter(), project, "Échec de la connexion vers l'import ODK : " + e.getMessage());
            return;
        }

        if (status == 201) {
            Set<String> after = listAllFormIds(conn);
            after.removeAll(before);
            if (after.size() == 1) {
                String newFormId = after.iterator().next();
                addFormToProject(conn, project.id, newFormId, currentUserName());
                resp.sendRedirect("project?projectId=" + project.id + "&imported=1");
            } else {
                resp.sendRedirect("project?projectId=" + project.id + "&imported=partial");
            }
        } else {
            resp.setContentType("text/html;charset=UTF-8");
            String message = responseBody == null || responseBody.trim().isEmpty()
                    ? "Échec de l'import (code " + status + ")."
                    : responseBody.trim();
            if (message.length() > 500) {
                message = message.substring(0, 500);
            }
            renderImportXmlForm(resp.getWriter(), project, message);
        }
    }

    // ---- Rendu ---------------------------------------------------------------------------------

    private void renderProjectList(Connection conn, PrintWriter out) throws SQLException {
        writeHeader(out, "Projets");
        out.println("<div class='toolbar'><a class='btn-link' href='project?action=new'>+ Nouveau projet</a></div>");
        List<ProjectSummary> projects = listProjects(conn);
        if (projects.isEmpty()) {
            out.println("<p class='info'>Aucun projet pour le moment.</p>");
        } else {
            out.println("<ul class='list'>");
            for (ProjectSummary p : projects) {
                out.println("<li><a href='project?projectId=" + p.id + "'><strong>" + htmlEscape(p.name) + "</strong>"
                        + "<div class='muted'>" + p.formCount + " formulaire(s)"
                        + (p.description != null && !p.description.trim().isEmpty()
                                ? " &middot; " + htmlEscape(p.description) : "")
                        + "</div></a></li>");
            }
            out.println("</ul>");
        }
        writeFooter(out);
    }

    private void renderProjectForm(PrintWriter out, Project project, String error) {
        boolean editing = project != null;
        writeHeader(out, editing ? "Modifier le projet" : "Nouveau projet");
        out.println("<p class='back-link'><a href='project" + (editing ? "?projectId=" + project.id : "")
                + "'>&laquo; " + (editing ? "Retour au projet" : "Liste des projets") + "</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<form method='post' action='project?" + (editing ? "projectId=" + project.id + "&action=update" : "action=create") + "'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Nom</label><input type='text' name='name' value='"
                + htmlEscape(editing ? project.name : "") + "' required>");
        out.println("<label>Description</label><textarea name='description'>"
                + htmlEscape(editing && project.description != null ? project.description : "") + "</textarea>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>" + (editing ? "Enregistrer" : "Créer le projet") + "</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void renderProjectDetail(Connection conn, PrintWriter out, Project project, String error, String notice)
            throws SQLException {
        writeHeader(out, project.name);
        out.println("<p class='back-link'><a href='project'>&laquo; Liste des projets</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        if (project.description != null && !project.description.trim().isEmpty()) {
            out.println("<p class='info'>" + htmlEscape(project.description) + "</p>");
        }
        out.println("<div class='toolbar'>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=edit'>Modifier</a>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=addForm'>+ Ajouter un formulaire</a>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=importXml'>Importer un formulaire XML</a>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=delete'>Supprimer le projet</a>");
        out.println("</div>");

        List<String> formIds = listAttachedForms(conn, project.id);
        if (formIds.isEmpty()) {
            out.println("<p class='info'>Aucun formulaire rattaché à ce projet.</p>");
        } else {
            out.println("<table><tr><th>Formulaire</th><th>Soumissions</th><th>Dernière soumission</th><th></th></tr>");
            for (String formId : formIds) {
                out.println("<tr>");
                try {
                    FormSchemaUtil.SubmissionStats stats = FormSchemaUtil.submissionStats(conn, formId);
                    out.println("<td><a href='editSubmission?formId=" + urlEncode(formId) + "'>" + htmlEscape(formId) + "</a></td>");
                    out.println("<td>" + stats.count + "</td>");
                    out.println("<td>" + htmlEscape(stats.lastSubmissionDate == null ? "" : stats.lastSubmissionDate.toString()) + "</td>");
                } catch (SQLException e) {
                    out.println("<td>" + htmlEscape(formId) + "</td>");
                    out.println("<td class='err' colspan='2'>Formulaire introuvable (peut-être supprimé)</td>");
                }
                out.println("<td><form class='inline' method='post' action='project?projectId=" + project.id + "&action=removeForm'>"
                        + "<input type='hidden' name='formId' value='" + htmlEscape(formId) + "'>"
                        + "<button type='submit' class='btn-link'>Retirer</button></form></td>");
                out.println("</tr>");
            }
            out.println("</table>");
        }
        writeFooter(out);
    }

    private void renderAddFormPicker(Connection conn, PrintWriter out, Project project) throws SQLException {
        writeHeader(out, "Ajouter un formulaire — " + project.name);
        out.println("<p class='back-link'><a href='project?projectId=" + project.id + "'>&laquo; Retour au projet</a></p>");
        List<String> addable = listAddableForms(conn, project.id);
        if (addable.isEmpty()) {
            out.println("<p class='info'>Tous les formulaires disponibles sont déjà rattachés à ce projet.</p>");
        } else {
            out.println("<ul class='list'>");
            for (String formId : addable) {
                out.println("<li style='display:flex;justify-content:space-between;align-items:center;'>"
                        + "<span>" + htmlEscape(formId) + "</span>"
                        + "<form class='inline' method='post' action='project?projectId=" + project.id + "&action=addForm'>"
                        + "<input type='hidden' name='formId' value='" + htmlEscape(formId) + "'>"
                        + "<button type='submit' class='btn-link'>Ajouter</button></form></li>");
            }
            out.println("</ul>");
        }
        writeFooter(out);
    }

    private void renderImportXmlForm(PrintWriter out, Project project, String error) {
        writeHeader(out, "Importer un formulaire XML — " + project.name);
        out.println("<p class='back-link'><a href='project?projectId=" + project.id + "'>&laquo; Retour au projet</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p class='info'>Le formulaire sera créé dans ODK puis automatiquement rattaché à ce projet.</p>");
        out.println("<form method='post' action='project?projectId=" + project.id
                + "&action=importXml' enctype='multipart/form-data'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Fichier XML</label><input type='file' name='xmlFile' accept='.xml,text/xml' required>");
        out.println("<label>Titre</label><input type='text' name='formTitle' "
                + "placeholder='(facultatif, déduit du nom de fichier si vide)'>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>Importer</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void renderDeleteConfirm(Connection conn, PrintWriter out, Project project) throws SQLException {
        writeHeader(out, "Supprimer le projet");
        out.println("<p class='back-link'><a href='project?projectId=" + project.id + "'>&laquo; Retour au projet</a></p>");
        int formCount = listAttachedForms(conn, project.id).size();
        out.println("<div class='card'>");
        out.println("<p>Voulez-vous vraiment supprimer le projet <strong>" + htmlEscape(project.name) + "</strong> ? "
                + "Cette action retirera aussi son association avec " + formCount + " formulaire(s) "
                + "(les soumissions elles-mêmes ne sont pas supprimées).</p>");
        out.println("<form method='post' action='project?projectId=" + project.id + "&action=deleteConfirm'>");
        out.println("<div class='actions'><button type='submit' class='danger'>Supprimer définitivement</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }
}
