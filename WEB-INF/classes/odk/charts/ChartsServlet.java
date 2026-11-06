package odk.charts;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.ChartUtil;
import odk.common.FormSchemaUtil;
import odk.common.NavBar;

/**
 * Onglet /charts (section "graphiques", ROLE_DATA_VIEWER comme /project) : on choisit un projet et
 * les graphiques de ce projet s'affichent - tendance des soumissions, répartition par formulaire,
 * répartition par enquêteur. Réutilise les mêmes requêtes/graphiques que ProjectServlet (regroupés
 * ici pour une vue dédiée), et respecte la même restriction par projet affecté pour un lecteur de
 * données (FormSchemaUtil.listProjectsVisibleToViewer/isProjectAccessibleToViewer).
 */
public class ChartsServlet extends HttpServlet {

    private static final int TREND_DAYS = 30;

    private static class ProjectOption {
        final int id;
        final String name;

        ProjectOption(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final String ICON_MAP =
            "<svg viewBox='0 0 24 24'><path d='M12 21s7-6.5 7-11a7 7 0 1 0-14 0c0 4.5 7 11 7 11z'/><circle cx='12' cy='10' r='2.4'/></svg>";
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

    private boolean isDataOwner() {
        return FormSchemaUtil.currentUserHasRole("ROLE_DATA_OWNER", getServletContext());
    }

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), "graphiques");
        out.println("<div class='container'>");
        out.println("<h2>" + htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Integer projectId = null;
        try {
            String raw = req.getParameter("projectId");
            if (raw != null && !raw.trim().isEmpty()) {
                projectId = Integer.valueOf(raw.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        String formId = req.getParameter("formId");
        if (formId != null && formId.trim().isEmpty()) {
            formId = null;
        }
        try (Connection conn = getDataSource().getConnection()) {
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            writeHeader(out, "Graphiques");

            boolean canManage = isDataOwner();
            List<ProjectOption> projects = listSelectableProjects(conn, canManage);

            out.println("<p class='info'>Choisissez un projet pour afficher ses graphiques.</p>");
            renderProjectPicker(out, projects, projectId);

            if (projectId != null) {
                ProjectOption selected = null;
                for (ProjectOption p : projects) {
                    if (p.id == projectId) {
                        selected = p;
                        break;
                    }
                }
                if (selected == null) {
                    out.println("<p class='err'>Projet introuvable ou non accessible.</p>");
                } else {
                    renderProjectCharts(conn, out, selected, formId);
                }
            } else if (projects.isEmpty()) {
                out.println("<p class='info'>" + (canManage ? "Aucun projet pour le moment."
                        : "Aucun projet visible : vous n'êtes affecté à aucun projet.") + "</p>");
            }
            writeFooter(out);
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private List<ProjectOption> listSelectableProjects(Connection conn, boolean canManage) throws SQLException {
        List<ProjectOption> all = new ArrayList<ProjectOption>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM odk_prod.app_project WHERE deleted = false ORDER BY name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.add(new ProjectOption(rs.getInt(1), rs.getString(2)));
            }
        }
        if (canManage) {
            return all;
        }
        Set<Integer> visible = FormSchemaUtil.listProjectsVisibleToViewer(conn, FormSchemaUtil.currentLocalUsername());
        List<ProjectOption> filtered = new ArrayList<ProjectOption>();
        for (ProjectOption p : all) {
            if (visible.contains(p.id)) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    private void renderProjectPicker(PrintWriter out, List<ProjectOption> projects, Integer selectedId) {
        if (projects.isEmpty()) {
            return;
        }
        out.println("<form method='get' action='charts' class='card' style='display:flex;gap:10px;align-items:center;"
                + "flex-wrap:wrap;'>");
        out.println("<label for='projectId' style='font-weight:600;'>Projet</label>");
        out.println("<select name='projectId' id='projectId'>");
        out.println("<option value=''>— Choisir —</option>");
        for (ProjectOption p : projects) {
            boolean sel = selectedId != null && selectedId == p.id;
            out.println("<option value='" + p.id + "'" + (sel ? " selected" : "") + ">" + htmlEscape(p.name) + "</option>");
        }
        out.println("</select>");
        out.println("<button type='submit'>Afficher</button>");
        out.println("</form>");
    }

    private void renderProjectCharts(Connection conn, PrintWriter out, ProjectOption project, String selectedFormId)
            throws SQLException {
        // Agrège ce projet et tous ses sous-projets (récursivement) - un sous-projet vient avec ses
        // propres formulaires, additionnés à ceux du projet choisi.
        Set<Integer> aggregateProjectIds = FormSchemaUtil.descendantProjectIds(conn, project.id);
        java.util.LinkedHashSet<String> formIdSet = new java.util.LinkedHashSet<String>();
        for (Integer pid : aggregateProjectIds) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT form_id FROM odk_prod.app_project_form WHERE project_id = ? ORDER BY form_id")) {
                ps.setInt(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        formIdSet.add(rs.getString(1));
                    }
                }
            }
        }
        List<String> formIds = new ArrayList<String>(formIdSet);

        out.println("<h3 style='margin-top:24px;'>" + htmlEscape(project.name)
                + (aggregateProjectIds.size() > 1 ? " (+ sous-projets)" : "") + "</h3>");
        if (formIds.isEmpty()) {
            out.println("<p class='info'>Aucun formulaire rattaché à ce projet.</p>");
            return;
        }

        // ---- Tendance des soumissions (tous formulaires du projet, somme jour par jour) ----
        long[] totalDaily = new long[TREND_DAYS];
        LinkedHashMap<String, Long> countsByForm = new LinkedHashMap<String, Long>();
        for (String formId : formIds) {
            try {
                String coreTable = FormSchemaUtil.resolveCoreTableOrThrow(conn, formId);
                FormSchemaUtil.SubmissionStats stats = FormSchemaUtil.submissionStats(conn, formId);
                countsByForm.put(formId, stats.count);
                long[] daily = FormSchemaUtil.dailySubmissionCounts(conn, coreTable, TREND_DAYS);
                for (int i = 0; i < TREND_DAYS; i++) {
                    totalDaily[i] += daily[i];
                }
            } catch (SQLException e) {
                // Formulaire introuvable (peut-être supprimé) - ignoré pour ces graphiques.
            }
        }

        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        List<String> labels = new ArrayList<String>();
        labels.add(today.minusDays(TREND_DAYS - 1).format(fmt));
        labels.add(today.format(fmt));

        out.println("<div class='charts-grid'>");
        ChartUtil.renderLineChart(out, "Soumissions des " + TREND_DAYS + " derniers jours", labels, totalDaily);

        boolean anyFormSubmissions = false;
        for (Long v : countsByForm.values()) {
            if (v != null && v > 0) {
                anyFormSubmissions = true;
                break;
            }
        }
        if (anyFormSubmissions) {
            ChartUtil.renderHorizontalBarChart(out, "Soumissions par formulaire", countsByForm);
        }

        // ---- Soumissions par enquêteur (collecteurs affectés à ce projet ou l'un de ses sous-projets) ----
        List<String> enqueteurs = listAssignedEnqueteurs(conn, aggregateProjectIds);
        if (!enqueteurs.isEmpty()) {
            LinkedHashMap<String, Long> countByEnqueteur = countSubmissionsByEnqueteur(conn, formIds, enqueteurs);
            boolean anyEnqueteurSubmissions = false;
            for (Long v : countByEnqueteur.values()) {
                if (v != null && v > 0) {
                    anyEnqueteurSubmissions = true;
                    break;
                }
            }
            if (anyEnqueteurSubmissions) {
                ChartUtil.renderHorizontalBarChart(out, "Soumissions par enquêteur", countByEnqueteur);
            }
        }
        out.println("</div>");

        out.println("<p class='back-link'><a href='project?projectId=" + project.id + "'>Voir le détail du projet &raquo;</a></p>");

        // ---- Zoom sur un formulaire du projet : carte + graphiques propres a ce seul formulaire ----
        out.println("<h3 style='margin-top:32px;'>Zoom sur un formulaire</h3>");
        out.println("<form method='get' action='charts' class='card' style='display:flex;gap:10px;align-items:center;"
                + "flex-wrap:wrap;'>");
        out.println("<input type='hidden' name='projectId' value='" + project.id + "'>");
        out.println("<label for='formId' style='font-weight:600;'>Formulaire</label>");
        out.println("<select name='formId' id='formId'>");
        out.println("<option value=''>— Choisir —</option>");
        for (String formId : formIds) {
            boolean sel = formId.equals(selectedFormId);
            out.println("<option value='" + htmlEscape(formId) + "'" + (sel ? " selected" : "") + ">"
                    + htmlEscape(formId) + "</option>");
        }
        out.println("</select>");
        out.println("<button type='submit'>Afficher</button>");
        out.println("</form>");

        if (selectedFormId != null) {
            if (!formIds.contains(selectedFormId)) {
                out.println("<p class='err'>Ce formulaire n'est pas rattaché à ce projet.</p>");
            } else {
                renderFormCharts(conn, out, selectedFormId, enqueteurs);
            }
        }
    }

    /** Les LOCAL_USERNAME affectés à un projet (odk_prod.app_project_user) qui ont le rôle Collecteur de données. */
    private List<String> listAssignedEnqueteurs(Connection conn, Set<Integer> projectIds) throws SQLException {
        java.util.LinkedHashSet<String> assignedUsers = new java.util.LinkedHashSet<String>();
        for (Integer projectId : projectIds) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT username FROM odk_prod.app_project_user WHERE project_id = ? ORDER BY username")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assignedUsers.add(rs.getString(1));
                    }
                }
            }
        }
        Set<String> collectorRoleUsernames = FormSchemaUtil.listUsernamesWithAuthority(conn, "GROUP_DATA_COLLECTORS");
        List<String> enqueteurs = new ArrayList<String>();
        for (String u : assignedUsers) {
            if (collectorRoleUsernames.contains(u)) {
                enqueteurs.add(u);
            }
        }
        return enqueteurs;
    }

    /** Nombre de soumissions par enquêteur, sommées sur les formulaires donnés (identification par _CREATOR_URI_USER). */
    private LinkedHashMap<String, Long> countSubmissionsByEnqueteur(Connection conn, List<String> formIds,
            List<String> enqueteurs) {
        LinkedHashMap<String, Long> countByEnqueteur = new LinkedHashMap<String, Long>();
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
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM odk_prod.\"" + coreTable + "\" WHERE \"_CREATOR_URI_USER\" LIKE ? AND "
                                + FormSchemaUtil.CORE_NOT_DELETED)) {
                    ps.setString(1, "uid:" + u + "|%");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            countByEnqueteur.put(u, countByEnqueteur.get(u) + rs.getLong(1));
                        }
                    }
                } catch (SQLException e) {
                    // Table introuvable - ignorée pour ce comptage.
                }
            }
        }
        return countByEnqueteur;
    }

    /**
     * Graphiques propres a un seul formulaire du projet : tendance des 30 derniers jours,
     * répartition par enquêteur pour ce formulaire, et lien vers la carte (si le formulaire a un
     * champ de géolocalisation - géré par EditSubmissionServlet, pas dupliqué ici) et vers ses
     * soumissions.
     */
    private void renderFormCharts(Connection conn, PrintWriter out, String formId, List<String> enqueteurs)
            throws SQLException {
        out.println("<h4>" + htmlEscape(formId) + "</h4>");
        String coreTable;
        FormSchemaUtil.SubmissionStats stats;
        try {
            coreTable = FormSchemaUtil.resolveCoreTableOrThrow(conn, formId);
            stats = FormSchemaUtil.submissionStats(conn, formId);
        } catch (SQLException e) {
            out.println("<p class='err'>" + htmlEscape(e.getMessage()) + "</p>");
            return;
        }

        out.println("<div class='toolbar'>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "&action=map'>" + ICON_MAP
                + " Voir la carte</a>");
        out.println("<a class='btn-link' href='editSubmission?formId=" + urlEncode(formId) + "'>" + ICON_EDIT
                + " Voir les soumissions</a>");
        out.println("</div>");
        out.println("<p class='info'><strong>" + stats.count + "</strong> soumission(s)"
                + (stats.lastSubmissionDate != null ? ", dernière le " + htmlEscape(stats.lastSubmissionDate.toString()) : "")
                + ".</p>");

        long[] daily = FormSchemaUtil.dailySubmissionCounts(conn, coreTable, TREND_DAYS);
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        List<String> labels = new ArrayList<String>();
        labels.add(today.minusDays(TREND_DAYS - 1).format(fmt));
        labels.add(today.format(fmt));

        out.println("<div class='charts-grid'>");
        ChartUtil.renderLineChart(out, "Soumissions des " + TREND_DAYS + " derniers jours — " + formId, labels, daily);

        if (!enqueteurs.isEmpty()) {
            List<String> singleForm = new ArrayList<String>();
            singleForm.add(formId);
            LinkedHashMap<String, Long> countByEnqueteur = countSubmissionsByEnqueteur(conn, singleForm, enqueteurs);
            boolean any = false;
            for (Long v : countByEnqueteur.values()) {
                if (v != null && v > 0) {
                    any = true;
                    break;
                }
            }
            if (any) {
                ChartUtil.renderHorizontalBarChart(out, "Soumissions par enquêteur — " + formId, countByEnqueteur);
            }
        }
        out.println("</div>");
    }
}
