package odk.common;

import java.io.IOException;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * Delegue la creation d'un formulaire ODK a l'endpoint natif existant
 * (/formUpload - meme logique de parsing/validation XForms que l'import natif
 * d'ODK), en rejouant le cookie de session de l'utilisateur courant sur un
 * appel local (boucle locale, jamais via ngrok). Partage entre odk.project
 * (import direct dans un projet) et odk.editsubmission (import general depuis
 * /home) pour eviter de dupliquer cette logique.
 */
public final class FormUploadUtil {

    private FormUploadUtil() {
    }

    public static final class UploadResult {
        public final int status;
        public final String body;

        UploadResult(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean isSuccess() {
            return status == 201;
        }
    }

    public static UploadResult uploadFormXml(HttpServletRequest req, byte[] xmlBytes, String fileName, String formTitle)
            throws IOException {
        // Toujours http (jamais req.getScheme(), qui vaut "https" derriere ngrok) : req.getLocalPort()
        // designe le connecteur Tomcat local reel, qui sert en clair - un appel https vers ce port
        // provoque "Unsupported or unrecognized SSL message" (poignee de main TLS contre un port HTTP).
        String uploadUrl = "http://127.0.0.1:" + req.getLocalPort() + req.getContextPath() + "/formUpload";
        RequestConfig config = RequestConfig.custom().setConnectTimeout(15000).setSocketTimeout(15000).build();
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            HttpPost post = new HttpPost(uploadUrl);
            String cookie = req.getHeader("Cookie");
            if (cookie != null) {
                post.setHeader("Cookie", cookie);
            }
            HttpEntity entity = MultipartEntityBuilder.create()
                    .addBinaryBody("form_def_file", xmlBytes, ContentType.APPLICATION_XML,
                            fileName != null ? fileName : "form.xml")
                    .addTextBody("form_name", formTitle, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .build();
            post.setEntity(entity);
            try (CloseableHttpResponse httpResp = client.execute(post)) {
                int status = httpResp.getStatusLine().getStatusCode();
                String body = httpResp.getEntity() != null ? EntityUtils.toString(httpResp.getEntity(), "UTF-8") : "";
                return new UploadResult(status, body);
            }
        }
    }

    /** Deduit un titre par defaut a partir du nom de fichier, pour ne jamais laisser form_name vide. */
    public static String deriveTitleFromFileName(String fileName) {
        if (fileName == null) {
            return "Formulaire importé";
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        return name.isEmpty() ? "Formulaire importé" : name;
    }
}
