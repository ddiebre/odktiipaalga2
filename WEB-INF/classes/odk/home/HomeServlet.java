package odk.home;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.opendatakit.aggregate.ContextFactory;
import org.opendatakit.common.security.server.SecurityServiceUtil;
import org.opendatakit.common.web.CallingContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.ChartUtil;
import odk.common.FormSchemaUtil;
import odk.common.NavBar;

/**
 * Portail /home : tableau de bord central listant les outils internes et un
 * recapitulatif chiffre (projets, formulaires, utilisateurs). L'acces a
 * /home lui-meme est restreint a ROLE_DATA_OWNER (voir
 * applicationContext-security.xml) ; chaque MenuItem peut en plus exiger
 * un role supplementaire pour n'apparaitre qu'aux utilisateurs concernes -
 * la statistique "Utilisateurs" suit la meme regle (ROLE_SITE_ACCESS_ADMIN).
 */
public class HomeServlet extends HttpServlet {

    private static class MenuItem {
        final String title;
        final String description;
        final String url;
        final String requiredRole;
        final String icon;

        MenuItem(String title, String description, String url, String requiredRole, String icon) {
            this.title = title;
            this.description = description;
            this.url = url;
            this.requiredRole = requiredRole;
            this.icon = icon;
        }
    }

    private static final String ICON_EDIT =
            "<svg viewBox='0 0 24 24'><path d='M4 20h4L18.5 9.5a2.121 2.121 0 0 0-3-3L5 17v3z'/><path d='M13.5 6.5l4 4'/></svg>";
    private static final String ICON_FOLDER =
            "<svg viewBox='0 0 24 24'><path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z'/></svg>";
    private static final String ICON_USERS =
            "<svg viewBox='0 0 24 24'><circle cx='9' cy='8' r='3.2'/><path d='M3.5 20c0-3.3 2.6-6 6-6s6 2.7 6 6'/>"
            + "<circle cx='17.5' cy='9' r='2.6'/><path d='M15.8 14.3c2.6.4 4.7 2.6 4.7 5.7'/></svg>";
    private static final String ICON_SHIELD =
            "<svg viewBox='0 0 24 24'><path d='M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z'/><path d='M9 12l2 2 4-4'/></svg>";
    private static final String ICON_GRID =
            "<svg viewBox='0 0 24 24'><rect x='3' y='3' width='7' height='7' rx='1.2'/><rect x='14' y='3' width='7' height='7' rx='1.2'/>"
            + "<rect x='3' y='14' width='7' height='7' rx='1.2'/><rect x='14' y='14' width='7' height='7' rx='1.2'/></svg>";
    private static final String ICON_FILE =
            "<svg viewBox='0 0 24 24'><path d='M6 3h8l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z'/>"
            + "<path d='M14 3v4h4'/><path d='M8 13h8M8 17h8M8 9h3'/></svg>";
    private static final String ICON_UPLOAD =
            "<svg viewBox='0 0 24 24'><path d='M12 16V4'/><path d='M7 9l5-5 5 5'/><path d='M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3'/></svg>";

    private static final List<MenuItem> MENU_ITEMS = new ArrayList<MenuItem>();
    static {
        MENU_ITEMS.add(new MenuItem(
                "Ajouter un formulaire",
                "Importer un nouveau formulaire XML dans ODK.",
                "editSubmission?action=importForm",
                null,
                ICON_UPLOAD));
        MENU_ITEMS.add(new MenuItem(
                "Importer un XLSForm (Excel)",
                "Convertir un formulaire écrit en Excel, télécharger le XML et choisir de le déployer ou non.",
                "editSubmission?action=importXlsForm",
                null,
                ICON_UPLOAD));
        MENU_ITEMS.add(new MenuItem(
                "Modifier une soumission",
                "Consulter, corriger, importer (CSV) ou visualiser sur carte les soumissions d'un formulaire.",
                "editSubmission",
                null,
                ICON_EDIT));
        MENU_ITEMS.add(new MenuItem(
                "Projets",
                "Regrouper des formulaires par projet et suivre le nombre de soumissions de chacun.",
                "project",
                null,
                ICON_FOLDER));
        MENU_ITEMS.add(new MenuItem(
                "Gestion des utilisateurs",
                "Créer des comptes et gérer leurs rôles/permissions.",
                "manageUsers",
                "ROLE_SITE_ACCESS_ADMIN",
                ICON_USERS));
        MENU_ITEMS.add(new MenuItem(
                "Interface admin",
                "Page récapitulative des outils réservés aux administrateurs du site.",
                "admin",
                "ROLE_SITE_ACCESS_ADMIN",
                ICON_SHIELD));
        MENU_ITEMS.add(new MenuItem(
                "Retour a l'interface Aggregate",
                "Formulaires, permissions et administration standard d'ODK Aggregate.",
                "Aggregate.html",
                null,
                ICON_GRID));
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private DataSource getDataSource() {
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        return (DataSource) ctx.getBean("dataSource");
    }

    private boolean currentUserHasRole(String role) {
        return FormSchemaUtil.currentUserHasRole(role, getServletContext());
    }

    private static Integer countRows(Connection conn, String sql) {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Integer projectCount = null;
        Integer formCount = null;
        Integer userCount = null;
        boolean showUserCount = currentUserHasRole("ROLE_SITE_ACCESS_ADMIN");

        try (Connection conn = getDataSource().getConnection()) {
            projectCount = countRows(conn, "SELECT COUNT(*) FROM odk_prod.app_project");
            formCount = countRows(conn, "SELECT COUNT(*) FROM odk_prod.\"_form_info_submission_association\" "
                    + "WHERE \"IS_SUBMISSION_ALLOWED\" = true AND \"URI_SUBMISSION_DATA_MODEL\" IS NOT NULL");
        } catch (SQLException e) {
            // Statistiques indisponibles - le tableau de bord reste utilisable sans elles.
        }
        if (showUserCount) {
            try {
                CallingContext cc = ContextFactory.getCallingContext(this, req);
                userCount = SecurityServiceUtil.getAllUsers(true, cc).size();
            } catch (Exception e) {
                userCount = null;
            }
        }

        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>ODK Tiipaalga - Tableau de bord</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), "accueil");
        out.println("<div class='container'>");
        out.println("<h2>Que voulez-vous faire ?</h2>");
        out.println("<p class='info'>Vue d'ensemble et outils disponibles.</p>");

