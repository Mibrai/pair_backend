package org.program.pair.domain.publicslot;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * La vignette de repli, dessinée quand le créneau n'a pas d'image.
 *
 * <p><b>Pourquoi ne pas simplement omettre l'image.</b> Un aperçu sans visuel
 * s'affiche dans une messagerie comme deux lignes de texte grises, à peu près
 * indiscernables d'un lien mort — et la page publique n'a d'autre raison d'être
 * que cet aperçu. Renvoyer {@code null} revenait donc à laisser tomber le canal
 * pour les créneaux qui n'ont pas de photo, c'est-à-dire la majorité.
 *
 * <p><b>Pourquoi une image dessinée plutôt qu'un jeu de visuels par catégorie.</b>
 * Le dépôt n'a aucun dossier d'actifs statiques et aucun visuel de marque. Une
 * dizaine de photographies auraient dû être produites, versionnées et maintenues
 * en regard d'un catalogue de catégories qui s'allonge — et une catégorie ajoutée
 * sans sa photo serait retombée sur le même vide. Ce dessin, lui, existe pour
 * toute catégorie, y compris celles qui n'existent pas encore : il tire sa
 * couleur de {@code categories.color_ramp}, que le référentiel porte déjà.
 *
 * <p><b>PNG et non SVG.</b> Les robots d'aperçu de WhatsApp, Facebook et Signal
 * ne rendent pas le SVG ; une vignette vectorielle aurait été refusée en silence,
 * ce qui est exactement la panne qu'on cherche à corriger.
 *
 * <p>1200 × 630 : le format que réclame {@code og:image} pour obtenir une grande
 * carte plutôt qu'une vignette carrée.
 */
public final class PublicSlotCover {

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;

    private static final Color DEFAULT_COLOR = new Color(0x14607F);
    private static final Color INK = new Color(0xFF, 0xFF, 0xFF);

    private static final int MARGIN = 84;
    private static final int TITLE_SIZE = 76;
    private static final int SUBTITLE_SIZE = 34;
    private static final int BRAND_SIZE = 30;

    private PublicSlotCover() {
    }

    /**
     * Dessine la vignette et la rend en PNG.
     *
     * @param colorRamp couleur de la catégorie ({@code #RRGGBB}), repli si absente
     * @param title     le titre du programme, mis en avant
     * @param subtitle  une ligne de contexte — activité, date, ville
     */
    public static byte[] render(String colorRamp, String title, String subtitle) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color base = parse(colorRamp);
            g.setPaint(new GradientPaint(0, 0, base, WIDTH, HEIGHT, darken(base, 0.55f)));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // Deux polices logiques, jamais un nom de famille : une police
            // installée sur le poste de développement peut manquer sur le
            // serveur, et Java retomberait alors sur un rendu différent sans
            // rien signaler.
            Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, TITLE_SIZE);
            Font subtitleFont = new Font(Font.SANS_SERIF, Font.PLAIN, SUBTITLE_SIZE);
            Font brandFont = new Font(Font.SANS_SERIF, Font.BOLD, BRAND_SIZE);

            g.setColor(INK);

            List<String> lines = wrap(g.getFontRenderContext(), titleFont,
                title == null ? "" : title, WIDTH - 2 * MARGIN, 3);

            int lineHeight = (int) (TITLE_SIZE * 1.18);
            int blockHeight = lines.size() * lineHeight + (subtitle == null ? 0 : SUBTITLE_SIZE + 28);
            int y = (HEIGHT - blockHeight) / 2 + TITLE_SIZE;

            g.setFont(titleFont);
            for (String line : lines) {
                g.drawString(line, MARGIN, y);
                y += lineHeight;
            }

            if (subtitle != null && !subtitle.isBlank()) {
                g.setFont(subtitleFont);
                // Légèrement en retrait : la ligne de contexte ne doit pas
                // disputer la lecture au titre dans une vignette de 300 px de
                // large, qui est la taille réelle dans une conversation.
                g.setColor(new Color(255, 255, 255, 214));
                g.drawString(ellipsize(g.getFontRenderContext(), subtitleFont, subtitle,
                    WIDTH - 2 * MARGIN), MARGIN, y + 12);
            }

            g.setFont(brandFont);
            g.setColor(new Color(255, 255, 255, 200));
            g.drawString("meetDo", MARGIN, HEIGHT - MARGIN + BRAND_SIZE / 2);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } finally {
            g.dispose();
        }
    }

    /**
     * Couleur de la catégorie, ou celle de la marque.
     *
     * <p>Une valeur illisible ne fait pas échouer la vignette : perdre l'aperçu
     * entier parce qu'une couleur est mal saisie coûterait bien plus que perdre
     * la couleur.
     */
    private static Color parse(String colorRamp) {
        if (colorRamp == null || colorRamp.isBlank()) {
            return DEFAULT_COLOR;
        }
        try {
            return Color.decode(colorRamp.strip().startsWith("#")
                ? colorRamp.strip() : "#" + colorRamp.strip());
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }

    private static Color darken(Color color, float factor) {
        return new Color(
            Math.round(color.getRed() * factor),
            Math.round(color.getGreen() * factor),
            Math.round(color.getBlue() * factor));
    }

    /** Découpe sur les espaces, en s'arrêtant après {@code maxLines}. */
    private static List<String> wrap(FontRenderContext frc, Font font, String text,
                                     int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(frc, font, candidate) <= maxWidth || current.isEmpty()) {
                current.setLength(0);
                current.append(candidate);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
                if (lines.size() == maxLines) {
                    break;
                }
            }
        }
        if (lines.size() < maxLines && !current.isEmpty()) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        // Un titre plus long que la place disponible est coupé, jamais rétréci :
        // réduire la taille du texte pour tout faire tenir donnerait des
        // vignettes dont la typographie change d'un créneau à l'autre.
        int last = lines.size() - 1;
        lines.set(last, ellipsize(frc, font, lines.get(last), maxWidth));
        return lines;
    }

    private static String ellipsize(FontRenderContext frc, Font font, String text, int maxWidth) {
        if (width(frc, font, text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && width(frc, font, cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + "…";
    }

    private static double width(FontRenderContext frc, Font font, String text) {
        return font.getStringBounds(text, frc).getWidth();
    }
}
