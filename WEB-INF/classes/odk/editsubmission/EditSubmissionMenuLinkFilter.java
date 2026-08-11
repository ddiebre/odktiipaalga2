package odk.editsubmission;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import odk.formlist.rewrite.CharResponseWrapper;

/**
 * Injecte un lien "Menu" (vers /home) dans la page Aggregate.html
 * (shell HTML statique généré par AggregateHtmlServlet, dont le contenu
 * principal est rempli par le GWT non modifiable ici faute de source).
 * Le lien n'est ajouté que si l'utilisateur courant a effectivement accès
 * à /home (ROLE_DATA_OWNER, en tenant compte de la hiérarchie
 * des rôles comme le fait le filterSecurityInterceptor).
 */
public class EditSubmissionMenuLinkFilter implements Filter {

    private static final String LINK_HTML =
            "<div style=\"position:fixed;bottom:14px;right:14px;z-index:99999;\">"
            + "<a href=\"home\" style=\"display:inline-block;background:#2a6b2a;color:#fff;"
            + "font-family:sans-serif;font-size:13px;font-weight:bold;padding:8px 14px;border-radius:6px;"
            + "text-decoration:none;box-shadow:0 2px 6px rgba(0,0,0,.35);\">Menu</a></div>";

    private FilterConfig filterConfig;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
    }

    private boolean currentUserCanAccessHome() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return false;
        }
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(filterConfig.getServletContext());
        RoleHierarchy roleHierarchy = (RoleHierarchy) ctx.getBean("hierarchicalRoleRelationships");
        Collection<? extends GrantedAuthority> reachable = roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities());
        for (GrantedAuthority a : reachable) {
            if ("ROLE_DATA_OWNER".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)
                || !currentUserCanAccessHome()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        CharResponseWrapper wrapper = new CharResponseWrapper(httpResponse);
        chain.doFilter(request, wrapper);

        String content = wrapper.getContent();
        if (content == null) {
            content = "";
        }
        if (content.contains("</body>")) {
            content = content.replace("</body>", LINK_HTML + "</body>");
        }

        byte[] bytes = content.getBytes("UTF-8");
        httpResponse.setContentLength(bytes.length);
        httpResponse.getOutputStream().write(bytes);
    }

    @Override
    public void destroy() {
    }
}
