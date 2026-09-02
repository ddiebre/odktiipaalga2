package odk.profile;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.opendatakit.aggregate.ContextFactory;
import org.opendatakit.common.security.client.CredentialsInfo;
import org.opendatakit.common.security.client.RealmSecurityInfo;
import org.opendatakit.common.security.server.SecurityServiceUtil;
import org.opendatakit.common.security.spring.CredentialsInfoBuilderInternal;
import org.opendatakit.common.web.CallingContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.encoding.MessageDigestPasswordEncoder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.common.FormSchemaUtil;
import odk.common.NavBar;

/**
 * Libre-service (tout utilisateur connecté, voir intercept-url /profile -> ROLE_USER) permettant à
 * chacun de changer son propre mot de passe, accessible depuis le menu profil de la barre de
 * navigation. Ne réimplémente ni le hachage (CredentialsInfoBuilderInternal/SecurityServiceUtil,
 * même mécanisme que /manageUsers) ni la vérification du mot de passe actuel : celle-ci délègue au
 * bean Spring Security "authenticationManager" déjà configuré (son basicAuthenticationProvider est
 * un DaoAuthenticationProvider qui sait vérifier un couple utilisateur/mot de passe local).
 */
public class ProfileServlet extends HttpServlet {

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + FormSchemaUtil.htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css?v=" + FormSchemaUtil.ASSET_VERSION + "'>");
        out.println("<script src='javascript/odk-tools.js?v=" + FormSchemaUtil.ASSET_VERSION + "' defer></script>");
        out.println("</head><body>");
        NavBar.render(out, getServletContext(), null);
        out.println("<div class='container'>");
        out.println("<h2>" + FormSchemaUtil.htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=UTF-8");
        String notice = "1".equals(req.getParameter("updated")) ? "Mot de passe changé avec succès." : null;
        renderForm(resp.getWriter(), notice, null);
    }

    private void renderForm(PrintWriter out, String notice, String error) {
        String username = FormSchemaUtil.currentLocalUsername();
        writeHeader(out, "Mon profil");
        out.println("<p class='back-link'><a href='home'>&laquo; Accueil</a></p>");
        if (notice != null) {
            out.println("<p class='success'>" + FormSchemaUtil.htmlEscape(notice) + "</p>");
        }
        if (error != null) {
            out.println("<p class='err'>" + FormSchemaUtil.htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<p><strong>Utilisateur :</strong> " + FormSchemaUtil.htmlEscape(username) + "</p>");
        out.println("</div>");
        out.println("<h3>Changer mon mot de passe</h3>");
        out.println("<div class='card'>");
        out.println("<form method='post' action='profile?action=changePassword'>");
        out.println("<div class='form-grid'>");
        out.println("<label>Mot de passe actuel</label><input type='password' name='currentPassword' required>");
        out.println("<label>Nouveau mot de passe</label><input type='password' name='newPassword' required>");
        out.println("<label>Confirmer le nouveau mot de passe</label><input type='password' name='confirmPassword' required>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>Changer le mot de passe</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        if (!"changePassword".equals(action)) {
            resp.sendRedirect("profile");
            return;
        }
        handleChangePassword(req, resp);
    }

    private void handleChangePassword(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String currentPassword = req.getParameter("currentPassword");
        String newPassword = req.getParameter("newPassword");
        String confirmPassword = req.getParameter("confirmPassword");
        String username = FormSchemaUtil.currentLocalUsername();

        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        if (username == null) {
            renderForm(out, null, "Impossible de déterminer votre compte. Reconnectez-vous et réessayez.");
            return;
        }
        if (newPassword == null || newPassword.isEmpty()) {
            renderForm(out, null, "Le nouveau mot de passe est obligatoire.");
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            renderForm(out, null, "Les deux mots de passe ne correspondent pas.");
            return;
        }

        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        try {
            AuthenticationManager authManager = (AuthenticationManager) ctx.getBean("authenticationManager");
            authManager.authenticate(new UsernamePasswordAuthenticationToken(username, currentPassword));
        } catch (AuthenticationException e) {
            renderForm(out, null, "Mot de passe actuel incorrect.");
            return;
        }

        try {
            CallingContext cc = ContextFactory.getCallingContext(this, req);
            RealmSecurityInfo realm = new RealmSecurityInfo();
            realm.setRealmString(cc.getUserService().getCurrentRealm().getRealmString());
            MessageDigestPasswordEncoder enc =
                    (MessageDigestPasswordEncoder) cc.getBean("basicAuthenticationMessageDigestPasswordEncoder");
            realm.setBasicAuthHashEncoding(enc.getAlgorithm());

            CredentialsInfo credentials = CredentialsInfoBuilderInternal.build(username, realm, newPassword);
            SecurityServiceUtil.setUserCredentials(credentials, cc);
            resp.sendRedirect("profile?updated=1");
        } catch (Exception e) {
            renderForm(out, null, "Erreur : " + e.getMessage());
        }
    }
}
