package odk.formlist.rewrite;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Filtre qui réécrit les réponses OpenRosa et GWT-RPC pour retirer le port 8080 des URLs
 * (formList, formXml, submission, xformsList, xformsManifest, aggregateui/formservice). Utile derrière ngrok ou un reverse proxy.
 * formXml est essentiel : le XML du formulaire contient l'URL de soumission utilisée par Collect.
 * aggregateui/formservice est essentiel : c'est l'appel GWT-RPC (FormServiceImpl.getExports())
 * qui renvoie les liens de téléchargement affichés dans l'onglet "Exported Submissions" de l'UI web
 * (ex: activite_f3pa_autre_results.csv) - sans ce filtre ces liens contiennent ":8080" et ne
 * fonctionnent pas derrière ngrok.
 */
public class FormListUrlRewriteFilter implements Filter {

    private static final String PORT_TO_STRIP = ":8080";

    private static boolean shouldRewrite(String requestUri) {
        if (requestUri == null) return false;
        return requestUri.endsWith("/formList")
                || requestUri.endsWith("/formXml")
                || requestUri.endsWith("/www/formXml")
                || requestUri.endsWith("/submission")
                || requestUri.endsWith("/xformsList")
                || requestUri.endsWith("/xformsManifest")
                || requestUri.endsWith("/aggregateui/formservice");
    }

    private static String stripPort(String value) {
        return value != null && value.contains(PORT_TO_STRIP) ? value.replace(PORT_TO_STRIP, "") : value;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!shouldRewrite(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        CharResponseWrapper wrapper = new CharResponseWrapper(httpResponse);
        chain.doFilter(request, wrapper);

        String content = wrapper.getContent();
        content = stripPort(content);
        if (content == null) content = "";

        String location = stripPort(wrapper.getLocationHeader());
        if (location != null) {
            httpResponse.setHeader("Location", location);
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        httpResponse.setContentLength(bytes.length);
        httpResponse.getOutputStream().write(bytes);
    }

    @Override
    public void destroy() {
    }
}
