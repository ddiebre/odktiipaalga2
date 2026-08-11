package odk.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Resolution du schema ODK (formId -> table "core" contenant les soumissions)
 * et petits utilitaires HTML/URL partages entre les outils internes
 * (odk.editsubmission, odk.project). Toujours appele avec des identifiants
 * SQL valides via checkIdentifier avant toute concatenation dans une requete.
 */
public final class FormSchemaUtil {

    private FormSchemaUtil() {
    }

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
}