        out.println("<div class='stats'>");
        out.println(statTile(ICON_FOLDER, projectCount, "Projet(s)"));
        out.println(statTile(ICON_FILE, formCount, "Formulaire(s)"));
        if (showUserCount) {
            out.println(statTile(ICON_USERS, userCount, "Utilisateur(s)"));
        }
        out.println("</div>");

        renderCharts(out);

        out.println("<div class='grid'>");
        for (MenuItem item : MENU_ITEMS) {
            if (item.requiredRole != null && !currentUserHasRole(item.requiredRole)) {
                continue;
            }
            out.println("<a class='card' href='" + htmlEscape(item.url) + "'>"
                    + "<div class='icon-badge'>" + item.icon + "</div>"
                    + "<h3>" + htmlEscape(item.title) + "</h3>"
                    + "<p>" + htmlEscape(item.description) + "</p></a>");
        }
        out.println("</div>");
        out.println("</div></body></html>");
    }

    private static final int TREND_DAYS = 30;

    /**
     * Deux graphiques calcules a partir des memes tables de soumissions que le reste du tableau
     * de bord (aucune donnee fictive) : une courbe "soumissions dans le temps" (somme, jour par
     * jour, sur tous les formulaires) et un classement des formulaires les plus actifs. Ouvre sa
     * propre connexion (comme renderUserList dans UserManagementServlet) plutot que de restructurer
     * le bloc try-with-resources existant plus haut dans doGet.
     */
    private void renderCharts(PrintWriter out) {
        try (Connection conn = getDataSource().getConnection()) {
            Set<String> formIds = FormSchemaUtil.listAllFormIds(conn);
            if (formIds.isEmpty()) {
                return;
            }
            long[] totalDaily = new long[TREND_DAYS];
            Map<String, Long> countsByForm = new java.util.HashMap<String, Long>();
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
                    // Formulaire indisponible (schema incomplet, etc.) - ignoré pour ce graphique.
                }
            }

            List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(countsByForm.entrySet());
            sorted.sort(new Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return Long.compare(b.getValue(), a.getValue());
                }
            });
            LinkedHashMap<String, Long> topForms = new LinkedHashMap<String, Long>();
            for (int i = 0; i < sorted.size() && i < 8; i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                if (e.getValue() != null && e.getValue() > 0) {
                    topForms.put(e.getKey(), e.getValue());
                }
            }

            LocalDate today = LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
            List<String> labels = new ArrayList<String>();
            labels.add(today.minusDays(TREND_DAYS - 1).format(fmt));
            labels.add(today.format(fmt));

            out.println("<div class='charts-grid'>");
            ChartUtil.renderLineChart(out, "Soumissions des " + TREND_DAYS + " derniers jours (tous formulaires)",
                    labels, totalDaily);
            if (!topForms.isEmpty()) {
                ChartUtil.renderHorizontalBarChart(out, "Formulaires les plus actifs", topForms);
            }
            out.println("</div>");
        } catch (SQLException e) {
            // Graphiques indisponibles - le tableau de bord reste utilisable sans eux.
        }
    }

    private static String statTile(String icon, Integer value, String label) {
        String display = value == null ? "—" : String.valueOf(value);
        return "<div class='stat'><div class='stat-icon'>" + icon + "</div>"
                + "<div><div class='stat-value'>" + display + "</div>"
                + "<div class='stat-label'>" + htmlEscape(label) + "</div></div></div>";
    }
}
