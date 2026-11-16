import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.File;

/** Slozi snimek launcheru (s pruhlednosti) s ilustracnim pozadim "Quest domov". */
public class Compose {
    public static void main(String[] a) throws Exception {
        BufferedImage ui = ImageIO.read(new File(a[0]));
        int W = ui.getWidth(), H = ui.getHeight();
        float d = 2f; // xhdpi
        BufferedImage bg = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Zaklad jako v preview: radial-gradient(circle at 50% 35%, #1d2538, #0d111a 60%, #05070a)
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W * 0.5f, H * 0.35f), W * 0.75f,
                new float[]{0f, 0.6f, 1f},
                new Color[]{new Color(0x1d2538), new Color(0x0d111a), new Color(0x05070a)}));
        g.fillRect(0, 0, W, H);
        // "Mistnost": teple svetlo lampy vlevo, okno vpravo, modra zare uprostred
        blob(g, W * 0.12f, H * 0.30f, W * 0.22f, new Color(255, 170, 90, 150));
        blob(g, W * 0.86f, H * 0.22f, W * 0.20f, new Color(140, 200, 255, 140));
        blob(g, W * 0.70f, H * 0.85f, W * 0.18f, new Color(120, 90, 255, 110));
        blob(g, W * 0.30f, H * 0.88f, W * 0.16f, new Color(56, 189, 248, 90));
        // Mrizka hranice (Guardian) jako v preview
        g.setColor(new Color(255, 255, 255, 12));
        for (int x = 0; x < W; x += 120) g.drawLine(x, 0, x, H);
        for (int y = 0; y < H; y += 120) g.drawLine(0, y, W, y);
        g.dispose();

        // Systemove rozmazani prostredi za sklem (Quest 3/3S blend effects)
        // Panel: vlevo misto na listu s ikonami (88 dp), nahore pul ornamentu (36 dp).
        RoundRectangle2D frame = new RoundRectangle2D.Float(88 * d, 36 * d, W - 112 * d, H - 66 * d,
                56 * d, 56 * d);
        BufferedImage blurred = blur(bg, 22);
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D o = out.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        o.drawImage(bg, 0, 0, null);
        o.setClip(frame);
        o.drawImage(blurred, 0, 0, null);
        o.setClip(null);
        o.drawImage(ui, 0, 0, null);
        o.dispose();
        ImageIO.write(out, "png", new File(a[1]));
    }

    static void blob(Graphics2D g, float cx, float cy, float r, Color c) {
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r, new float[]{0f, 1f},
                new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
    }

    /** Rychly blur: zmensit, 3x box blur, zvetsit. */
    static BufferedImage blur(BufferedImage src, int radius) {
        int s = 4;
        int w = src.getWidth() / s, h = src.getHeight() / s;
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        int r = Math.max(1, radius / s);
        float[] k = new float[(2 * r + 1)];
        java.util.Arrays.fill(k, 1f / k.length);
        ConvolveOp hOp = new ConvolveOp(new Kernel(k.length, 1, k), ConvolveOp.EDGE_NO_OP, null);
        ConvolveOp vOp = new ConvolveOp(new Kernel(1, k.length, k), ConvolveOp.EDGE_NO_OP, null);
        for (int i = 0; i < 3; i++) small = vOp.filter(hOp.filter(small, null), null);
        BufferedImage big = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D b = big.createGraphics();
        b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        b.drawImage(small, 0, 0, src.getWidth(), src.getHeight(), null);
        b.dispose();
        return big;
    }
}
