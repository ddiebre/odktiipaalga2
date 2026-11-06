package odk.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // ---- Suppression logique des soumissions (corbeille) ------------------------------------------
    //
    // Les tables "core" des soumissions appartiennent a ODK Aggregate : plutot que d'y ajouter une
    // colonne "deleted", une soumission supprimee est marquee dans une table a part
    // (odk_prod.app_submission_deleted, une ligne par _URI). Le _URI d'une soumission ODK
    // ("uuid:...") est unique globalement, il suffit donc comme cle. Toute lecture de soumissions
    // doit ajouter notDeletedSql(...) a sa clause WHERE.

    /**
     * Cree si besoin les tables propres aux soumissions (corbeille, historique, corrections, reprises)
     * - appele au demarrage (ProjectServlet.init) et par EditSubmissionServlet.init. Idempotent.
     */
    public static void ensureSubmissionTables(java.sql.Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_submission_deleted ("
                + "submission_uri VARCHAR(255) PRIMARY KEY,"
                + "form_id VARCHAR(255) NOT NULL,"
                + "deleted_at TIMESTAMP NOT NULL DEFAULT now(),"
                + "deleted_by VARCHAR(255))");
        st.execute("CREATE INDEX IF NOT EXISTS app_submission_deleted_form_id_idx "
                + "ON odk_prod.app_submission_deleted(form_id)");
        // Historique des modifications : une ligne par operation sur une soumission (modification,
        // mise a jour CSV, retour arriere, corbeille...). "changes" = JSON des seuls champs modifies,
        // [{"column","label","before","after"}], valeurs au format texte relisible par bindValue.
        st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_submission_history ("
                + "id SERIAL PRIMARY KEY,"
                + "form_id VARCHAR(255) NOT NULL,"
                + "submission_uri VARCHAR(255) NOT NULL,"
                + "action VARCHAR(20) NOT NULL,"
                + "changes TEXT,"
                + "note TEXT,"
                + "changed_at TIMESTAMP NOT NULL DEFAULT now(),"
                + "changed_by VARCHAR(255))");
        st.execute("CREATE INDEX IF NOT EXISTS app_submission_history_uri_idx "
                + "ON odk_prod.app_submission_history(submission_uri)");
        // Couche de corrections (nettoyage sans toucher au brut) : la table core d'ODK garde
        // toujours la valeur envoyee du terrain ; une correction = la valeur nettoyee d'UN champ
        // d'UNE soumission. Valeur nettoyee = correction si elle existe, sinon le brut. value peut
        // etre NULL (correction "vider ce champ") : c'est l'existence de la ligne qui compte.
        st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_submission_correction ("
                + "submission_uri VARCHAR(255) NOT NULL,"
                + "column_name VARCHAR(255) NOT NULL,"
                + "form_id VARCHAR(255) NOT NULL,"
                + "value TEXT,"
                + "corrected_at TIMESTAMP NOT NULL DEFAULT now(),"
                + "corrected_by VARCHAR(255),"
                + "PRIMARY KEY (submission_uri, column_name))");
        st.execute("CREATE INDEX IF NOT EXISTS app_submission_correction_form_id_idx "
                + "ON odk_prod.app_submission_correction(form_id)");
        // Statut de validation du nettoyage : pas de ligne = "a verifier" (etat par defaut de toute
        // soumission recue) ; sinon VALIDEE ou REJETEE, avec un commentaire (motif d'un rejet).
        st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_submission_status ("
                + "submission_uri VARCHAR(255) PRIMARY KEY,"
                + "form_id VARCHAR(255) NOT NULL,"
                + "status VARCHAR(20) NOT NULL,"
                + "comment TEXT,"
                + "updated_at TIMESTAMP NOT NULL DEFAULT now(),"
                + "updated_by VARCHAR(255))");
        st.execute("CREATE INDEX IF NOT EXISTS app_submission_status_form_idx "
                + "ON odk_prod.app_submission_status(form_id, status)");
        st.execute("CREATE TABLE IF NOT EXISTS odk_prod.app_migration ("
                + "name VARCHAR(255) PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT now())");
    }

    /**
     * Condition SQL "cette soumission n'est pas a la corbeille". uriExpr est l'expression SQL
     * (jamais une saisie utilisateur) qui donne le _URI de la soumission : "\"_URI\"" sur une table
     * core, "\"_TOP_LEVEL_AURI\"" sur une table enfant (_BN, groupe repete).
     */
    public static String notDeletedSql(String uriExpr) {
        return "NOT EXISTS (SELECT 1 FROM odk_prod.app_submission_deleted sd WHERE sd.submission_uri = " + uriExpr + ")";
    }

    /** Raccourci pour une table core : notDeletedSql("\"_URI\""). */
    public static final String CORE_NOT_DELETED = notDeletedSql("\"_URI\"");

    public static SubmissionStats submissionStats(Connection conn, String formId) throws SQLException {
        String coreTable = resolveCoreTableOrThrow(conn, formId);
        String sql = "SELECT COUNT(*), MAX(\"_SUBMISSION_DATE\") FROM odk_prod.\"" + coreTable + "\" WHERE " + CORE_NOT_DELETED;
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
                + "WHERE \"_SUBMISSION_DATE\" >= CURRENT_DATE - ? AND " + CORE_NOT_DELETED + " "
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

    /**
     * Un element BINARY (photo/vidéo/audio) d'un formulaire ODK n'a pas de colonne physique sur la
     * table core : il persiste dans sa propre table "&lt;nom&gt;_BN" (une ligne par fichier envoyé,
     * avec CONTENT_TYPE/UNROOTED_FILE_PATH/CONTENT_LENGTH et _TOP_LEVEL_AURI = _URI de la
     * soumission), dont le contenu binaire est lui-même réparti (fragmenté) entre une table
     * "&lt;nom&gt;_REF" (ordre des fragments) et une table "&lt;nom&gt;_BLB" (octets bruts) - schéma
     * de persistance standard d'ODK Aggregate pour tout champ BINARY, vérifié directement en base
     * (odk.media.MediaServlet).
     */
    public static final class MediaElement {
        public final String elementName;
        public final String tableName;

        public MediaElement(String elementName, String tableName) {
            this.elementName = elementName;
            this.tableName = tableName;
        }
    }

    /** Les questions "média" (photo/vidéo/audio) d'un formulaire, dans l'ordre du formulaire. */
    public static List<MediaElement> listMediaElements(Connection conn, String formId) throws SQLException {
        List<MediaElement> result = new ArrayList<MediaElement>();
        String modelUri = resolveSubmissionDataModelUri(conn, formId);
        if (modelUri == null) {
            return result;
        }
        String sql = "SELECT \"ELEMENT_NAME\", \"PERSIST_AS_TABLE_NAME\" FROM odk_prod.\"_form_data_model\" "
                + "WHERE \"URI_SUBMISSION_DATA_MODEL\" = ? AND \"ELEMENT_TYPE\" = 'BINARY' "
                + "AND \"PERSIST_AS_TABLE_NAME\" IS NOT NULL ORDER BY \"ORDINAL_NUMBER\"";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modelUri);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString(2);
                    checkIdentifier(tableName);
                    result.add(new MediaElement(rs.getString(1), tableName));
                }
            }
        }
        return result;
    }

    /**
     * Confirme qu'une table donnée est réellement une table "_BN" enregistrée pour un champ BINARY
     * dans _form_data_model - à vérifier avant toute requête utilisant un nom de table venu de
     * l'URL (paramètre "table"), en plus de checkIdentifier, pour ne jamais laisser un appelant
     * pointer vers une table arbitraire de odk_prod.
     */
    public static boolean isKnownBinaryTable(Connection conn, String tableName) throws SQLException {
        checkIdentifier(tableName);
        String sql = "SELECT 1 FROM odk_prod.\"_form_data_model\" "
                + "WHERE \"PERSIST_AS_TABLE_NAME\" = ? AND \"ELEMENT_TYPE\" = 'BINARY' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Arbre des projets (odk_prod.app_project.parent_id) : un sous-projet hérite des
     * formulaires/utilisateurs de sa lignée d'ancêtres (accès), et un projet agrège les
     * formulaires de tous ses sous-projets (descendants) pour son propre affichage
     * (soumissions/graphiques/médias/suivi des enquêteurs - voir ProjectServlet/ChartsServlet).
     * Chargé en une fois par appel (petite table, cohérent avec le reste de l'app).
     */
    private static Map<Integer, Integer> loadProjectParents(Connection conn) throws SQLException {
        Map<Integer, Integer> parentOf = new HashMap<Integer, Integer>();
        try (java.sql.Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, parent_id FROM odk_prod.app_project WHERE deleted = false")) {
            while (rs.next()) {
                int id = rs.getInt(1);
                int parentId = rs.getInt(2);
                parentOf.put(id, rs.wasNull() ? null : Integer.valueOf(parentId));
            }
        }
        return parentOf;
    }

    private static Map<Integer, List<Integer>> childrenOf(Map<Integer, Integer> parentOf) {
        Map<Integer, List<Integer>> children = new HashMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, Integer> e : parentOf.entrySet()) {
            Integer parent = e.getValue();
            if (parent != null) {
                List<Integer> list = children.get(parent);
                if (list == null) {
                    list = new ArrayList<Integer>();
                    children.put(parent, list);
                }
                list.add(e.getKey());
            }
        }
        return children;
    }

    /** projectId et tous ses descendants (sous-projets, sous-sous-projets, ...) - pour l'agrégation d'un projet. */
    public static Set<Integer> descendantProjectIds(Connection conn, int projectId) throws SQLException {
        Map<Integer, List<Integer>> children = childrenOf(loadProjectParents(conn));
        Set<Integer> result = new HashSet<Integer>();
        result.add(projectId);
        List<Integer> stack = new ArrayList<Integer>();
        stack.add(projectId);
        while (!stack.isEmpty()) {
            int p = stack.remove(stack.size() - 1);
            List<Integer> kids = children.get(p);
            if (kids != null) {
                for (Integer k : kids) {
                    if (result.add(k)) {
                        stack.add(k);
                    }
                }
            }
        }
        return result;
    }

    /**
     * projectId, ses ancêtres (lignée jusqu'à la racine) et ses descendants - "branche verticale"
     * du projet, hors branches cousines. C'est l'ensemble effectif utilisé pour l'accès d'un
     * lecteur de données affecté à ce projet : il hérite de la lignée de ses ancêtres, et voit
     * aussi ses propres sous-projets.
     */
    public static Set<Integer> effectiveProjectIds(Connection conn, int projectId) throws SQLException {
        Map<Integer, Integer> parentOf = loadProjectParents(conn);
        Set<Integer> result = descendantProjectIds(conn, projectId);
        Integer current = parentOf.get(projectId);
        while (current != null && result.add(current)) {
            current = parentOf.get(current);
        }
        return result;
    }

    private static Set<Integer> listAssignedProjectIds(Connection conn, String username) throws SQLException {
        Set<Integer> ids = new HashSet<Integer>();
        if (username == null) {
            return ids;
        }
        // Un projet à la corbeille (deleted = true) ne donne plus aucun accès - ni à ses formulaires
        // sur ODK Collect, ni à ses données dans l'interface web.
        String sql = "SELECT pu.project_id FROM odk_prod.app_project_user pu "
                + "JOIN odk_prod.app_project p ON p.id = pu.project_id "
                + "WHERE pu.username = ? AND p.deleted = false";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }
        return ids;
    }

    /**
     * Les formId visibles pour un lecteur de données : ceux rattachés à un projet directement
     * affecté (odk_prod.app_project_user), à sa lignée d'ancêtres (hérité), ou à l'un de ses
     * sous-projets (agrégé) - non affecté à aucun projet, il ne voit rien.
     */
    public static Set<String> listFormsVisibleToViewer(Connection conn, String username) throws SQLException {
        Set<String> forms = new HashSet<String>();
        for (Integer projectId : listProjectsVisibleToViewer(conn, username)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT form_id FROM odk_prod.app_project_form WHERE project_id = ?")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        forms.add(rs.getString(1));
                    }
                }
            }
        }
        return forms;
    }

    public static boolean isFormAccessibleToViewer(Connection conn, String formId, String username) throws SQLException {
        return listFormsVisibleToViewer(conn, username).contains(formId);
    }

    public static boolean isProjectAccessibleToViewer(Connection conn, int projectId, String username) throws SQLException {
        return listProjectsVisibleToViewer(conn, username).contains(projectId);
    }

    /**
     * Les id de projets visibles pour un lecteur de données : chaque projet directement affecté,
     * étendu à sa branche verticale complète (ancêtres hérités + sous-projets agrégés) - voir
     * effectiveProjectIds.
     */
    public static Set<Integer> listProjectsVisibleToViewer(Connection conn, String username) throws SQLException {
        Set<Integer> visible = new HashSet<Integer>();
        for (Integer directId : listAssignedProjectIds(conn, username)) {
            visible.addAll(effectiveProjectIds(conn, directId));
        }
        return visible;
    }

    /** Le formId propriétaire d'une table "_BN" (champ BINARY) - pour vérifier l'accès (projet) avant de diffuser un fichier média. */
    public static String resolveFormIdForBinaryTable(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT a.\"SUBMISSION_FORM_ID\" FROM odk_prod.\"_form_data_model\" dm "
                + "JOIN odk_prod.\"_form_info_submission_association\" a "
                + "  ON a.\"URI_SUBMISSION_DATA_MODEL\" = dm.\"URI_SUBMISSION_DATA_MODEL\" "
                + "WHERE dm.\"PERSIST_AS_TABLE_NAME\" = ? AND dm.\"ELEMENT_TYPE\" = 'BINARY' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
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
        return extractLocalUsername(auth.getName());
    }

    /** Extrait le LOCAL_USERNAME d'une URI ODK "uid:username|timestamp" - voir currentLocalUsername() pour le contexte. */
    public static String extractLocalUsername(String uri) {
        if (uri != null && uri.startsWith("uid:")) {
            int pipe = uri.indexOf('|');
            if (pipe > 4) {
                return uri.substring(4, pipe);
            }
        }
        return uri;
    }

    /** Les LOCAL_USERNAME des comptes possédant une autorité donnée (ex. "GROUP_DATA_COLLECTORS"). */
    public static Set<String> listUsernamesWithAuthority(Connection conn, String authority) throws SQLException {
        Set<String> usernames = new HashSet<String>();
        String sql = "SELECT DISTINCT \"USER\" FROM odk_prod.\"_user_granted_authority\" WHERE \"GRANTED_AUTHORITY\" = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, authority);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String username = extractLocalUsername(rs.getString(1));
                    if (username != null) {
                        usernames.add(username);
                    }
                }
            }
        }
        return usernames;
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
