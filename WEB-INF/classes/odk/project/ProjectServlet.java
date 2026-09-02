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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.opendatakit.aggregate.ContextFactory;
import org.opendatakit.common.security.client.UserSecurityInfo;
import org.opendatakit.common.security.common.GrantedAuthorityName;
import org.opendatakit.common.security.server.SecurityServiceUtil;
import org.opendatakit.common.web.CallingContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.ChartUtil;
import odk.common.FormSchemaUtil;
import odk.common.NavBar;
import odk.common.FormUploadUtil;

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
            st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_project_user ("
                    + "id SERIAL PRIMARY KEY,"
                    + "project_id INTEGER NOT NULL REFERENCES odk_prod.app_project(id) ON DELETE CASCADE,"
                    + "username VARCHAR(255) NOT NULL,"
                    + "added_by VARCHAR(255),"
                    + "added_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "CONSTRAINT app_project_user_unique UNIQUE (project_id, username))");
            st.execute("CREATE INDEX IF NOT EXISTS app_project_user_project_id_idx "
                    + "ON odk_prod.app_project_user(project_id)");
            st.execute("CREATE INDEX IF NOT EXISTS app_project_user_username_idx "
                    + "ON odk_prod.app_project_user(username)");
            getServletContext().log("ProjectServlet: tables odk_prod.app_project / app_project_form / app_project_user prêtes.");
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
    private static final String ICON_USERS =
            "<svg viewBox='0 0 24 24'><circle cx='9' cy='8' r='3.2'/><path d='M3.5 20c0-3.3 2.6-6 6-6s6 2.7 6 6'/>"
            + "<circle cx='17.5' cy='9' r='2.6'/><path d='M15.8 14.3c2.6.4 4.7 2.6 4.7 5.7'/></svg>";

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), "projets");
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
            } else if ("addCollector".equals(action)) {
                CallingContext cc = ContextFactory.getCallingContext(this, req);
                renderAddCollectorPicker(conn, out, project, cc);
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
            } else if ("addCollector".equals(action)) {
                String username = req.getParameter("username");
                if (username != null && !username.trim().isEmpty()) {
                    addCollectorToProject(conn, projectId, username.trim(), currentUserName());
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("removeCollector".equals(action)) {
                String username = req.getParameter("username");
                if (username != null) {
                    removeCollectorFromProject(conn, projectId, username);
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

    private static List<String> listAssignedCollectors(Connection conn, int projectId) throws SQLException {
        String sql = "SELECT username FROM odk_prod.app_project_user WHERE project_id = ? ORDER BY username";
        List<String> usernames = new ArrayList<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    usernames.add(rs.getString(1));
                }
            }
        }
        return usernames;
    }

    /** Comptes ROLE_DATA_COLLECTOR (groupe GROUP_DATA_COLLECTORS) pas encore affectes a ce projet. */
    private static List<String> listAddableCollectors(Connection conn, int projectId, CallingContext cc) throws Exception {
        List<String> assigned = listAssignedCollectors(conn, projectId);
        ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
        List<String> addable = new ArrayList<String>();
        for (UserSecurityInfo u : all) {
            if (u.getAssignedUserGroups() != null && u.getAssignedUserGroups().contains(GrantedAuthorityName.GROUP_DATA_COLLECTORS)
                    && !assigned.contains(u.getUsername())) {
                addable.add(u.getUsername());
            }
        }
        return addable;
    }

    private static void addCollectorToProject(Connection conn, int projectId, String username, String addedBy) throws SQLException {
        String sql = "INSERT INTO odk_prod.app_project_user(project_id, username, added_by) VALUES (?,?,?) "
                + "ON CONFLICT (project_id, username) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            ps.setString(2, username);
            ps.setString(3, addedBy);
            ps.executeUpdate();
        }
    }

    private static void removeCollectorFromProject(Connection conn, int projectId, String username) throws SQLException {
        String sql = "DELETE FROM odk_prod.app_project_user WHERE project_id = ? AND username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            ps.setString(2, username);
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

    // ---- Import direct d'un formulaire XML (delegue a l'endpoint ODK existant /formUpload) ----

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
            formTitle = FormUploadUtil.deriveTitleFromFileName(xmlFileName);
        }

        Set<String> before = FormSchemaUtil.listAllFormIds(conn);

        FormUploadUtil.UploadResult result;
        try {
            result = FormUploadUtil.uploadFormXml(req, xmlBytes, xmlFileName, formTitle);
        } catch (IOException e) {
            resp.setContentType("text/html;charset=UTF-8");
            renderImportXmlForm(resp.getWriter(), project, "Échec de la connexion vers l'import ODK : " + e.getMessage());
            return;
        }

        if (result.isSuccess()) {
            Set<String> after = FormSchemaUtil.listAllFormIds(conn);
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
            String message = result.body == null || result.body.trim().isEmpty()
                    ? "Échec de l'import (code " + result.status + ")."
                    : result.body.trim();
            if (message.length() > 500) {
                message = message.substring(0, 500);
            }
            renderImportXmlForm(resp.getWriter(), project, message);
        }
    }

    // ---- Rendu ---------------------------------------------------------------------------------

    private void renderProjectList(Connection conn, PrintWriter out) throws SQLException {
        writeHeader(out, "Projets");
        out.println("<div class='toolbar'><a class='btn-link' href='project?action=new'>" + ICON_PLUS + " Nouveau projet</a></div>");
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
        out.println("<div class='actions'><button type='submit'>" + (editing ? "Enregistrer" : ICON_PLUS + " Créer le projet")
                + "</button></div>");
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
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=edit'>" + ICON_EDIT + " Modifier</a>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=addForm'>" + ICON_PLUS
                + " Ajouter un formulaire</a>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=importXml'>" + ICON_UPLOAD
                + " Importer un formulaire XML</a>");
        out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=addCollector'>" + ICON_USERS
                + " Affecter un agent</a>");
        out.println("<a class='btn-link danger' href='project?projectId=" + project.id + "&action=delete'>" + ICON_TRASH
                + " Supprimer le projet</a>");
        out.println("</div>");

        List<String> formIds = listAttachedForms(conn, project.id);
        if (formIds.isEmpty()) {
            out.println("<p class='info'>Aucun formulaire rattaché à ce projet.</p>");
        } else {
            LinkedHashMap<String, Long> formCounts = new LinkedHashMap<String, Long>();
            for (String formId : formIds) {
                try {
                    formCounts.put(formId, FormSchemaUtil.submissionStats(conn, formId).count);
                } catch (SQLException e) {
                    // Formulaire introuvable (peut-être supprimé) - ignoré pour ce graphique,
                    // le message d'erreur reste visible dans la table ci-dessous.
                }
            }
            boolean hasSubmissions = false;
            for (Long v : formCounts.values()) {
                if (v != null && v > 0) {
                    hasSubmissions = true;
                    break;
                }
            }
            if (hasSubmissions) {
                out.println("<div class='charts-grid'>");
                ChartUtil.renderHorizontalBarChart(out, "Soumissions par formulaire", formCounts);
                out.println("</div>");
            }

            out.println("<div class='table-enhance'>");
            out.println("<input type='text' class='table-search-input' placeholder='Rechercher un formulaire...'>");
            out.println("<table><thead><tr><th data-sortable>Formulaire</th><th data-sortable>Soumissions</th>"
                    + "<th data-sortable>Dernière soumission</th><th></th></tr></thead><tbody>");
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
                out.println("<td><div class='row-actions'>"
                        + "<a class='btn-link' href='formXml?formId=" + urlEncode(formId) + "'>" + ICON_DOWNLOAD + " XML</a>"
                        + "<form class='inline' method='post' action='project?projectId=" + project.id + "&action=removeForm'>"
                        + "<input type='hidden' name='formId' value='" + htmlEscape(formId) + "'>"
                        + "<button type='submit' class='btn-link danger'>" + ICON_TRASH + " Retirer</button></form>"
                        + "</div></td>");
                out.println("</tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }

        out.println("<h2 style='margin-top:32px;'>Agents de collecte affectés</h2>");
        out.println("<p class='info'>Un agent affecté à ce projet ne verra, sur son téléphone, que les formulaires "
                + "listés ci-dessus. Un agent non affecté à aucun projet ne verra aucun formulaire.</p>");
        List<String> collectors = listAssignedCollectors(conn, project.id);
        if (collectors.isEmpty()) {
            out.println("<p class='info'>Aucun agent affecté à ce projet.</p>");
        } else {
            out.println("<div class='table-enhance'>");
            out.println("<input type='text' class='table-search-input' placeholder='Rechercher un agent...'>");
            out.println("<table><thead><tr><th data-sortable>Nom d'utilisateur</th><th></th></tr></thead><tbody>");
            for (String username : collectors) {
                out.println("<tr><td>" + htmlEscape(username) + "</td><td>"
                        + "<form class='inline' method='post' action='project?projectId=" + project.id + "&action=removeCollector'>"
                        + "<input type='hidden' name='username' value='" + htmlEscape(username) + "'>"
                        + "<button type='submit' class='btn-link danger'>" + ICON_TRASH + " Retirer</button></form></td></tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }
        writeFooter(out);
    }

    private void renderAddCollectorPicker(Connection conn, PrintWriter out, Project project, CallingContext cc)
            throws SQLException {
        writeHeader(out, "Affecter un agent — " + project.name);
        out.println("<p class='back-link'><a href='project?projectId=" + project.id + "'>&laquo; Retour au projet</a></p>");
        List<String> addable;
        try {
            addable = listAddableCollectors(conn, project.id, cc);
        } catch (Exception e) {
            out.println("<p class='err'>Erreur : " + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
            return;
        }
        if (addable.isEmpty()) {
            out.println("<p class='info'>Aucun agent de collecte disponible à affecter "
                    + "(tous déjà affectés, ou aucun compte avec le rôle Collecteur de données).</p>");
        } else {
            out.println("<ul class='list'>");
            for (String username : addable) {
                out.println("<li style='display:flex;justify-content:space-between;align-items:center;'>"
                        + "<span>" + htmlEscape(username) + "</span>"
                        + "<form class='inline' method='post' action='project?projectId=" + project.id + "&action=addCollector'>"
                        + "<input type='hidden' name='username' value='" + htmlEscape(username) + "'>"
                        + "<button type='submit' class='btn-link'>" + ICON_PLUS + " Affecter</button></form></li>");
            }
            out.println("</ul>");
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
                        + "<button type='submit' class='btn-link'>" + ICON_PLUS + " Ajouter</button></form></li>");
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
        out.println("<div class='actions'><button type='submit'>" + ICON_UPLOAD + " Importer</button></div>");
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
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Supprimer définitivement</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }
}
