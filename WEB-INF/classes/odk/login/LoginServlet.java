package odk.login;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.FormSchemaUtil;

/**
 * Page de connexion HTML (/login, access="true") à la place de la popup Digest native du
 * navigateur pour tout accès non authentifié à un outil interne - voir
 * applicationContext-security.xml (delegatingAuthenticationEntryPoint.defaultEntryPoint =
 * loginPageEntryPoint). Les appareils ODK Collect/OpenRosa continuent d'utiliser Digest sans
 * changement (routés séparément par leurs en-têtes X-OpenRosa-Version/X-OpenDataKit-Version).
 *
 * Ne réimplémente pas l'authentification : délègue au même bean "authenticationManager" que
 * ProfileServlet (déjà utilisé pour vérifier un mot de passe), puis place le résultat dans
 * SecurityContextHolder - le SecurityContextPersistenceFilter déjà présent dans la chaîne (avant
 * ce servlet) le persiste automatiquement en session à la fin de la requête, exactement comme il
 * le ferait pour une authentification Digest/Basic classique.
 */
public class LoginServlet extends HttpServlet {

    private static String htmlEscape(String s) {
        return FormSchemaUtil.htmlEscape(s);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated() && !(existing instanceof AnonymousAuthenticationToken)) {
            resp.sendRedirect("home");
            return;
        }
        resp.setContentType("text/html;charset=UTF-8");
        String error = req.getParameter("error");
        renderForm(resp.getWriter(), "1".equals(error) ? "Nom d'utilisateur ou mot de passe incorrect." : null);
    }

    private void renderForm(PrintWriter out, String error) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>Connexion — ODK Tiipaalga</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("</head><body>");
        out.println("<div class='login-page'>");
        out.println("<div class='login-card'>");
        out.println("<div class='login-title'>ODK Tiipaalga</div>");
        out.println("<p class='login-subtitle'>Connectez-vous pour accéder à l'interface.</p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<form method='post' action='login'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Utilisateur</label><input type='text' name='username' required autofocus>");
        out.println("<label>Mot de passe</label><input type='password' name='password' required>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit' style='width:100%;'>Se connecter</button></div>");
        out.println("</form>");
        out.println("</div>");
        out.println("</div>");
        out.println("</body></html>");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            resp.sendRedirect("login?error=1");
            return;
        }

        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        try {
            AuthenticationManager authManager = (AuthenticationManager) ctx.getBean("authenticationManager");
            Authentication result = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username.trim(), password));
            SecurityContextHolder.getContext().setAuthentication(result);
        } catch (AuthenticationException e) {
            resp.sendRedirect("login?error=1");
            return;
        }

        // Redirige vers la page initialement demandée avant la redirection vers /login, si connue
        // (sauvegardée automatiquement par ExceptionTranslationFilter) - sinon, vers l'accueil.
        String target = "home";
        try {
            SavedRequest saved = new HttpSessionRequestCache().getRequest(req, resp);
            if (saved != null) {
                target = saved.getRedirectUrl();
            }
        } catch (Exception ignored) {
        }
        resp.sendRedirect(target);
    }
}
