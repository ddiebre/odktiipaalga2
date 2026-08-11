package odk.home;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Portail /home : point d'entree central listant les outils internes
 * (edition de soumissions aujourd'hui, futures fonctionnalites ensuite).
 * L'acces a /home lui-meme est restreint a ROLE_DATA_OWNER (voir
 * applicationContext-security.xml) ; chaque MenuItem peut en plus exiger
 * un role supplementaire pour n'apparaitre qu'aux utilisateurs concernes.
 */
public class HomeServlet extends HttpServlet {

    private static class MenuItem {
        final String title;
        final String description;
        final String url;
        final String requiredRole;

        MenuItem(String title, String description, String url, String requiredRole) {
            this.title = title;
            this.description = description;
            this.url = url;
            this.requiredRole = requiredRole;
        }
    }

    private static final List<MenuItem> MENU_ITEMS = new ArrayList<MenuItem>();
    static {
        MENU_ITEMS.add(new MenuItem(
                "Modifier une soumission",
                "Consulter, corriger, importer (CSV) ou visualiser sur carte les soumissions d'un formulaire.",
                "editSubmission",
                null));
        MENU_ITEMS.add(new MenuItem(
                "Projets",
                "Regrouper des formulaires par projet et suivre le nombre de soumissions de chacun.",
                "project",
                null));
        MENU_ITEMS.add(new MenuItem(
                "Retour a l'interface Aggregate",
                "Formulaires, permissions et administration standard d'ODK Aggregate.",
                "Aggregate.html",
                null));
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private boolean currentUserHasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        RoleHierarchy roleHierarchy = (RoleHierarchy) ctx.getBean("hierarchicalRoleRelationships");
        Collection<? extends GrantedAuthority> reachable = roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities());
        for (GrantedAuthority a : reachable) {
            if (role.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static final String PAGE_CSS =
            "body{margin:0;background:#f4f6f9;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1f2430;}"
            + "*{box-sizing:border-box;}"
            + ".topbar{background:#1e3a5f;color:#fff;padding:14px 24px;font-size:16px;font-weight:600;letter-spacing:.2px;}"
            + ".container{max-width:960px;margin:24px auto;padding:0 16px 48px;}"
            + "h2{font-size:20px;font-weight:600;margin:0 0 16px;color:#1e3a5f;}"
            + ".info{color:#6b7280;font-size:13px;margin-bottom:20px;}"
            + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:16px;}"
            + ".card{display:block;background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);"
            + "padding:20px 24px;text-decoration:none;color:inherit;transition:box-shadow .15s,transform .15s;}"
            + ".card:hover{box-shadow:0 4px 12px rgba(0,0,0,.12);transform:translateY(-1px);text-decoration:none;}"
            + ".card h3{margin:0 0 8px;font-size:16px;font-weight:600;color:#1e3a5f;}"
            + ".card p{margin:0;font-size:13px;color:#6b7280;line-height:1.4;}";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>ODK Tiipaalga - Menu</title>");
        out.println("<style>" + PAGE_CSS + "</style>");
        out.println("</head><body>");
        out.println("<div class='topbar'>ODK Tiipaalga &middot; Menu</div>");
        out.println("<div class='container'>");
        out.println("<h2>Que voulez-vous faire ?</h2>");
        out.println("<p class='info'>Choisissez un outil ci-dessous.</p>");
        out.println("<div class='grid'>");
        for (MenuItem item : MENU_ITEMS) {
            if (item.requiredRole != null && !currentUserHasRole(item.requiredRole)) {
                continue;
            }
            out.println("<a class='card' href='" + htmlEscape(item.url) + "'>"
                    + "<h3>" + htmlEscape(item.title) + "</h3>"
                    + "<p>" + htmlEscape(item.description) + "</p></a>");
        }
        out.println("</div>");
        out.println("</div></body></html>");
    }
}
