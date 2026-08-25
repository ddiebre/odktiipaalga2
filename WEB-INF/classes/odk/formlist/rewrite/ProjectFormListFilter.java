package odk.formlist.rewrite;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import odk.common.FormSchemaUtil;

/**
 * Filtre les reponses /formList et /xformsList (liste de formulaires qu'un
 * appareil ODK Collect synchronise) pour qu'un compte ROLE_DATA_COLLECTOR
 * n'y voie que les formulaires des projets auxquels il est explicitement
 * affecte (odk_prod.app_project_user / app_project_form). Les comptes
 * ROLE_DATA_OWNER ou plus voient toujours la liste complete, non filtree.
 *
 * Doit s'executer APRES FormListUrlRewriteFilter dans la chaine (voir
 * l'ordre des filter-mapping dans web.xml) : ce filtre ecrit son resultat
 * via response.getWriter(), la seule methode capturee par CharResponseWrapper -
 * un filtre englobant qui ecrirait via getOutputStream() ne serait pas
 * capture correctement.
 *
 * Ne filtre jamais /submission : un appareil qui a deja un formulaire hors
 * projet peut toujours y soumettre des donnees (portee volontairement
 * limitee a la liste, voir la discussion qui a mene a cette fonctionnalite).
 */
public class ProjectFormListFilter implements Filter {

    private FilterConfig filterConfig;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
    }

    private static boolean shouldFilter(String requestUri) {
        return requestUri != null && (requestUri.endsWith("/formList") || requestUri.endsWith("/xformsList"));
    }

    private DataSource getDataSource() {
        WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(filterConfig.getServletContext());
        return (DataSource) ctx.getBean("dataSource");
    }

    private boolean currentUserIsElevated() {
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

    private static String currentUsername() {
        return FormSchemaUtil.currentLocalUsername();
    }

    private Set<String> allowedFormIds(String username) throws SQLException {
        String sql = "SELECT DISTINCT pf.form_id FROM odk_prod.app_project_form pf "
                + "JOIN odk_prod.app_project_user pu ON pu.project_id = pf.project_id "
                + "WHERE pu.username = ?";
        Set<String> ids = new HashSet<String>();
        try (Connection conn = getDataSource().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
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

        if (!shouldFilter(httpRequest.getRequestURI()) || currentUserIsElevated()) {
            chain.doFilter(request, response);
            return;
        }

        String username = currentUsername();
        if (username == null) {
            chain.doFilter(request, response);
            return;
        }

        Set<String> allowed;
        try {
            allowed = allowedFormIds(username);
        } catch (SQLException e) {
            // Panne base de donnees ponctuelle : on laisse passer sans filtrer plutot que
            // de couper tout le monde a cause d'un probleme d'infrastructure transitoire.
            chain.doFilter(request, response);
            return;
        }

        CharResponseWrapper wrapper = new CharResponseWrapper(httpResponse);
        chain.doFilter(request, wrapper);

        String content = wrapper.getContent();
        if (content == null) {
            content = "";
        }
        String filtered = filterFormListXml(content, allowed);

        httpResponse.getWriter().write(filtered);
    }

    /** Retire du XML OpenRosa chaque &lt;xform&gt; dont le &lt;formID&gt; n'est pas dans allowedFormIds. */
    private static String filterFormListXml(String xml, Set<String> allowedFormIds) {
        if (xml.trim().isEmpty()) {
            return xml;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(xml)));

            Element root = doc.getDocumentElement();
            if (root == null || !"xforms".equals(root.getTagName())) {
                // Format inattendu (pas la reponse OpenRosa moderne) : on ne touche a rien.
                return xml;
            }

            NodeList xformNodes = root.getElementsByTagName("xform");
            // On retire en partant de la fin pour ne pas perturber la NodeList "live" pendant l'iteration.
            for (int i = xformNodes.getLength() - 1; i >= 0; i--) {
                Node xformNode = xformNodes.item(i);
                String formId = firstChildText(xformNode, "formID");
                if (formId == null || !allowedFormIds.contains(formId)) {
                    xformNode.getParentNode().removeChild(xformNode);
                }
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            // Erreur de parsing : on ne prend pas le risque de renvoyer un XML casse, on laisse passer tel quel.
            return xml;
        }
    }

    private static String firstChildText(Node parent, String childTagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && childTagName.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    @Override
    public void destroy() {
    }
}
