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
import java.util.Map;
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

import odk.common.ActivityLog;
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
        final Integer parentId;

        ProjectSummary(int id, String name, String description, Timestamp createdAt, int formCount, Integer parentId) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.createdAt = createdAt;
            this.formCount = formCount;
            this.parentId = parentId;
        }
    }

    private static class Project {
        final int id;
        final String name;
        final String description;
        final Integer parentId;

        Project(int id, String name, String description, Integer parentId) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.parentId = parentId;
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
            // Sous-projets : parent_id nullable (NULL = projet racine). Ajoutee separement (ALTER)
            // car app_project existait deja avant cette fonctionnalite.
            st.execute("ALTER TABLE odk_prod.app_project ADD COLUMN IF NOT EXISTS "
                    + "parent_id INTEGER REFERENCES odk_prod.app_project(id) ON DELETE SET NULL");
            st.execute("CREATE INDEX IF NOT EXISTS app_project_parent_id_idx ON odk_prod.app_project(parent_id)");
            // Suppression logique (corbeille) : un projet "supprime" garde sa ligne avec deleted = true,
            // et toutes les lectures filtrent sur deleted = false. Il peut etre restaure, ou supprime
            // definitivement (vrai DELETE) depuis la corbeille.
            st.execute("ALTER TABLE odk_prod.app_project ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT false");
            st.execute("ALTER TABLE odk_prod.app_project ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
            st.execute("ALTER TABLE odk_prod.app_project ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255)");
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
            // Corbeille des soumissions (utilisee par /editSubmission, /charts, /media...) : creee ici
            // car ce servlet est charge au demarrage (load-on-startup), avant toute lecture.
            FormSchemaUtil.ensureSubmissionTables(st);
            getServletContext().log("ProjectServlet: tables odk_prod.app_project / app_project_form / app_project_user / "
                    + "app_submission_deleted prêtes.");
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

    /**
     * ROLE_DATA_VIEWER (Lecteur de données) peut désormais accéder à /project (voir l'intercept-url)
     * pour consulter, mais la création/modification/suppression de projets et leurs rattachements
     * restent réservées à ROLE_DATA_OWNER (Gestionnaire de formulaires).
     */
    private boolean isDataOwner() {
        return FormSchemaUtil.currentUserHasRole("ROLE_DATA_OWNER", getServletContext());
    }

    private void renderAccessDenied(PrintWriter out, String backHref, String backLabel) {
        writeHeader(out, "Accès refusé");
        out.println("<p class='err'>Votre rôle (Lecteur de données) permet de consulter, "
                + "mais pas de créer, modifier ou supprimer.</p>");
        out.println("<p class='back-link'><a href='" + backHref + "'>&laquo; " + htmlEscape(backLabel) + "</a></p>");
        writeFooter(out);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        Integer projectId = parseProjectId(req.getParameter("projectId"));
        boolean canManage = isDataOwner();
        try (Connection conn = getDataSource().getConnection()) {
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            if (projectId == null) {
                if ("trash".equals(action)) {
                    if (!canManage) {
                        renderAccessDenied(out, "project", "Projets");
                        return;
                    }
                    renderTrash(conn, out, req.getParameter("error"), req.getParameter("notice"));
                } else if ("new".equals(action)) {
                    if (!canManage) {
                        renderAccessDenied(out, "project", "Projets");
                        return;
                    }
                    Integer prefillParentId = parseProjectId(req.getParameter("parentId"));
                    renderProjectForm(conn, out, null, null, prefillParentId);
                } else {
                    renderProjectList(conn, out, canManage);
                }
                return;
            }
            Project project = getProject(conn, projectId);
            if (project == null) {
                out.println("<p class='err'>Projet introuvable.</p>");
                return;
            }
            if (!canManage && !FormSchemaUtil.isProjectAccessibleToViewer(conn, projectId, FormSchemaUtil.currentLocalUsername())) {
                renderAccessDenied(out, "project", "Projets");
                return;
            }
            if ("edit".equals(action) || "addForm".equals(action) || "addCollector".equals(action)
                    || "importXml".equals(action) || "delete".equals(action)) {
                if (!canManage) {
                    renderAccessDenied(out, "project?projectId=" + projectId, project.name);
                    return;
                }
            }
            if ("edit".equals(action)) {
                renderProjectForm(conn, out, project, null, project.parentId);
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
                renderProjectDetail(conn, out, project, err, notice, canManage);
            }
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        Integer projectId = parseProjectId(req.getParameter("projectId"));
        // Toutes les actions POST de ce servlet ecrivent en base (creation/modification/suppression
        // de projets ou de leurs rattachements) - reservees a ROLE_DATA_OWNER.
        if (!isDataOwner()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Action non autorisée pour votre rôle (Lecteur de données : consultation uniquement).");
            return;
        }
        try (Connection conn = getDataSource().getConnection()) {
            if ("create".equals(action)) {
                String name = req.getParameter("name");
                String description = req.getParameter("description");
                Integer parentId = parseProjectId(req.getParameter("parentId"));
                if (name == null || name.trim().isEmpty()) {
                    resp.setContentType("text/html;charset=UTF-8");
                    renderProjectForm(conn, resp.getWriter(), null, "Le nom du projet est obligatoire.", parentId);
                    return;
                }
                if (parentId != null && getProject(conn, parentId) == null) {
                    resp.setContentType("text/html;charset=UTF-8");
                    renderProjectForm(conn, resp.getWriter(), null, "Projet parent introuvable.", null);
                    return;
                }
                int newId = insertProject(conn, name.trim(), description, currentUserName(), parentId);
                ActivityLog.record(conn, "project.create", "projet", String.valueOf(newId), "Nom : " + name.trim());
                resp.sendRedirect("project?projectId=" + newId);
                return;
            }
            if (projectId == null) {
                resp.sendRedirect("project");
                return;
            }
            // Actions de la corbeille : portent sur un projet supprime (deleted = true), donc
            // traitees avant getProject() qui ne voit que les projets actifs.
            if ("restore".equals(action) || "purge".equals(action)) {
                handleTrashAction(conn, resp, action, projectId);
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
                Integer parentId = parseProjectId(req.getParameter("parentId"));
                if (name == null || name.trim().isEmpty()) {
                    resp.setContentType("text/html;charset=UTF-8");
                    renderProjectForm(conn, resp.getWriter(), project, "Le nom du projet est obligatoire.", parentId);
                    return;
                }
                if (parentId != null) {
                    if (parentId.intValue() == projectId.intValue()
                            || FormSchemaUtil.descendantProjectIds(conn, projectId).contains(parentId)) {
                        resp.setContentType("text/html;charset=UTF-8");
                        renderProjectForm(conn, resp.getWriter(), project,
                                "Le projet parent ne peut pas être ce projet lui-même ni l'un de ses sous-projets.", parentId);
                        return;
                    }
                    if (getProject(conn, parentId) == null) {
                        resp.setContentType("text/html;charset=UTF-8");
                        renderProjectForm(conn, resp.getWriter(), project, "Projet parent introuvable.", parentId);
                        return;
                    }
                }
                updateProject(conn, projectId, name.trim(), description, parentId);
                ActivityLog.record(conn, "project.update", "projet", String.valueOf(projectId), "Nom : " + name.trim());
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("addForm".equals(action)) {
                String formId = req.getParameter("formId");
                if (formId != null && !formId.trim().isEmpty()) {
                    addFormToProject(conn, projectId, formId.trim(), currentUserName());
                    ActivityLog.record(conn, "project.form.add", "projet", String.valueOf(projectId),
                            "Formulaire ajouté : " + formId.trim());
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("removeForm".equals(action)) {
                String formId = req.getParameter("formId");
                if (formId != null) {
                    removeFormFromProject(conn, projectId, formId);
                    ActivityLog.record(conn, "project.form.remove", "projet", String.valueOf(projectId),
                            "Formulaire retiré : " + formId);
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("addCollector".equals(action)) {
                String username = req.getParameter("username");
                if (username != null && !username.trim().isEmpty()) {
                    addCollectorToProject(conn, projectId, username.trim(), currentUserName());
                    ActivityLog.record(conn, "project.user.add", "projet", String.valueOf(projectId),
                            "Utilisateur affecté : " + username.trim());
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("removeCollector".equals(action)) {
                String username = req.getParameter("username");
                if (username != null) {
                    removeCollectorFromProject(conn, projectId, username);
                    ActivityLog.record(conn, "project.user.remove", "projet", String.valueOf(projectId),
                            "Utilisateur retiré : " + username);
                }
                resp.sendRedirect("project?projectId=" + projectId);
            } else if ("importXml".equals(action)) {
                handleImportXml(conn, req, resp, project);
            } else if ("deleteConfirm".equals(action)) {
                int count = softDeleteProject(conn, projectId, currentUserName());
                ActivityLog.record(conn, "project.delete", "projet", String.valueOf(projectId),
                        "Mis à la corbeille : " + project.name + (count > 1 ? " (+ " + (count - 1) + " sous-projet(s))" : ""));
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
        String sql = "SELECT p.id, p.name, p.description, p.created_at, COUNT(f.id), p.parent_id "
                + "FROM odk_prod.app_project p "
                + "LEFT JOIN odk_prod.app_project_form f ON f.project_id = p.id "
                + "WHERE p.deleted = false "
                + "GROUP BY p.id, p.name, p.description, p.created_at, p.parent_id "
                + "ORDER BY p.name";
        List<ProjectSummary> result = new ArrayList<ProjectSummary>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int parentId = rs.getInt(6);
                result.add(new ProjectSummary(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4), rs.getInt(5), rs.wasNull() ? null : Integer.valueOf(parentId)));
            }
        }
        return result;
    }

    private static Project getProject(Connection conn, int id) throws SQLException {
        String sql = "SELECT id, name, description, parent_id FROM odk_prod.app_project WHERE id = ? AND deleted = false";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int parentId = rs.getInt(4);
                    return new Project(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.wasNull() ? null : Integer.valueOf(parentId));
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

    private static int insertProject(Connection conn, String name, String description, String creator, Integer parentId)
            throws SQLException {
        String sql = "INSERT INTO odk_prod.app_project(name, description, created_by, parent_id) VALUES (?,?,?,?) RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, creator);
            if (parentId != null) {
                ps.setInt(4, parentId);
            } else {
                ps.setNull(4, java.sql.Types.INTEGER);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Les sous-projets directs d'un projet (pas les petits-enfants). */
    private static List<ProjectSummary> listChildProjects(Connection conn, int projectId) throws SQLException {
        String sql = "SELECT p.id, p.name, p.description, p.created_at, COUNT(f.id), p.parent_id "
                + "FROM odk_prod.app_project p "
                + "LEFT JOIN odk_prod.app_project_form f ON f.project_id = p.id "
                + "WHERE p.parent_id = ? AND p.deleted = false "
                + "GROUP BY p.id, p.name, p.description, p.created_at, p.parent_id "
                + "ORDER BY p.name";
        List<ProjectSummary> result = new ArrayList<ProjectSummary>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int parentId = rs.getInt(6);
                    result.add(new ProjectSummary(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getTimestamp(4), rs.getInt(5), rs.wasNull() ? null : Integer.valueOf(parentId)));
                }
            }
        }
        return result;
    }

    /** La lignée d'un projet, de la racine jusqu'à son parent direct (pas le projet lui-même) - pour le fil d'Ariane. */
    private List<Project> ancestorChain(Connection conn, Project project) throws SQLException {
        List<Project> chain = new ArrayList<Project>();
        Integer pid = project.parentId;
        Set<Integer> seen = new java.util.HashSet<Integer>();
        while (pid != null && seen.add(pid)) {
            Project ancestor = getProject(conn, pid);
            if (ancestor == null) {
                break;
            }
            chain.add(0, ancestor);
            pid = ancestor.parentId;
        }
        return chain;
    }

    private static void updateProject(Connection conn, int id, String name, String description, Integer parentId)
            throws SQLException {
        String sql = "UPDATE odk_prod.app_project SET name = ?, description = ?, parent_id = ?, updated_at = now() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            if (parentId != null) {
                ps.setInt(3, parentId);
            } else {
                ps.setNull(3, java.sql.Types.INTEGER);
            }
            ps.setInt(4, id);
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

    /**
     * Comptes pas encore affectés à ce projet, parmi ceux pour qui l'affectation a un effet réel :
     * GROUP_DATA_COLLECTORS (filtre les formulaires visibles sur ODK Collect) ou GROUP_DATA_VIEWERS
     * (restreint, côté interface web, les projets/formulaires/soumissions visibles - voir
     * FormSchemaUtil.isFormAccessibleToViewer/isProjectAccessibleToViewer).
     */
    private static List<String> listAddableCollectors(Connection conn, int projectId, CallingContext cc) throws Exception {
        List<String> assigned = listAssignedCollectors(conn, projectId);
        ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
        List<String> addable = new ArrayList<String>();
        for (UserSecurityInfo u : all) {
            if (u.getAssignedUserGroups() != null
                    && (u.getAssignedUserGroups().contains(GrantedAuthorityName.GROUP_DATA_COLLECTORS)
                            || u.getAssignedUserGroups().contains(GrantedAuthorityName.GROUP_DATA_VIEWERS))
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

    // ---- Suppression logique (corbeille) -------------------------------------------------------

    /**
     * Sous-arbre d'un projet (lui-même + descendants), y compris les projets déjà supprimés.
     * UNION (et non UNION ALL) déduplique, ce qui garantit la terminaison même en cas de cycle.
     */
    private static final String SUBTREE_CTE = "WITH RECURSIVE tree AS ("
            + "SELECT id FROM odk_prod.app_project WHERE id = ? "
            + "UNION SELECT p.id FROM odk_prod.app_project p JOIN tree t ON p.parent_id = t.id) ";

    /**
     * Met le projet et tous ses sous-projets actifs à la corbeille (deleted = true). Ils partagent
     * le même deleted_at (now() est constant dans une instruction), ce qui permet ensuite de les
     * restaurer ensemble. Les rattachements formulaires/utilisateurs sont conservés tels quels.
     * Retourne le nombre de projets mis à la corbeille.
     */
    private static int softDeleteProject(Connection conn, int id, String deletedBy) throws SQLException {
        String sql = SUBTREE_CTE + "UPDATE odk_prod.app_project SET deleted = true, deleted_at = now(), deleted_by = ? "
                + "WHERE id IN (SELECT id FROM tree) AND deleted = false";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, deletedBy);
            return ps.executeUpdate();
        }
    }

    /** Un projet de la corbeille (deleted = true). */
    private static class DeletedProject {
        final int id;
        final String name;
        final Integer parentId;
        final boolean parentDeleted;
        final String parentName;
        final Timestamp deletedAt;
        final String deletedBy;
        final int subProjectCount;

        DeletedProject(int id, String name, Integer parentId, boolean parentDeleted, String parentName,
                Timestamp deletedAt, String deletedBy, int subProjectCount) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
            this.parentDeleted = parentDeleted;
            this.parentName = parentName;
            this.deletedAt = deletedAt;
            this.deletedBy = deletedBy;
            this.subProjectCount = subProjectCount;
        }
    }

    /**
     * Les suppressions "racines" de la corbeille : un projet supprimé en même temps que son parent
     * (même deleted_at) est masqué, il sera restauré / purgé avec lui. subProjectCount compte ces
     * sous-projets entraînés (approximation : enfants directs et indirects au même deleted_at).
     */
    private static List<DeletedProject> listDeletedProjects(Connection conn) throws SQLException {
        String sql = "SELECT p.id, p.name, p.parent_id, COALESCE(pp.deleted, false), pp.name, p.deleted_at, p.deleted_by, "
                + "(WITH RECURSIVE tree AS (SELECT c.id FROM odk_prod.app_project c WHERE c.parent_id = p.id "
                + "   AND c.deleted = true AND c.deleted_at = p.deleted_at "
                + " UNION SELECT c.id FROM odk_prod.app_project c JOIN tree t ON c.parent_id = t.id "
                + "   WHERE c.deleted = true AND c.deleted_at = p.deleted_at) SELECT COUNT(*) FROM tree) "
                + "FROM odk_prod.app_project p "
                + "LEFT JOIN odk_prod.app_project pp ON pp.id = p.parent_id "
                + "WHERE p.deleted = true "
                + "AND NOT (pp.deleted IS TRUE AND pp.deleted_at = p.deleted_at) "
                + "ORDER BY p.deleted_at DESC, p.name";
        List<DeletedProject> result = new ArrayList<DeletedProject>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int parentId = rs.getInt(3);
                Integer parent = rs.wasNull() ? null : Integer.valueOf(parentId);
                result.add(new DeletedProject(rs.getInt(1), rs.getString(2), parent, rs.getBoolean(4),
                        rs.getString(5), rs.getTimestamp(6), rs.getString(7), rs.getInt(8)));
            }
        }
        return result;
    }

    private static DeletedProject getDeletedProject(Connection conn, int id) throws SQLException {
        for (DeletedProject d : listDeletedProjects(conn)) {
            if (d.id == id) {
                return d;
            }
        }
        return null;
    }

    /** Restaure le projet et les sous-projets supprimés en même temps que lui (même deleted_at). */
    private static int restoreProject(Connection conn, int id) throws SQLException {
        String sql = SUBTREE_CTE + "UPDATE odk_prod.app_project SET deleted = false, deleted_at = NULL, deleted_by = NULL, "
                + "updated_at = now() WHERE id IN (SELECT id FROM tree) AND deleted = true "
                + "AND deleted_at = (SELECT deleted_at FROM odk_prod.app_project WHERE id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setInt(2, id);
            return ps.executeUpdate();
        }
    }

    /**
     * Suppression définitive (vrai DELETE) du projet et de ses sous-projets eux aussi à la corbeille.
     * Les rattachements app_project_form / app_project_user partent avec (ON DELETE CASCADE) ;
     * les formulaires ODK et leurs soumissions ne sont pas touchés.
     */
    private static int purgeProject(Connection conn, int id) throws SQLException {
        String sql = SUBTREE_CTE + "DELETE FROM odk_prod.app_project WHERE id IN (SELECT id FROM tree) AND deleted = true";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate();
        }
    }

    private void handleTrashAction(Connection conn, HttpServletResponse resp, String action, int projectId)
            throws SQLException, IOException {
        DeletedProject deleted = getDeletedProject(conn, projectId);
        if (deleted == null) {
            resp.sendRedirect("project?action=trash&error=" + urlEncode("Projet introuvable dans la corbeille."));
            return;
        }
        if ("restore".equals(action)) {
            if (deleted.parentDeleted) {
                resp.sendRedirect("project?action=trash&error=" + urlEncode("Le projet parent « " + deleted.parentName
                        + " » est lui aussi dans la corbeille : restaurez-le d'abord."));
                return;
            }
            int count = restoreProject(conn, projectId);
            ActivityLog.record(conn, "project.restore", "projet", String.valueOf(projectId),
                    "Restauré : " + deleted.name + (count > 1 ? " (+ " + (count - 1) + " sous-projet(s))" : ""));
            resp.sendRedirect("project?projectId=" + projectId);
        } else {
            int count = purgeProject(conn, projectId);
            ActivityLog.record(conn, "project.purge", "projet", String.valueOf(projectId),
                    "Supprimé définitivement : " + deleted.name + (count > 1 ? " (+ " + (count - 1) + " sous-projet(s))" : ""));
            resp.sendRedirect("project?action=trash&notice=" + urlEncode("Projet « " + deleted.name
                    + " » supprimé définitivement."));
        }
    }

    private void renderTrash(Connection conn, PrintWriter out, String error, String notice) throws SQLException {
        writeHeader(out, "Corbeille des projets");
        out.println("<p class='back-link'><a href='project'>&laquo; Liste des projets</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        out.println("<p class='info'>Un projet supprimé est conservé ici avec ses formulaires et utilisateurs rattachés. "
                + "<strong>Restaurer</strong> le remet en place tel quel (avec les sous-projets supprimés en même temps). "
                + "<strong>Supprimer définitivement</strong> efface le projet et ses rattachements ; les formulaires ODK "
                + "et leurs soumissions ne sont jamais touchés.</p>");
        List<DeletedProject> items = listDeletedProjects(conn);
        if (items.isEmpty()) {
            out.println("<p class='info'>La corbeille est vide.</p>");
            writeFooter(out);
            return;
        }
        out.println("<div class='table-enhance'>");
        out.println("<input type='text' class='table-search-input' placeholder='Rechercher un projet...'>");
        out.println("<table><thead><tr><th data-sortable>Projet</th><th data-sortable>Supprimé le</th>"
                + "<th data-sortable>Par</th><th></th></tr></thead><tbody>");
        for (DeletedProject d : items) {
            String deletedBy = d.deletedBy == null ? "" : FormSchemaUtil.extractLocalUsername(d.deletedBy);
            out.println("<tr><td><strong>" + htmlEscape(d.name) + "</strong>"
                    + (d.subProjectCount > 0 ? "<div class='muted'>+ " + d.subProjectCount + " sous-projet(s)</div>" : "")
                    + (d.parentDeleted ? "<div class='muted'>Parent « " + htmlEscape(d.parentName)
                            + " » aussi dans la corbeille</div>" : "")
                    + "</td>");
            out.println("<td>" + htmlEscape(d.deletedAt == null ? "" : d.deletedAt.toString()) + "</td>");
            out.println("<td>" + htmlEscape(deletedBy) + "</td>");
            out.println("<td><div class='row-actions'>"
                    + "<form class='inline' method='post' action='project?projectId=" + d.id + "&action=restore'>"
                    + "<button type='submit' class='btn-link'" + (d.parentDeleted ? " disabled" : "") + ">Restaurer</button></form>"
                    + "<form class='inline' method='post' action='project?projectId=" + d.id + "&action=purge' "
                    + "onsubmit=\"return confirm('Supprimer définitivement ce projet ? Cette action est irréversible.');\">"
                    + "<button type='submit' class='btn-link danger'>" + ICON_TRASH + " Supprimer définitivement</button></form>"
                    + "</div></td></tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");
        writeFooter(out);
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
                ActivityLog.record(conn, "form.import", "formulaire", newFormId,
                        "Importé et rattaché au projet " + project.name);
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

    private void renderProjectList(Connection conn, PrintWriter out, boolean canManage) throws SQLException {
        writeHeader(out, "Projets");
        if (canManage) {
            out.println("<div class='toolbar'><a class='btn-link' href='project?action=new'>" + ICON_PLUS + " Nouveau projet</a>"
                    + "<a class='btn-link' href='project?action=trash'>" + ICON_TRASH + " Corbeille</a></div>");
        }
        List<ProjectSummary> projects = listProjects(conn);
        if (!canManage) {
            Set<Integer> visible = FormSchemaUtil.listProjectsVisibleToViewer(conn, FormSchemaUtil.currentLocalUsername());
            List<ProjectSummary> filtered = new ArrayList<ProjectSummary>();
            for (ProjectSummary p : projects) {
                if (visible.contains(p.id)) {
                    filtered.add(p);
                }
            }
            projects = filtered;
        }
        if (projects.isEmpty()) {
            out.println("<p class='info'>" + (canManage ? "Aucun projet pour le moment."
                    : "Aucun projet visible : vous n'êtes affecté à aucun projet.") + "</p>");
        } else {
            Map<Integer, ProjectSummary> byId = new LinkedHashMap<Integer, ProjectSummary>();
            for (ProjectSummary p : projects) {
                byId.put(p.id, p);
            }
            Map<Integer, List<ProjectSummary>> childrenOf = new LinkedHashMap<Integer, List<ProjectSummary>>();
            List<ProjectSummary> roots = new ArrayList<ProjectSummary>();
            for (ProjectSummary p : projects) {
                if (p.parentId != null && byId.containsKey(p.parentId)) {
                    List<ProjectSummary> siblings = childrenOf.get(p.parentId);
                    if (siblings == null) {
                        siblings = new ArrayList<ProjectSummary>();
                        childrenOf.put(p.parentId, siblings);
                    }
                    siblings.add(p);
                } else {
                    roots.add(p);
                }
            }
            out.println("<ul class='list'>");
            for (ProjectSummary root : roots) {
                renderProjectTreeItem(out, root, childrenOf, 0);
            }
            out.println("</ul>");
        }
        writeFooter(out);
    }

    /** Affiche un projet et récursivement ses sous-projets, indentés, dans le <ul class='list'> de la liste des projets. */
    private void renderProjectTreeItem(PrintWriter out, ProjectSummary p, Map<Integer, List<ProjectSummary>> childrenOf,
            int depth) {
        out.println("<li style='" + (depth > 0 ? "margin-left:" + (depth * 22) + "px;" : "") + "'>"
                + "<a href='project?projectId=" + p.id + "'><strong>" + htmlEscape(p.name) + "</strong>"
                + "<div class='muted'>" + p.formCount + " formulaire(s)"
                + (p.description != null && !p.description.trim().isEmpty()
                        ? " &middot; " + htmlEscape(p.description) : "")
                + "</div></a></li>");
        List<ProjectSummary> kids = childrenOf.get(p.id);
        if (kids != null) {
            for (ProjectSummary k : kids) {
                renderProjectTreeItem(out, k, childrenOf, depth + 1);
            }
        }
    }

    private void renderProjectForm(Connection conn, PrintWriter out, Project project, String error, Integer selectedParentId)
            throws SQLException {
        boolean editing = project != null;
        writeHeader(out, editing ? "Modifier le projet" : "Nouveau projet");
        out.println("<p class='back-link'><a href='project" + (editing ? "?projectId=" + project.id : "")
                + "'>&laquo; " + (editing ? "Retour au projet" : "Liste des projets") + "</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }

        // Projets parents possibles : tous sauf, en édition, ce projet lui-même et ses propres
        // sous-projets (empêcherait de créer un cycle dans l'arbre).
        Set<Integer> excluded = editing ? FormSchemaUtil.descendantProjectIds(conn, project.id) : java.util.Collections.<Integer>emptySet();
        List<ProjectSummary> candidateParents = new ArrayList<ProjectSummary>();
        for (ProjectSummary p : listProjects(conn)) {
            if (!excluded.contains(p.id)) {
                candidateParents.add(p);
            }
        }

        out.println("<div class='card'>");
        out.println("<form method='post' action='project?" + (editing ? "projectId=" + project.id + "&action=update" : "action=create") + "'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Nom</label><input type='text' name='name' value='"
                + htmlEscape(editing ? project.name : "") + "' required>");
        out.println("<label>Description</label><textarea name='description'>"
                + htmlEscape(editing && project.description != null ? project.description : "") + "</textarea>");
        out.println("<label>Projet parent</label><select name='parentId'>");
        out.println("<option value=''" + (selectedParentId == null ? " selected" : "") + ">— Aucun (projet racine) —</option>");
        for (ProjectSummary p : candidateParents) {
            boolean sel = selectedParentId != null && selectedParentId == p.id;
            out.println("<option value='" + p.id + "'" + (sel ? " selected" : "") + ">" + htmlEscape(p.name) + "</option>");
        }
        out.println("</select>");
        out.println("</div>");
        out.println("<p class='info'>Un sous-projet hérite des formulaires et utilisateurs affectés de sa lignée de "
                + "projets parents ; le projet parent, lui, agrège automatiquement les soumissions/graphiques/médias de "
                + "tous ses sous-projets.</p>");
        out.println("<div class='actions'><button type='submit'>" + (editing ? "Enregistrer" : ICON_PLUS + " Créer le projet")
                + "</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void renderProjectDetail(Connection conn, PrintWriter out, Project project, String error, String notice,
            boolean canManage) throws SQLException {
        writeHeader(out, project.name);
        out.println("<p class='back-link'><a href='project'>&laquo; Liste des projets</a></p>");

        List<Project> ancestors = ancestorChain(conn, project);
        if (!ancestors.isEmpty()) {
            StringBuilder crumb = new StringBuilder();
            for (Project a : ancestors) {
                crumb.append("<a href='project?projectId=").append(a.id).append("'>").append(htmlEscape(a.name))
                        .append("</a> &raquo; ");
            }
            out.println("<p class='info'>" + crumb + htmlEscape(project.name) + "</p>");
        }
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        if (project.description != null && !project.description.trim().isEmpty()) {
            out.println("<p class='info'>" + htmlEscape(project.description) + "</p>");
        }
        if (canManage) {
            out.println("<div class='toolbar'>");
            out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=edit'>" + ICON_EDIT + " Modifier</a>");
            out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=addForm'>" + ICON_PLUS
                    + " Ajouter un formulaire</a>");
            out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=importXml'>" + ICON_UPLOAD
                    + " Importer un formulaire XML</a>");
            out.println("<a class='btn-link' href='project?projectId=" + project.id + "&action=addCollector'>" + ICON_USERS
                    + " Affecter un utilisateur</a>");
            out.println("<a class='btn-link' href='project?action=new&parentId=" + project.id + "'>" + ICON_PLUS
                    + " Créer un sous-projet</a>");
            out.println("<a class='btn-link danger' href='project?projectId=" + project.id + "&action=delete'>" + ICON_TRASH
                    + " Supprimer le projet</a>");
            out.println("</div>");
        }

        List<ProjectSummary> children = listChildProjects(conn, project.id);
        if (!children.isEmpty()) {
            out.println("<h2 style='margin-top:24px;'>Sous-projets</h2>");
            out.println("<div class='table-enhance'>");
            out.println("<input type='text' class='table-search-input' placeholder='Rechercher un sous-projet...'>");
            out.println("<table><thead><tr><th data-sortable>Nom</th><th data-sortable>Formulaires</th></tr></thead><tbody>");
            for (ProjectSummary c : children) {
                out.println("<tr><td><a href='project?projectId=" + c.id + "'>" + htmlEscape(c.name) + "</a></td>"
                        + "<td>" + c.formCount + "</td></tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }

        // Ensemble agrégé (ce projet + tous ses sous-projets, récursivement) : utilisé pour les
        // graphiques et le suivi des enquêteurs, qui doivent donner une vue d'ensemble complète.
        Set<Integer> aggregateProjectIds = FormSchemaUtil.descendantProjectIds(conn, project.id);
        boolean hasSubProjects = aggregateProjectIds.size() > 1;
        List<String> aggregatedFormIds = new ArrayList<String>();
        java.util.LinkedHashSet<String> aggregatedFormIdSet = new java.util.LinkedHashSet<String>();
        for (Integer pid : aggregateProjectIds) {
            aggregatedFormIdSet.addAll(listAttachedForms(conn, pid));
        }
        aggregatedFormIds.addAll(aggregatedFormIdSet);

        if (!aggregatedFormIds.isEmpty()) {
            LinkedHashMap<String, Long> formCounts = new LinkedHashMap<String, Long>();
            for (String formId : aggregatedFormIds) {
                try {
                    formCounts.put(formId, FormSchemaUtil.submissionStats(conn, formId).count);
                } catch (SQLException e) {
                    // Formulaire introuvable (peut-être supprimé) - ignoré pour ce graphique.
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
                ChartUtil.renderHorizontalBarChart(out,
                        hasSubProjects ? "Soumissions par formulaire (ce projet + sous-projets)" : "Soumissions par formulaire",
                        formCounts);
                out.println("</div>");
            }
        }

        // Formulaires rattachés directement a ce projet (gestion : ajout/retrait). Les graphiques
        // ci-dessus et le suivi des enquêteurs, plus bas, agrègent en plus ceux des sous-projets.
        List<String> formIds = listAttachedForms(conn, project.id);
        if (formIds.isEmpty()) {
            out.println("<p class='info'>Aucun formulaire rattaché directement à ce projet.</p>");
        } else {
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
                        + (canManage
                                ? "<form class='inline' method='post' action='project?projectId=" + project.id + "&action=removeForm'>"
                                        + "<input type='hidden' name='formId' value='" + htmlEscape(formId) + "'>"
                                        + "<button type='submit' class='btn-link danger'>" + ICON_TRASH + " Retirer</button></form>"
                                : "")
                        + "</div></td>");
                out.println("</tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }

        out.println("<h2 style='margin-top:32px;'>Utilisateurs affectés</h2>");
        out.println("<p class='info'>Un <strong>collecteur</strong> affecté à ce projet ne verra, sur son téléphone, que les "
                + "formulaires listés ci-dessus. Un <strong>lecteur de données</strong> affecté à ce projet voit ce projet, "
                + "sa lignée de projets parents (hérité) et tous ses sous-projets (agrégé). Un compte non affecté à aucun "
                + "projet ne verra rien.</p>");
        List<String> collectors = listAssignedCollectors(conn, project.id);
        Set<String> collectorRoleUsernames = FormSchemaUtil.listUsernamesWithAuthority(conn, "GROUP_DATA_COLLECTORS");
        if (collectors.isEmpty()) {
            out.println("<p class='info'>Aucun utilisateur affecté à ce projet.</p>");
        } else {
            out.println("<div class='table-enhance'>");
            out.println("<input type='text' class='table-search-input' placeholder='Rechercher un agent...'>");
            out.println("<table><thead><tr><th data-sortable>Nom d'utilisateur</th><th data-sortable>Rôle</th><th></th></tr></thead><tbody>");
            for (String username : collectors) {
                out.println("<tr><td>" + htmlEscape(username) + "</td><td>"
                        + (collectorRoleUsernames.contains(username) ? "Collecteur de données" : "Lecteur de données")
                        + "</td><td>"
                        + (canManage
                                ? "<form class='inline' method='post' action='project?projectId=" + project.id + "&action=removeCollector'>"
                                        + "<input type='hidden' name='username' value='" + htmlEscape(username) + "'>"
                                        + "<button type='submit' class='btn-link danger'>" + ICON_TRASH + " Retirer</button></form>"
                                : "")
                        + "</td></tr>");
            }
            out.println("</tbody></table>");
            out.println("</div>");
        }

        // Suivi des enquêteurs agrégé : formulaires ET utilisateurs affectés de ce projet + tous ses sous-projets.
        java.util.LinkedHashSet<String> aggregatedUsers = new java.util.LinkedHashSet<String>();
        for (Integer pid : aggregateProjectIds) {
            aggregatedUsers.addAll(listAssignedCollectors(conn, pid));
        }
        renderEnqueteurTracking(conn, out, aggregatedFormIds, new ArrayList<String>(aggregatedUsers), collectorRoleUsernames);
        writeFooter(out);
    }

    /**
     * Suivi des enquêteurs par projet : pour chaque agent de collecte affecté (parmi les
     * utilisateurs affectés, en excluant les lecteurs de données), compte ses soumissions sur tous
     * les formulaires du projet. Identifié par le compte de connexion ODK Collect (_CREATOR_URI_USER,
     * au format "uid:&lt;username&gt;|&lt;horodatage&gt;" - stable par compte, voir
     * FormSchemaUtil.extractLocalUsername), pas par un champ de formulaire.
     */
    private void renderEnqueteurTracking(Connection conn, PrintWriter out, List<String> formIds,
            List<String> assignedUsers, Set<String> collectorRoleUsernames) throws SQLException {
        out.println("<h2 style='margin-top:32px;'>Suivi des enquêteurs</h2>");
        out.println("<p class='info'>Nombre de soumissions envoyées par chaque agent de collecte affecté à ce projet, "
                + "tous formulaires du projet confondus (identifié via son compte de connexion ODK Collect).</p>");

        List<String> enqueteurs = new ArrayList<String>();
        for (String u : assignedUsers) {
            if (collectorRoleUsernames.contains(u)) {
                enqueteurs.add(u);
            }
        }
        if (enqueteurs.isEmpty()) {
            out.println("<p class='info'>Aucun agent de collecte affecté à ce projet.</p>");
            return;
        }
        if (formIds.isEmpty()) {
            out.println("<p class='info'>Aucun formulaire rattaché à ce projet.</p>");
            return;
        }

        LinkedHashMap<String, Long> countByEnqueteur = new LinkedHashMap<String, Long>();
        java.util.Map<String, Timestamp> lastByEnqueteur = new java.util.HashMap<String, Timestamp>();
        for (String u : enqueteurs) {
            countByEnqueteur.put(u, 0L);
        }
        for (String formId : formIds) {
            String coreTable;
            try {
                coreTable = FormSchemaUtil.resolveCoreTableOrThrow(conn, formId);
            } catch (SQLException e) {
                continue;
            }
            for (String u : enqueteurs) {
                String pattern = "uid:" + u + "|%";
                try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*), MAX(\"_SUBMISSION_DATE\") "
                        + "FROM odk_prod.\"" + coreTable + "\" WHERE \"_CREATOR_URI_USER\" LIKE ? AND "
                        + FormSchemaUtil.CORE_NOT_DELETED)) {
                    ps.setString(1, pattern);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            countByEnqueteur.put(u, countByEnqueteur.get(u) + rs.getLong(1));
                            Timestamp last = rs.getTimestamp(2);
                            if (last != null) {
                                Timestamp prev = lastByEnqueteur.get(u);
                                if (prev == null || last.after(prev)) {
                                    lastByEnqueteur.put(u, last);
                                }
                            }
                        }
                    }
                } catch (SQLException e) {
                    // Table introuvable (formulaire peut-être supprimé) - ignorée pour ce comptage.
                }
            }
        }

        boolean anySubmissions = false;
        for (Long v : countByEnqueteur.values()) {
            if (v != null && v > 0) {
                anySubmissions = true;
                break;
            }
        }
        if (anySubmissions) {
            out.println("<div class='charts-grid'>");
            ChartUtil.renderHorizontalBarChart(out, "Soumissions par enquêteur", countByEnqueteur);
            out.println("</div>");
        }

        out.println("<div class='table-enhance'>");
        out.println("<input type='text' class='table-search-input' placeholder='Rechercher un enquêteur...'>");
        out.println("<table><thead><tr><th data-sortable>Enquêteur</th><th data-sortable>Soumissions</th>"
                + "<th data-sortable>Dernière soumission</th></tr></thead><tbody>");
        for (String u : enqueteurs) {
            Timestamp last = lastByEnqueteur.get(u);
            out.println("<tr><td>" + htmlEscape(u) + "</td><td>" + countByEnqueteur.get(u) + "</td><td>"
                    + htmlEscape(last == null ? "" : last.toString()) + "</td></tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");
    }

    private void renderAddCollectorPicker(Connection conn, PrintWriter out, Project project, CallingContext cc)
            throws SQLException {
        writeHeader(out, "Affecter un utilisateur — " + project.name);
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
            out.println("<p class='info'>Aucun utilisateur disponible à affecter "
                    + "(tous déjà affectés, ou aucun compte avec le rôle Collecteur de données ou Lecteur de données).</p>");
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
        int subProjectCount = FormSchemaUtil.descendantProjectIds(conn, project.id).size() - 1;
        out.println("<div class='card'>");
        out.println("<p>Voulez-vous vraiment supprimer le projet <strong>" + htmlEscape(project.name) + "</strong>"
                + (subProjectCount > 0 ? " et ses " + subProjectCount + " sous-projet(s)" : "") + " ? "
                + "Il sera placé dans la <a href='project?action=trash'>corbeille</a>, d'où vous pourrez le restaurer "
                + "avec ses formulaires et utilisateurs rattachés. Les soumissions ne sont pas touchées.</p>");
        out.println("<form method='post' action='project?projectId=" + project.id + "&action=deleteConfirm'>");
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Mettre à la corbeille</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }
}
