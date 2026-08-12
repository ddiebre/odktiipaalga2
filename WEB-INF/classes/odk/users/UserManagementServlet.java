package odk.users;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.opendatakit.aggregate.ContextFactory;
import org.opendatakit.common.security.client.CredentialsInfo;
import org.opendatakit.common.security.client.RealmSecurityInfo;
import org.opendatakit.common.security.client.UserSecurityInfo;
import org.opendatakit.common.security.common.GrantedAuthorityName;
import org.opendatakit.common.security.server.SecurityServiceUtil;
import org.opendatakit.common.security.spring.CredentialsInfoBuilderInternal;
import org.opendatakit.common.web.CallingContext;
import org.springframework.security.authentication.encoding.MessageDigestPasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Admin-only tool (see /manageUsers intercept-url, ROLE_SITE_ACCESS_ADMIN)
 * pour creer des comptes locaux et gerer leurs roles, sans reimplementer la
 * logique de hachage de mot de passe ni le bookkeeping des roles : delegue
 * entierement aux classes internes deja utilisees par l'onglet Permissions
 * GWT et les outils /ssl/* natifs (SecurityServiceUtil, CredentialsInfoBuilderInternal).
 */
public class UserManagementServlet extends HttpServlet {

    private static class GroupOption {
        final String paramName;
        final String label;
        final GrantedAuthorityName authority;

        GroupOption(String paramName, String label, GrantedAuthorityName authority) {
            this.paramName = paramName;
            this.label = label;
            this.authority = authority;
        }
    }

