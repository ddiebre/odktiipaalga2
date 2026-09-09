package odk.activity;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

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
 * Journal d'activité (/activity, ROLE_SITE_ACCESS_ADMIN) : consulte odk_prod.app_activity_log,
 * alimentée par odk.common.ActivityLog.record(...) depuis les points de mutation importants
 * (formulaires, projets, utilisateurs, soumissions) de ProjectServlet/EditSubmissionServlet/
 * UserManagementServlet/ProfileServlet. Lecture seule : ce servlet n'écrit jamais dans le journal
 * lui-même.
 */
public class ActivityLogServlet extends HttpServlet {

    private static final int PAGE_SIZE = 100;

    private static class Entry {
        final Timestamp occurredAt;
        final String username;
        final String action;
        final String targetType;
        final String targetId;
        final String details;

        Entry(Timestamp occurredAt, String username, String action, String targetType, String targetId, String details) {
            this.occurredAt = occurredAt;
            this.username = username;
            this.action = action;
            this.targetType = targetType;
            this.targetId = targetId;
            this.details = details;
        }
    }

    private static String htmlEscape(String s) {
        return FormSchemaUtil.htmlEscape(s);
    }

    private DataSource getDataSource() {
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        return (DataSource) ctx.getBean("dataSource");
    }

    @Override
    public void init() throws ServletException {
        try (Connection conn = getDataSource().getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_activity_log ("
                    + "id SERIAL PRIMARY KEY,"
                    + "occurred_at TIMESTAMP NOT NULL DEFAULT now(),"
                    + "username VARCHAR(255),"
                    + "action VARCHAR(100) NOT NULL,"
                    + "target_type VARCHAR(50),"
                    + "target_id VARCHAR(255),"
                    + "details TEXT)");
            st.execute("CREATE INDEX IF NOT EXISTS app_activity_log_occurred_at_idx "
                    + "ON odk_prod.app_activity_log(occurred_at DESC)");
            getServletContext().log("ActivityLogServlet: table odk_prod.app_activity_log prête.");
        } catch (SQLException e) {
            getServletContext().log("ActivityLogServlet: échec de la création de la table app_activity_log", e);
        }
    }

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), "parametres");
        out.println("<div class='container'>");
        out.println("<h2>" + htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        int offset = 0;
        try {
            offset = Integer.parseInt(req.getParameter("offset"));
        } catch (NumberFormatException ignored) {
        }
        String targetType = req.getParameter("targetType");

        try (Connection conn = getDataSource().getConnection()) {
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            writeHeader(out, "Journal d'activité");
            out.println("<p class='back-link'><a href='admin'>&laquo; Interface admin</a></p>");
            out.println("<p class='info'>Actions administratives importantes : création/modification/suppression de "
                    + "formulaires, projets, utilisateurs et soumissions.</p>");

            List<String> targetTypes = listDistinctTargetTypes(conn);
            renderFilter(out, targetTypes, targetType);

            List<Entry> entries = listEntries(conn, targetType, offset);
            if (entries.isEmpty()) {
                out.println("<p class='info'>Aucune activité enregistrée" + (targetType != null ? " pour ce filtre" : "")
                        + "" + (offset > 0 ? " sur cette page" : "") + ".</p>");
            } else {
                out.println("<div class='table-enhance'>");
                out.println("<input type='text' class='table-search-input' placeholder='Rechercher dans cette page...'>");
                out.println("<div style='overflow-x:auto;'>");
                out.println("<table><thead><tr><th data-sortable>Date/heure</th><th data-sortable>Utilisateur</th>"
                        + "<th data-sortable>Action</th><th data-sortable>Cible</th><th>Détails</th></tr></thead><tbody>");
                for (Entry e : entries) {
                    out.println("<tr><td>" + htmlEscape(e.occurredAt == null ? "" : e.occurredAt.toString()) + "</td>"
                            + "<td>" + htmlEscape(e.username) + "</td>"
                            + "<td>" + htmlEscape(e.action) + "</td>"
                            + "<td>" + htmlEscape(e.targetType) + (e.targetId != null ? " — " + htmlEscape(e.targetId) : "")
                            + "</td>"
                            + "<td>" + htmlEscape(e.details) + "</td></tr>");
                }
                out.println("</tbody></table>");
                out.println("</div>");
                out.println("</div>");
            }

            String filterParam = targetType != null ? "&targetType=" + FormSchemaUtil.urlEncode(targetType) : "";
            out.println("<p class='pagination'>");
            if (offset > 0) {
                out.println("<a href='activity?offset=" + Math.max(0, offset - PAGE_SIZE) + filterParam + "'>&laquo; Précédent</a>");
            }
            if (entries.size() == PAGE_SIZE) {
                out.println("<a href='activity?offset=" + (offset + PAGE_SIZE) + filterParam + "'>Suivant &raquo;</a>");
            }
            out.println("</p>");
            writeFooter(out);
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private void renderFilter(PrintWriter out, List<String> targetTypes, String selected) {
        if (targetTypes.isEmpty()) {
            return;
        }
        out.println("<form method='get' action='activity' class='card' style='display:flex;gap:10px;align-items:center;"
                + "flex-wrap:wrap;'>");
        out.println("<label for='targetType' style='font-weight:600;'>Filtrer par type</label>");
        out.println("<select name='targetType' id='targetType'>");
        out.println("<option value=''" + (selected == null ? " selected" : "") + ">— Tous —</option>");
        for (String t : targetTypes) {
            boolean sel = t.equals(selected);
            out.println("<option value='" + htmlEscape(t) + "'" + (sel ? " selected" : "") + ">" + htmlEscape(t) + "</option>");
        }
        out.println("</select>");
        out.println("<button type='submit'>Filtrer</button>");
        out.println("</form>");
    }

    private List<String> listDistinctTargetTypes(Connection conn) throws SQLException {
        List<String> types = new ArrayList<String>();
        String sql = "SELECT DISTINCT target_type FROM odk_prod.app_activity_log "
                + "WHERE target_type IS NOT NULL ORDER BY target_type";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                types.add(rs.getString(1));
            }
        }
        return types;
    }

    private List<Entry> listEntries(Connection conn, String targetType, int offset) throws SQLException {
        List<Entry> entries = new ArrayList<Entry>();
        String sql = "SELECT occurred_at, username, action, target_type, target_id, details "
                + "FROM odk_prod.app_activity_log "
                + (targetType != null && !targetType.trim().isEmpty() ? "WHERE target_type = ? " : "")
                + "ORDER BY occurred_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (targetType != null && !targetType.trim().isEmpty()) {
                ps.setString(idx++, targetType);
            }
            ps.setInt(idx++, PAGE_SIZE);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getTimestamp(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)));
                }
            }
        }
        return entries;
    }
}
