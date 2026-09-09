package odk.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Journal d'activité (odk_prod.app_activity_log) : trace les actions administratives importantes
 * (création/modification/suppression de formulaire, projet, utilisateur, soumission) - visible sur
 * /activity (ROLE_SITE_ACCESS_ADMIN). Table bootstrapée par odk.activity.ActivityLogServlet.init().
 * Volontairement best-effort : un échec d'écriture du journal ne doit jamais faire échouer l'action
 * elle-même (ex. supprimer un formulaire doit réussir même si l'insertion du journal échoue).
 */
public final class ActivityLog {

    private ActivityLog() {
    }

    /**
     * @param action     identifiant court de l'action (ex. "form.delete", "project.create", "user.role_change")
     * @param targetType type de la cible (ex. "formulaire", "projet", "utilisateur", "soumission") - affiché tel quel
     * @param targetId   identifiant de la cible (formId, id de projet, username, _URI de soumission)
     * @param details    résumé lisible par un humain (peut être null)
     */
    public static void record(Connection conn, String action, String targetType, String targetId, String details) {
        String username = FormSchemaUtil.currentLocalUsername();
        String sql = "INSERT INTO odk_prod.app_activity_log(username, action, target_type, target_id, details) "
                + "VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, action);
            ps.setString(3, targetType);
            ps.setString(4, targetId);
            ps.setString(5, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Volontairement silencieux - voir javadoc de la classe.
        }
    }
}