    private static final List<GroupOption> GROUPS = new ArrayList<GroupOption>();
    static {
        GROUPS.add(new GroupOption("g_collector", "Collecteur de données", GrantedAuthorityName.GROUP_DATA_COLLECTORS));
        GROUPS.add(new GroupOption("g_viewer", "Lecteur de données", GrantedAuthorityName.GROUP_DATA_VIEWERS));
        GROUPS.add(new GroupOption("g_formmanager", "Gestionnaire de formulaires", GrantedAuthorityName.GROUP_FORM_MANAGERS));
        GROUPS.add(new GroupOption("g_synctables", "Synchronisation des tables", GrantedAuthorityName.GROUP_SYNCHRONIZE_TABLES));
        GROUPS.add(new GroupOption("g_supertables", "Super-utilisateur des tables", GrantedAuthorityName.GROUP_SUPER_USER_TABLES));
        GROUPS.add(new GroupOption("g_admintables", "Administrateur des tables", GrantedAuthorityName.GROUP_ADMINISTER_TABLES));
        GROUPS.add(new GroupOption("g_siteadmin", "Administrateur du site", GrantedAuthorityName.GROUP_SITE_ADMINS));
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static final String ICON_PLUS = "<svg viewBox='0 0 24 24'><path d='M12 5v14M5 12h14'/></svg>";
    private static final String ICON_EDIT =
            "<svg viewBox='0 0 24 24'><path d='M4 20h4L18.5 9.5a2.121 2.121 0 0 0-3-3L5 17v3z'/><path d='M13.5 6.5l4 4'/></svg>";
    private static final String ICON_TRASH =
            "<svg viewBox='0 0 24 24'><path d='M4 7h16'/><path d='M9 7V4h6v3'/>"
            + "<path d='M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13'/><path d='M10 11v6M14 11v6'/></svg>";

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Set<GrantedAuthorityName> readSelectedGroups(HttpServletRequest req) {
        Set<GrantedAuthorityName> groups = new TreeSet<GrantedAuthorityName>();
        for (GroupOption opt : GROUPS) {
            if (req.getParameter(opt.paramName) != null) {
                groups.add(opt.authority);
            }
        }
        return groups;
    }

    private static String groupsToLabel(Set<GrantedAuthorityName> groups) {
        if (groups == null || groups.isEmpty()) {
            return "—";
        }
        List<String> labels = new ArrayList<String>();
        for (GroupOption opt : GROUPS) {
            if (groups.contains(opt.authority)) {
                labels.add(opt.label);
            }
        }
        return labels.isEmpty() ? "—" : String.join(", ", labels);
    }

    private static RealmSecurityInfo buildRealm(CallingContext cc) {
        RealmSecurityInfo realm = new RealmSecurityInfo();
        realm.setRealmString(cc.getUserService().getCurrentRealm().getRealmString());
        MessageDigestPasswordEncoder enc = (MessageDigestPasswordEncoder) cc.getBean("basicAuthenticationMessageDigestPasswordEncoder");
        realm.setBasicAuthHashEncoding(enc.getAlgorithm());
        return realm;
    }

    private static UserSecurityInfo findUser(ArrayList<UserSecurityInfo> all, String username) {
        if (username == null) {
            return null;
        }
        for (UserSecurityInfo u : all) {
            if (username.equals(u.getUsername())) {
                return u;
            }
        }
        return null;
    }

    private void writeHeader(PrintWriter out, String title) {
        out.println("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        out.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>" + htmlEscape(title) + "</title>");
        out.println("<link rel='stylesheet' href='stylesheets/odk-tools.css'>");
        out.println("</head><body>");
        out.println("<div class='topbar'><a href='home' class='topbar-link'>&laquo; Menu</a> &middot; ODK Tiipaalga &middot; Utilisateurs</div>");
        out.println("<div class='container'>");
        out.println("<h2>" + htmlEscape(title) + "</h2>");
    }

    private void writeFooter(PrintWriter out) {
        out.println("</div></body></html>");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        CallingContext cc = ContextFactory.getCallingContext(this, req);
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        try {
            if ("new".equals(action)) {
                renderUserForm(out, false, "", "", "", new TreeSet<GrantedAuthorityName>(), null);
            } else if ("edit".equals(action)) {
                String username = req.getParameter("username");
                ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
                UserSecurityInfo found = findUser(all, username);
                if (found == null) {
                    writeHeader(out, "Utilisateurs");
                    out.println("<p class='err'>Utilisateur introuvable.</p>");
                    writeFooter(out);
                } else {
                    renderUserForm(out, true, found.getUsername(), found.getFullName(), found.getEmail(),
                            found.getAssignedUserGroups(), null);
                }
            } else if ("delete".equals(action)) {
                renderDeleteConfirm(out, req.getParameter("username"));
            } else {
                String notice = "1".equals(req.getParameter("created")) ? "Utilisateur créé."
                        : "1".equals(req.getParameter("updated")) ? "Utilisateur mis à jour."
                        : "1".equals(req.getParameter("deleted")) ? "Utilisateur supprimé." : null;
                String error = "self".equals(req.getParameter("delerr"))
                        ? "Vous ne pouvez pas supprimer votre propre compte."
                        : "super".equals(req.getParameter("delerr"))
                        ? "Le compte super-utilisateur ne peut pas être supprimé."
                        : null;
                renderUserList(cc, out, notice, error);
            }
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        CallingContext cc = ContextFactory.getCallingContext(this, req);
        if ("create".equals(action)) {
            handleCreate(cc, req, resp);
        } else if ("update".equals(action)) {
            handleUpdate(cc, req, resp);
        } else if ("deleteConfirm".equals(action)) {
            handleDelete(cc, req, resp);
        } else {
            resp.sendRedirect("manageUsers");
        }
    }

    private void renderUserList(CallingContext cc, PrintWriter out, String notice, String error) throws Exception {
        writeHeader(out, "Utilisateurs");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        if (notice != null) {
            out.println("<p class='success'>" + htmlEscape(notice) + "</p>");
        }
        out.println("<div class='toolbar'><a class='btn-link' href='manageUsers?action=new'>" + ICON_PLUS
                + " Nouvel utilisateur</a></div>");
        ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
        out.println("<table><tr><th>Nom d'utilisateur</th><th>Nom complet</th><th>Rôles</th><th></th></tr>");
        for (UserSecurityInfo u : all) {
            out.println("<tr><td>" + htmlEscape(u.getUsername()) + "</td><td>" + htmlEscape(u.getFullName()) + "</td><td>"
                    + htmlEscape(groupsToLabel(u.getAssignedUserGroups())) + "</td><td><div class='row-actions'>"
                    + "<a class='btn-link' href='manageUsers?action=edit&username=" + urlEncode(u.getUsername())
                    + "'>" + ICON_EDIT + " Modifier</a>"
                    + "<a class='btn-link danger' href='manageUsers?action=delete&username=" + urlEncode(u.getUsername())
                    + "'>" + ICON_TRASH + " Supprimer</a></div></td></tr>");
        }
        out.println("</table>");
        writeFooter(out);
    }

    private void renderDeleteConfirm(PrintWriter out, String username) {
        writeHeader(out, "Supprimer l'utilisateur");
        out.println("<p class='back-link'><a href='manageUsers'>&laquo; Liste des utilisateurs</a></p>");
        out.println("<div class='card'>");
        out.println("<p>Voulez-vous vraiment supprimer l'utilisateur <strong>" + htmlEscape(username) + "</strong> ? "
                + "Cette action est irréversible.</p>");
        out.println("<form method='post' action='manageUsers?action=deleteConfirm&username=" + urlEncode(username) + "'>");
        out.println("<div class='actions'><button type='submit' class='danger'>" + ICON_TRASH + " Supprimer définitivement</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void renderUserForm(PrintWriter out, boolean editing, String username, String fullName, String email,
            Set<GrantedAuthorityName> selectedGroups, String error) {
        writeHeader(out, editing ? "Modifier l'utilisateur" : "Nouvel utilisateur");
        out.println("<p class='back-link'><a href='manageUsers'>&laquo; Liste des utilisateurs</a></p>");
        if (error != null) {
            out.println("<p class='err'>" + htmlEscape(error) + "</p>");
        }
        out.println("<div class='card'>");
        out.println("<form method='post' action='manageUsers?"
                + (editing ? "action=update&username=" + urlEncode(username) : "action=create") + "'>");
        out.println("<div class='form-grid'>");
        if (editing) {
            out.println("<label>Nom d'utilisateur</label><input type='text' value='" + htmlEscape(username) + "' disabled>");
            out.println("<input type='hidden' name='username' value='" + htmlEscape(username) + "'>");
        } else {
            out.println("<label>Nom d'utilisateur</label><input type='text' name='username' value='"
                    + htmlEscape(username == null ? "" : username) + "' required>");
        }
        out.println("<label>Nom complet</label><input type='text' name='fullName' value='"
                + htmlEscape(fullName == null ? "" : fullName) + "'>");
        out.println("<label>E-mail</label><input type='text' name='email' value='"
                + htmlEscape(email == null ? "" : email) + "'>");
        if (editing) {
            out.println("<label>Nouveau mot de passe</label><input type='password' name='password' "
                    + "placeholder='(laisser vide pour ne pas changer)'>");
        } else {
            out.println("<label>Mot de passe initial</label><input type='password' name='password' required>");
        }
        out.println("<label>Rôles</label><div>");
        for (GroupOption opt : GROUPS) {
            boolean checked = selectedGroups != null && selectedGroups.contains(opt.authority);
            out.println("<label style='font-weight:normal;display:block;margin-bottom:4px;'>"
                    + "<input type='checkbox' name='" + opt.paramName + "'" + (checked ? " checked" : "") + "> "
                    + htmlEscape(opt.label) + "</label>");
        }
        out.println("</div>");
        out.println("</div>");
        out.println("<div class='actions'><button type='submit'>" + (editing ? "Enregistrer" : ICON_PLUS + " Créer l'utilisateur")
                + "</button></div>");
        out.println("</form>");
        out.println("</div>");
        writeFooter(out);
    }

    private void handleCreate(CallingContext cc, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = trimToNull(req.getParameter("username"));
        String fullName = trimToNull(req.getParameter("fullName"));
        String email = trimToNull(req.getParameter("email"));
        String password = req.getParameter("password");
        Set<GrantedAuthorityName> groups = readSelectedGroups(req);

        resp.setContentType("text/html;charset=UTF-8");
        if (username == null) {
            renderUserForm(resp.getWriter(), false, "", fullName, email, groups, "Le nom d'utilisateur est obligatoire.");
            return;
        }
        if (password == null || password.isEmpty()) {
            renderUserForm(resp.getWriter(), false, username, fullName, email, groups, "Le mot de passe initial est obligatoire.");
            return;
        }
        try {
            ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
            if (findUser(all, username) != null) {
                renderUserForm(resp.getWriter(), false, username, fullName, email, groups, "Ce nom d'utilisateur existe déjà.");
                return;
            }
            UserSecurityInfo newUser = new UserSecurityInfo(username, fullName, email, UserSecurityInfo.UserType.REGISTERED);
            newUser.setAssignedUserGroups(new TreeSet<GrantedAuthorityName>(groups));
            all.add(newUser);
            SecurityServiceUtil.setStandardSiteAccessConfiguration(all, new ArrayList<GrantedAuthorityName>(), cc);

            RealmSecurityInfo realm = buildRealm(cc);
            CredentialsInfo credentials = CredentialsInfoBuilderInternal.build(username, realm, password);
            SecurityServiceUtil.setUserCredentials(credentials, cc);

            resp.sendRedirect("manageUsers?created=1");
        } catch (Exception e) {
            renderUserForm(resp.getWriter(), false, username, fullName, email, groups, "Erreur : " + e.getMessage());
        }
    }

    private void handleUpdate(CallingContext cc, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        String fullName = trimToNull(req.getParameter("fullName"));
        String email = trimToNull(req.getParameter("email"));
        String password = req.getParameter("password");
        Set<GrantedAuthorityName> groups = readSelectedGroups(req);

        resp.setContentType("text/html;charset=UTF-8");
        try {
            ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
            UserSecurityInfo target = findUser(all, username);
            if (target == null) {
                renderUserForm(resp.getWriter(), true, username, fullName, email, groups, "Utilisateur introuvable.");
                return;
            }
            target.setFullName(fullName);
            target.setEmail(email);
            target.setAssignedUserGroups(new TreeSet<GrantedAuthorityName>(groups));
            SecurityServiceUtil.setStandardSiteAccessConfiguration(all, new ArrayList<GrantedAuthorityName>(), cc);

            if (password != null && !password.isEmpty()) {
                RealmSecurityInfo realm = buildRealm(cc);
                CredentialsInfo credentials = CredentialsInfoBuilderInternal.build(username, realm, password);
                SecurityServiceUtil.setUserCredentials(credentials, cc);
            }

            resp.sendRedirect("manageUsers?updated=1");
        } catch (Exception e) {
            renderUserForm(resp.getWriter(), true, username, fullName, email, groups, "Erreur : " + e.getMessage());
        }
    }

    private void handleDelete(CallingContext cc, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");

        String currentUsername = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : null;
        if (username != null && username.equals(currentUsername)) {
            resp.sendRedirect("manageUsers?delerr=self");
            return;
        }
        String superUsername = cc.getUserService().getSuperUserUsername();
        if (username != null && username.equals(superUsername)) {
            resp.sendRedirect("manageUsers?delerr=super");
            return;
        }

        try {
            ArrayList<UserSecurityInfo> all = SecurityServiceUtil.getAllUsers(true, cc);
            UserSecurityInfo target = findUser(all, username);
            if (target != null) {
                all.remove(target);
                SecurityServiceUtil.setStandardSiteAccessConfiguration(all, new ArrayList<GrantedAuthorityName>(), cc);
            }
            resp.sendRedirect("manageUsers?deleted=1");
        } catch (Exception e) {
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            writeHeader(out, "Utilisateurs");
            out.println("<p class='err'>Erreur : " + htmlEscape(e.getMessage()) + "</p>");
            writeFooter(out);
        }
    }
}
