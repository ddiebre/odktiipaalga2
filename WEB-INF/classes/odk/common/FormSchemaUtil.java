package odk.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContext;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Resolution du schema ODK (formId -> table "core" contenant les soumissions)
 * et petits utilitaires HTML/URL partages entre les outils internes
 * (odk.editsubmission, odk.project). Toujours appele avec des identifiants
 * SQL valides via checkIdentifier avant toute concatenation dans une requete.
 */
public final class FormSchemaUtil {

    private FormSchemaUtil() {
    }

    /**
     * Casse-cache pour stylesheets/odk-tools.css et javascript/odk-tools.js : a ajouter en
     * parametre "?v=" sur chaque <link>/<script> qui les referencent. Change a chaque
     * redemarrage de Tomcat (valeur figee au chargement de la classe) - evite qu'un navigateur
     * continue de servir une version en cache de ces fichiers apres une mise a jour du code
     * (observe : barre de navigation/graphiques non stylee malgre un Ctrl+F5).
     */
    public static final String ASSET_VERSION = String.valueOf(System.currentTimeMillis());

    public static void checkIdentifier(String identifier) throws SQLException {
        if (identifier == null || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new SQLException("Identifiant SQL invalide: " + identifier);
        }
    }

    public static String resolveSubmissionDataModelUri(Connection conn, String formId) throws SQLException {
        String sql = "SELECT \"URI_SUBMISSION_DATA_MODEL\" FROM odk_prod.\"_form_info_submission_association\" "
                + "WHERE \"SUBMISSION_FORM_ID\" = ? AND \"URI_SUBMISSION_DATA_MODEL\" IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, formId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    public static String resolveCoreTable(Connection conn, String submissionDataModelUri) throws SQLException {
        String sql = "SELECT \"PERSIST_AS_TABLE_NAME\" FROM odk_prod.\"_form_data_model\" "
                + "WHERE \"PARENT_URI_FORM_DATA_MODEL\" = ? AND \"ELEMENT_TYPE\" = 'GROUP' "
                + "ORDER BY \"ORDINAL_NUMBER\" LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, submissionDataModelUri);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String table = rs.getString(1);
                    checkIdentifier(table);
                    return table;
                }
            }
        }
        return null;
    }

    public static String resolveCoreTableOrThrow(Connection conn, String formId) throws SQLException {
        String modelUri = resolveSubmissionDataModelUri(conn, formId);
        String coreTable = modelUri == null ? null : resolveCoreTable(conn, modelUri);
        if (coreTable == null) {
            throw new SQLException("Formulaire introuvable : " + formId);
        }
        return coreTable;
    }

    public static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Nombre de soumissions et date de la derniere, pour un formulaire donne. */
    public static final class SubmissionStats {
        public final long count;
        public final Timestamp lastSubmissionDate;

        SubmissionStats(long count, Timestamp lastSubmissionDate) {
            this.count = count;
            this.lastSubmissionDate = lastSubmissionDate;
        }
    }

    public static SubmissionStats submissionStats(Connection conn, String formId) throws SQLException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        String sql = "SELECT COUNT(*), MAX(\"_SUBMISSION_DATE\") FROM odk_prod.\"" + coreTable + "\"";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new SubmissionStats(rs.getLong(1), rs.getTimestamp(2));
        }
    }

    /**
     * Nombre de soumissions par jour sur les `days` derniers jours (aujourd'hui inclus) pour un
     * formulaire donne. index 0 = le jour le plus ancien (aujourd'hui - days + 1), index days-1 =
     * aujourd'hui - pratique pour sommer, jour par jour, les tableaux renvoyes pour plusieurs
     * formulaires (cf. HomeServlet, graphique "soumissions dans le temps").
     */
    public static long[] dailySubmissionCounts(Connection conn, String coreTable, int days) throws SQLException {
        checkIdentifier(coreTable);
        long[] counts = new long[days];
        String sql = "SELECT (CURRENT_DATE - \"_SUBMISSION_DATE\"::date) AS days_ago, COUNT(*) "
                + "FROM odk_prod.\"" + coreTable + "\" "
                + "WHERE \"_SUBMISSION_DATE\" >= CURRENT_DATE - ? "
                + "GROUP BY days_ago";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, days - 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int daysAgo = rs.getInt(1);
                    long count = rs.getLong(2);
                    int index = days - 1 - daysAgo;
                    if (index >= 0 && index < days) {
                        counts[index] += count;
                    }
                }
            }
        }
        return counts;
    }

    /** Tous les formId connus d'ODK - sert a detecter, par difference avant/apres, le formulaire cree par un import. */
    public static Set<String> listAllFormIds(Connection conn) throws SQLException {
        String sql = "SELECT \"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_info_submission_association\"";
        Set<String> ids = new HashSet<String>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    /**
     * Nom d'utilisateur local (LOCAL_USERNAME, ex. "test") de la personne connectee.
     * Attention : Authentication.getName() renvoie en realite l'_URI interne d'ODK
     * (ex. "uid:test|2026-08-13T18:08:01.499+0000", quel que soit le mecanisme
     * d'authentification - Digest, formulaire de connexion, etc., car c'est ce que
     * UserDetailsServiceImpl utilise comme "username" Spring Security), pas le
     * LOCAL_USERNAME utilise ailleurs (ex. UserSecurityInfo.getUsername(), ou les
     * noms stockes dans odk_prod.app_project_user/app_project_form). Cette methode
     * fait l'extraction pour permettre une comparaison correcte avec ces valeurs -
     * a utiliser partout ou on doit comparer l'utilisateur connecte a un LOCAL_USERNAME,
     * PAS pour horodater un _CREATOR_URI_USER/_LAST_UPDATE_URI_USER (qui doivent
     * rester des URI, cf. EditSubmissionServlet/ProjectServlet).
     */
    public static String currentLocalUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = auth.getName();
        if (name != null && name.startsWith("uid:")) {
            int pipe = name.indexOf('|');
            if (pipe > 4) {
                return name.substring(4, pipe);
            }
        }
        return name;
    }

    /**
     * Vrai si l'utilisateur connecte atteint le role donne via la hierarchie des roles
     * (bean "hierarchicalRoleRelationships") - meme pattern deja utilise separement dans
     * HomeServlet/ProjectFormListFilter/EditSubmissionMenuLinkFilter, centralise ici pour
     * que NavBar (et tout nouveau code) n'ait pas a le redupliquer une cinquieme fois.
     */
    public static boolean currentUserHasRole(String role, ServletContext servletContext) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        RoleHierarchy roleHierarchy = (RoleHierarchy) ctx.getBean("hierarchicalRoleRelationships");
        Collection<? extends GrantedAuthority> reachable = roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities());
        for (GrantedAuthority a : reachable) {
            if (role.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
