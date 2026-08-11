package odk.formlist.rewrite;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.CharArrayWriter;
import java.io.PrintWriter;

/**
 * Wrapper qui capture le corps de la réponse et l'en-tête Location
 * pour permettre la réécriture des URLs (retrait du port 8080).
 */
public class CharResponseWrapper extends HttpServletResponseWrapper {

    private final CharArrayWriter output = new CharArrayWriter();
    /** Location capturée pour réécriture (non transmise au client tant que non corrigée). */
    private String locationHeader;

    public CharResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public PrintWriter getWriter() {
        return new PrintWriter(output);
    }

    public String getContent() {
        return output.toString();
    }

    /** En-tête Location capturé (peut contenir :8080). */
    public String getLocationHeader() {
        return locationHeader;
    }

    @Override
    public void setHeader(String name, String value) {
        if ("Location".equalsIgnoreCase(name)) {
            locationHeader = value;
            return;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if ("Location".equalsIgnoreCase(name)) {
            locationHeader = value;
            return;
        }
        super.addHeader(name, value);
    }

    @Override
    public boolean containsHeader(String name) {
        if ("Location".equalsIgnoreCase(name)) {
            return locationHeader != null;
        }
        return super.containsHeader(name);
    }

    @Override
    public String getHeader(String name) {
        if ("Location".equalsIgnoreCase(name)) {
            return locationHeader;
        }
        return super.getHeader(name);
    }
}
