package odk.common;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Petits graphiques auto-suffisants (barres en CSS pur, courbe en SVG inline) pour visualiser des
 * comptages deja calcules ailleurs (FormSchemaUtil.submissionStats/dailySubmissionCounts). Aucune
 * dependance JS externe, coherent avec le reste de ces outils - ne fait aucune requete SQL.
 */
public final class ChartUtil {

    private ChartUtil() {
    }

    /** Barres horizontales : un label + une barre proportionnelle a la valeur max du jeu de donnees + la valeur. */
    public static void renderHorizontalBarChart(PrintWriter out, String title, LinkedHashMap<String, Long> data) {
        out.println("<div class='chart-card'>");
        if (title != null) {
            out.println("<h3 class='chart-title'>" + FormSchemaUtil.htmlEscape(title) + "</h3>");
        }
        if (data.isEmpty()) {
            out.println("<p class='info'>Pas encore de données.</p>");
            out.println("</div>");
            return;
        }
        long max = 1;
        for (Long v : data.values()) {
            if (v != null && v > max) {
                max = v;
            }
        }
        out.println("<div class='bar-chart'>");
        for (Map.Entry<String, Long> e : data.entrySet()) {
            long v = e.getValue() == null ? 0 : e.getValue();
            int pct = (int) Math.round(v * 100.0 / max);
            String label = FormSchemaUtil.htmlEscape(e.getKey());
            out.println("<div class='bar-chart-row'>"
                    + "<div class='bar-chart-label' title='" + label + "'>" + label + "</div>"
                    + "<div class='bar-chart-track'><div class='bar-chart-fill' style='width:" + pct + "%'></div></div>"
                    + "<div class='bar-chart-value'>" + v + "</div>"
                    + "</div>");
        }
        out.println("</div>");
        out.println("</div>");
    }

    /** Courbe SVG : une valeur par jour, sur labels.size() jours consecutifs (labels.size() == values.length). */
    public static void renderLineChart(PrintWriter out, String title, List<String> labels, long[] values) {
        out.println("<div class='chart-card'>");
        if (title != null) {
            out.println("<h3 class='chart-title'>" + FormSchemaUtil.htmlEscape(title) + "</h3>");
        }
        if (values == null || values.length == 0) {
            out.println("<p class='info'>Pas encore de données.</p>");
            out.println("</div>");
            return;
        }
        long max = 1;
        long total = 0;
        for (long v : values) {
            if (v > max) {
                max = v;
            }
            total += v;
        }
        int width = 600;
        int height = 150;
        int padding = 6;
        int n = values.length;
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x = n == 1 ? width / 2.0 : padding + (width - 2.0 * padding) * i / (n - 1);
            double y = height - padding - (height - 2.0 * padding) * (values[i] / (double) max);
            if (i > 0) {
                points.append(' ');
            }
            points.append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
        }
        String areaClose = String.format(Locale.ROOT, " %.1f,%.1f %.1f,%.1f", width - (double) padding,
                height - (double) padding, (double) padding, height - (double) padding);

        out.println("<p class='info' style='margin-top:0'><strong>" + total
                + "</strong> soumission(s) au total sur cette période.</p>");
        out.println("<svg class='line-chart' viewBox='0 0 " + width + " " + height + "' width='100%' height='" + height
                + "' preserveAspectRatio='none'>");
        out.println("<polyline points='" + points + areaClose + "' class='line-chart-area' "
                + "fill='rgba(37,99,235,.1)' stroke='none'></polyline>");
        out.println("<polyline points='" + points + "' class='line-chart-line' "
                + "fill='none' stroke='#2563eb' stroke-width='2'></polyline>");
        out.println("</svg>");
        if (labels != null && !labels.isEmpty()) {
            out.println("<div class='line-chart-axis'>"
                    + "<span>" + FormSchemaUtil.htmlEscape(labels.get(0)) + "</span>"
                    + "<span>" + FormSchemaUtil.htmlEscape(labels.get(labels.size() - 1)) + "</span>"
                    + "</div>");
        }
        out.println("</div>");
    }
}
