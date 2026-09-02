package odk.common;

import java.io.PrintWriter;

import javax.servlet.ServletContext;

/**
 * Barre de navigation partagee par tous les outils internes (odk.home,
 * odk.project, odk.editsubmission, odk.users, odk.admin) - remplace l'ancien
 * bandeau "topbar" imprime separement par chaque servlet. Un seul endroit a
 * modifier pour changer la navigation partout.
 */
public final class NavBar {

    private NavBar() {
    }

    /**
     * @param activeSection l'une de "accueil", "projets", "formulaires", "soumissions", "utilisateurs"
     *                      (ou null/valeur inconnue si la page ne correspond a aucune section)
     */
    public static void render(PrintWriter out, ServletContext servletContext, String activeSection) {
        boolean isSiteAdmin = FormSchemaUtil.currentUserHasRole("ROLE_SITE_ACCESS_ADMIN", servletContext);
        String username = FormSchemaUtil.currentLocalUsername();

        out.println("<div class='navbar'>");
        out.println("<div class='navbar-brand'>ODK Tiipaalga</div>");
        out.println("<nav class='navbar-links'>");
        navLink(out, "Accueil", "home", "accueil".equals(activeSection));
        navLink(out, "Projets", "project", "projets".equals(activeSection));
        navLink(out, "Formulaires", "editSubmission", "formulaires".equals(activeSection));
        navLink(out, "Soumissions", "editSubmission?action=submissions", "soumissions".equals(activeSection));
        if (isSiteAdmin) {
            navLink(out, "Utilisateurs", "manageUsers", "utilisateurs".equals(activeSection));
        }
        out.println("</nav>");
        out.println("<div class='navbar-user'>");
        if (username != null) {
            out.println("<div class='navbar-profile'>");
            out.println("<button type='button' class='navbar-profile-trigger'>"
                    + "<span class='navbar-username'>" + FormSchemaUtil.htmlEscape(username) + "</span>"
                    + "<svg class='navbar-caret' viewBox='0 0 24 24'><path d='M6 9l6 6 6-6'/></svg>"
                    + "</button>");
            out.println("<div class='navbar-profile-menu'>");
            out.println("<a href='profile'>Mon profil</a>");
            out.println("<a href='logout.html'>Déconnexion</a>");
            out.println("</div>");
            out.println("</div>");
        } else {
            out.println("<a class='navbar-logout' href='logout.html'>Déconnexion</a>");
        }
        out.println("</div>");
        out.println("</div>");
    }

    private static void navLink(PrintWriter out, String label, String href, boolean active) {
        out.println("<a class='navbar-link" + (active ? " active" : "") + "' href='" + href + "'>"
                + FormSchemaUtil.htmlEscape(label) + "</a>");
    }
}
