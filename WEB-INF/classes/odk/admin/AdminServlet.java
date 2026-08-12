package odk.admin;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Portail /admin : page recapitulative regroupant les outils reserves aux
 * administrateurs du site (voir /admin intercept-url, ROLE_SITE_ACCESS_ADMIN).
 * Distinct de /home (accessible a ROLE_DATA_OWNER, plus large) : ici on ne
 * liste que ce qui exige le niveau d'acces le plus eleve.
 */
public class AdminServlet extends HttpServlet {

    private static class MenuItem {
        final String title;
        final String description;
        final String url;
        final String icon;

        MenuItem(String title, String description, String url, String icon) {
            this.title = title;
            this.description = description;
            this.url = url;
            this.icon = icon;
        }
    }

    private static final String ICON_USERS =
            "<svg viewBox='0 0 24 24'><circle cx='9' cy='8' r='3.2'/><path d='M3.5 20c0-3.3 2.6-6 6-6s6 2.7 6 6'/>"
            + "<circle cx='17.5' cy='9' r='2.6'/><path d='M15.8 14.3c2.6.4 4.7 2.6 4.7 5.7'/></svg>";
    private static final String ICON_FOLDER =
            "<svg viewBox='0 0 24 24'><path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z'/></svg>";
    private static final String ICON_EDIT =
            "<svg viewBox='0 0 24 24'><path d='M4 20h4L18.5 9.5a2.121 2.121 0 0 0-3-3L5 17v3z'/><path d='M13.5 6.5l4 4'/></svg>";
    private static final String ICON_GRID =
            "<svg viewBox='0 0 24 24'><rect x='3' y='3' width='7' height='7' rx='1.2'/><rect x='14' y='3' width='7' height='7' rx='1.2'/>"
            + "<rect x='3' y='14' width='7' height='7' rx='1.2'/><rect x='14' y='14' width='7' height='7' rx='1.2'/></svg>";

    private static final List<MenuItem> MENU_ITEMS = new ArrayList<MenuItem>();
    static {
        MENU_ITEMS.add(new MenuItem(
                "Gestion des utilisateurs",
                "Créer des comptes, modifier leurs rôles, réinitialiser un mot de passe, supprimer un compte.",
                "manageUsers",
                ICON_USERS));
        MENU_ITEMS.add(new MenuItem(
                "Projets",
                "Regrouper des formulaires par projet et suivre le nombre de soumissions de chacun.",
                "project",
                ICON_FOLDER));
        MENU_ITEMS.add(new MenuItem(
                "Modifier une soumission",
                "Consulter, corriger, importer (CSV) ou visualiser sur carte les soumissions d'un formulaire.",
                "editSubmission",
                ICON_EDIT));
        MENU_ITEMS.add(new MenuItem(
                "Interface Aggregate (classique)",
                "Onglet Site Admin natif d'ODK : formulaires, permissions détaillées, administration standard.",
                "Aggregate.html",
                ICON_GRID));
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>ODK Tiipaalga - Interface admin</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css'>");
        out.println("</head><body>");
        out.println("<div class='topbar'><a href='home' class='topbar-link'>&laquo; Menu</a> &middot; ODK Tiipaalga &middot; Interface admin</div>");
        out.println("<div class='container'>");
        out.println("<h2>Interface admin</h2>");
        out.println("<p class='info'>Outils réservés aux administrateurs du site.</p>");
        out.println("<div class='grid'>");
        for (MenuItem item : MENU_ITEMS) {
            out.println("<a class='card' href='" + htmlEscape(item.url) + "'>"
                    + "<div class='icon-badge'>" + item.icon + "</div>"
                    + "<h3>" + htmlEscape(item.title) + "</h3>"
                    + "<p>" + htmlEscape(item.description) + "</p></a>");
        }
        out.println("</div>");
        out.println("</div></body></html>");
    }
}
