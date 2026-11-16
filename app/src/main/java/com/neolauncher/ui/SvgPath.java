package com.neolauncher.ui;

/**
 * Minimalni parser SVG "path d" (M L H V C S Q T A Z, velka i mala pismena).
 * Oblouky (A) se prevadi na kubicke Bezierovy krivky. Nezavisi na Androidu
 * (vystup jde do Sink), aby sel otestovat i na obycejne JVM.
 */
final class SvgPath {
    private SvgPath() {}

    interface Sink {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);

        void quadTo(float x1, float y1, float x, float y);

        void close();
    }

    /** Adapter pro android.graphics.Path. */
    static Sink of(final android.graphics.Path p) {
        return new Sink() {
            @Override
            public void moveTo(float x, float y) {
                p.moveTo(x, y);
            }

            @Override
            public void lineTo(float x, float y) {
                p.lineTo(x, y);
            }

            @Override
            public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) {
                p.cubicTo(x1, y1, x2, y2, x, y);
            }

            @Override
            public void quadTo(float x1, float y1, float x, float y) {
                p.quadTo(x1, y1, x, y);
            }

            @Override
            public void close() {
                p.close();
            }
        };
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length()) {
                final char ch = s.charAt(i);
                if (ch == ' ' || ch == ',' || ch == '\n' || ch == '\t' || ch == '\r') i++;
                else break;
            }
        }

        boolean hasNumber() {
            skip();
            if (i >= s.length()) return false;
            final char ch = s.charAt(i);
            return (ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.';
        }

        float number() {
            skip();
            final int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, digits = false;
            while (i < s.length()) {
                final char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    digits = true;
                    i++;
                } else if (ch == '.' && !dot) {
                    dot = true;
                    i++;
                } else {
                    break;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E') && digits) {
                int j = i + 1;
                if (j < s.length() && (s.charAt(j) == '-' || s.charAt(j) == '+')) j++;
                if (j < s.length() && Character.isDigit(s.charAt(j))) {
                    i = j;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
            }
            if (start == i) throw new IllegalArgumentException("cekal jsem cislo na pozici " + i + ": " + s);
            return Float.parseFloat(s.substring(start, i));
        }

        /** Priznak oblouku: jediny znak 0/1 (muze byt nalepeny na dalsi cislo). */
        boolean flag() {
            skip();
            final char ch = s.charAt(i++);
            if (ch != '0' && ch != '1') throw new IllegalArgumentException("spatny priznak oblouku: " + s);
            return ch == '1';
        }
    }

    static void parse(String d, Sink out) {
        final Reader r = new Reader(d);
        float cx = 0, cy = 0, sx = 0, sy = 0;
        // Posledni ridici bod (pro S a T) a posledni prikaz.
        float lx = 0, ly = 0;
        char last = ' ';
        char cmd = ' ';
        while (true) {
            r.skip();
            if (r.i >= d.length()) break;
            final char ch = d.charAt(r.i);
            if (Character.isLetter(ch) && ch != 'e' && ch != 'E') {
                cmd = ch;
                r.i++;
            } else if (cmd == ' ') {
                throw new IllegalArgumentException("cesta nezacina prikazem: " + d);
            }
            // (jinak opakovani predchoziho prikazu s dalsimi cisly)
            final boolean rel = Character.isLowerCase(cmd);
            final float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    cx = ox + r.number();
                    cy = oy + r.number();
                    sx = cx;
                    sy = cy;
                    out.moveTo(cx, cy);
                    // Dalsi dvojice po M jsou L (po m jsou l).
                    cmd = rel ? 'l' : 'L';
                    last = 'M';
                    break;
                }
                case 'L':
                    cx = ox + r.number();
                    cy = oy + r.number();
                    out.lineTo(cx, cy);
                    last = 'L';
                    break;
                case 'H':
                    cx = ox + r.number();
                    out.lineTo(cx, cy);
                    last = 'L';
                    break;
                case 'V':
                    cy = oy + r.number();
                    out.lineTo(cx, cy);
                    last = 'L';
                    break;
                case 'C': {
                    final float x1 = ox + r.number(), y1 = oy + r.number();
                    final float x2 = ox + r.number(), y2 = oy + r.number();
                    cx = ox + r.number();
                    cy = oy + r.number();
                    out.cubicTo(x1, y1, x2, y2, cx, cy);
                    lx = x2;
                    ly = y2;
                    last = 'C';
                    break;
                }
                case 'S': {
                    final float x1 = last == 'C' ? 2 * cx - lx : cx, y1 = last == 'C' ? 2 * cy - ly : cy;
                    final float x2 = ox + r.number(), y2 = oy + r.number();
                    cx = ox + r.number();
                    cy = oy + r.number();
                    out.cubicTo(x1, y1, x2, y2, cx, cy);
                    lx = x2;
                    ly = y2;
                    last = 'C';
                    break;
                }
                case 'Q': {
                    final float x1 = ox + r.number(), y1 = oy + r.number();
                    cx = ox + r.number();
                    cy = oy + r.number();
                    out.quadTo(x1, y1, cx, cy);
                    lx = x1;
                    ly = y1;
                    last = 'Q';
                    break;
                }
                case 'T': {
                    final float x1 = last == 'Q' ? 2 * cx - lx : cx, y1 = last == 'Q' ? 2 * cy - ly : cy;
                    cx = ox + r.number();
                    cy = oy + r.number();
                    out.quadTo(x1, y1, cx, cy);
                    lx = x1;
                    ly = y1;
                    last = 'Q';
                    break;
                }
                case 'A': {
                    final float rx = r.number(), ry = r.number(), rot = r.number();
                    final boolean large = r.flag(), sweep = r.flag();
                    final float x = ox + r.number(), y = oy + r.number();
                    arc(out, cx, cy, rx, ry, rot, large, sweep, x, y);
                    cx = x;
                    cy = y;
                    last = 'A';
                    break;
                }
                case 'Z':
                    out.close();
                    cx = sx;
                    cy = sy;
                    last = 'Z';
                    // Z nema cisla - dalsi znak musi byt prikaz.
                    cmd = ' ';
                    r.skip();
                    if (r.i < d.length() && !Character.isLetter(d.charAt(r.i))) {
                        throw new IllegalArgumentException("cisla po Z: " + d);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("neznamy prikaz " + cmd + ": " + d);
            }
        }
    }

    /** Eliptický oblouk (SVG, dodatek F.6.5) jako kubicke krivky po max. 90 stupnich. */
    static void arc(Sink out, float x1, float y1, float rxIn, float ryIn, float rotDeg,
                    boolean large, boolean sweep, float x2, float y2) {
        if (x1 == x2 && y1 == y2) return;
        double rx = Math.abs(rxIn), ry = Math.abs(ryIn);
        if (rx == 0 || ry == 0) {
            out.lineTo(x2, y2);
            return;
        }
        final double phi = Math.toRadians(rotDeg);
        final double cos = Math.cos(phi), sin = Math.sin(phi);
        final double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
        final double x1p = cos * dx2 + sin * dy2;
        final double y1p = -sin * dx2 + cos * dy2;
        final double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            final double k = Math.sqrt(lambda);
            rx *= k;
            ry *= k;
        }
        final double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        final double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = den == 0 ? 0 : Math.sqrt(Math.max(0, num / den));
        if (large == sweep) coef = -coef;
        final double cxp = coef * rx * y1p / ry;
        final double cyp = -coef * ry * x1p / rx;
        final double ccx = cos * cxp - sin * cyp + (x1 + x2) / 2.0;
        final double ccy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        final double ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry;
        final double vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry;
        final double theta1 = Math.atan2(uy, ux);
        double dtheta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && dtheta > 0) dtheta -= 2 * Math.PI;
        else if (sweep && dtheta < 0) dtheta += 2 * Math.PI;
        final int segs = Math.max(1, (int) Math.ceil(Math.abs(dtheta) / (Math.PI / 2) - 1e-7));
        final double delta = dtheta / segs;
        final double t = 4.0 / 3.0 * Math.tan(delta / 4);
        double a = theta1;
        double px = x1, py = y1;
        for (int i = 0; i < segs; i++) {
            final double b = a + delta;
            final double cosA = Math.cos(a), sinA = Math.sin(a), cosB = Math.cos(b), sinB = Math.sin(b);
            // Derivace bodu na elipse v uhlu a a b.
            final double dax = -rx * sinA * cos - ry * cosA * sin, day = -rx * sinA * sin + ry * cosA * cos;
            final double dbx = -rx * sinB * cos - ry * cosB * sin, dby = -rx * sinB * sin + ry * cosB * cos;
            double qx = ccx + rx * cosB * cos - ry * sinB * sin;
            double qy = ccy + rx * cosB * sin + ry * sinB * cos;
            if (i == segs - 1) {
                qx = x2;
                qy = y2;
            }
            out.cubicTo((float) (px + t * dax), (float) (py + t * day),
                    (float) (qx - t * dbx), (float) (qy - t * dby), (float) qx, (float) qy);
            px = qx;
            py = qy;
            a = b;
        }
    }
}
