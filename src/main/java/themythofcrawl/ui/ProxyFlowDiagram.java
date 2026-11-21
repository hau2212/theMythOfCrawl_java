package themythofcrawl.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;

import javax.swing.JComponent;

import themythofcrawl.core.ProxyGate;
import themythofcrawl.ui.theme.Theme;

/**
 * V_7.1: a small live picture of what happens to a request that MATCHES the Proxy gate:
 * Trình duyệt -> Burp + JCrawl -> Server, with the arrows the chosen effect really produces
 * (straight through, stopped and waiting for you, fake 200 back to the browser, or cut).
 */
final class ProxyFlowDiagram extends JComponent {
    private boolean on;
    private ProxyGate.Effect effect = ProxyGate.Effect.ASK;

    ProxyFlowDiagram() {
        setOpaque(true);
    }

    void show(boolean gateOn, ProxyGate.Effect e) {
        this.on = gateOn;
        this.effect = e == null ? ProxyGate.Effect.ASK : e;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Theme th = Theme.current();
        return new Dimension(th.scaled(480), th.scaled(150));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme th = Theme.current();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            themythofcrawl.ui.theme.FontManager.applyTextHints(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(th.surface);
            g2.fillRect(0, 0, w, h);
            g2.setColor(th.border);
            g2.drawRect(0, 0, w - 1, h - 1);

            int bw = Math.min(th.scaled(190), Math.max(th.scaled(96), w / 5));
            int bh = th.scaled(46);
            int top = th.scaled(30);
            int bx1 = th.scaled(16);
            int bx3 = w - bw - th.scaled(16);
            int bx2 = (bx1 + bx3) / 2;
            int cy = top + bh / 2;

            Theme.Token ok = th.success;
            Theme.Token warn = th.warning;
            Theme.Token bad = th.danger;
            Theme.Token mid = !on ? th.neutral : switch (effect) {
                case ASK -> warn;
                case FAKE_200 -> th.info;
                case DROP -> bad;
            };

            box(g2, th, bx1, top, bw, bh, "Trình duyệt", "bạn bấm nút trên web", th.neutral);
            box(g2, th, bx2, top, bw, bh, "Burp + JCrawl", !on ? "cho qua" : switch (effect) {
                case ASK -> "DỪNG · chờ bạn";
                case FAKE_200 -> "lưu request · trả 200 giả";
                case DROP -> "lưu request · cắt";
            }, mid);
            boolean serverGets = !on || effect == ProxyGate.Effect.ASK;
            box(g2, th, bx3, top, bw, bh, "Server", serverGets ? (on ? "chỉ khi bạn bấm Gửi" : "nhận request thật")
                    : "KHÔNG nhận gì", serverGets ? th.neutral : bad);

            // request: browser -> burp
            arrow(g2, th, bx1 + bw, cy - th.scaled(6), bx2, cy - th.scaled(6), ok.base(), false, "request", true);
            // burp -> server
            if (!on) {
                arrow(g2, th, bx2 + bw, cy - th.scaled(6), bx3, cy - th.scaled(6), ok.base(), false, "đi thẳng", true);
                arrow(g2, th, bx3, cy + th.scaled(8), bx1 + bw, cy + th.scaled(8), ok.base(), false, "response thật", false);
            } else if (effect == ProxyGate.Effect.ASK) {
                arrow(g2, th, bx2 + bw, cy - th.scaled(6), bx3, cy - th.scaled(6), warn.base(), true, "khi bấm Gửi", true);
                arrow(g2, th, bx3, cy + th.scaled(8), bx2 + bw, cy + th.scaled(8), warn.base(), true, "response thật", false);
                arrow(g2, th, bx2, cy + th.scaled(8), bx1 + bw, cy + th.scaled(8), warn.base(), true, "trình duyệt chờ", false);
            } else {
                cross(g2, th, (bx2 + bw + bx3) / 2, cy - th.scaled(6), bad.base());
                g2.setColor(bad.text());
                g2.setFont(th.smallBoldFont);
                drawCentered(g2, "không gửi", (bx2 + bw + bx3) / 2, cy - th.scaled(16));
                String back = effect == ProxyGate.Effect.FAKE_200 ? "200 giả {held_by_jcrawl}" : "lỗi kết nối";
                arrow(g2, th, bx2, cy + th.scaled(8), bx1 + bw, cy + th.scaled(8),
                        effect == ProxyGate.Effect.FAKE_200 ? th.info.base() : bad.base(), false, back, false);
            }

            // caption
            g2.setFont(th.baseFont);
            g2.setColor(th.fg);
            FontMetrics fm = g2.getFontMetrics();
            String line1 = !on ? "Cổng chặn TẮT: mọi request đi thẳng, JCrawl chỉ ghi lại API." : "Sơ đồ cho request KHỚP phạm vi + method đã chọn.";
            String line2 = !on ? "" : "Request KHÔNG khớp phạm vi / method đã chọn: vẫn đi thẳng lên Server như bình thường.";
            int ty = top + bh + th.scaled(40);
            drawWrapped(g2, fm, line1, th.scaled(16), ty, w - th.scaled(32));
            g2.setColor(th.muted);
            g2.setFont(th.smallFont);
            g2.drawString(line2, th.scaled(16), h - th.scaled(10));
        } finally {
            g2.dispose();
        }
    }

    private static void box(Graphics2D g2, Theme th, int x, int y, int w, int h, String title, String sub, Theme.Token tok) {
        g2.setColor(tok.fill());
        g2.fillRect(x, y, w, h);
        g2.setColor(tok.border());
        g2.drawRect(x, y, w, h);
        g2.setColor(tok.text());
        g2.setFont(th.boldFont);
        drawCentered(g2, title, x + w / 2, y + th.scaled(18));
        g2.setFont(th.smallFont);
        drawCentered(g2, sub, x + w / 2, y + th.scaled(36));
    }

    private static void arrow(Graphics2D g2, Theme th, int x1, int y1, int x2, int y2, Color c, boolean dashed, String label,
                              boolean labelAbove) {
        Stroke prev = g2.getStroke();
        g2.setColor(c);
        g2.setStroke(dashed ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f)
                : new BasicStroke(1.8f));
        int pad = th.scaled(4);
        int sx = x1 < x2 ? x1 + pad : x1 - pad;
        int ex = x1 < x2 ? x2 - pad : x2 + pad;
        g2.drawLine(sx, y1, ex, y2);
        g2.setStroke(prev);
        int a = th.scaled(6);
        int dir = x1 < x2 ? 1 : -1;
        Polygon head = new Polygon(new int[] {ex, ex - dir * a, ex - dir * a}, new int[] {y2, y2 - a / 2 - 1, y2 + a / 2 + 1}, 3);
        g2.fillPolygon(head);
        g2.setFont(th.smallFont);
        drawCentered(g2, label, (sx + ex) / 2, labelAbove ? y1 - th.scaled(5) : y1 + th.scaled(14));
    }

    private static void cross(Graphics2D g2, Theme th, int cx, int cy, Color c) {
        Stroke prev = g2.getStroke();
        g2.setStroke(new BasicStroke(2.4f));
        g2.setColor(c);
        int r = th.scaled(7);
        g2.drawLine(cx - r, cy - r, cx + r, cy + r);
        g2.drawLine(cx - r, cy + r, cx + r, cy - r);
        g2.setStroke(prev);
    }

    private static void drawCentered(Graphics2D g2, String s, int cx, int baseline) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(s, cx - fm.stringWidth(s) / 2, baseline);
    }

    private static void drawWrapped(Graphics2D g2, FontMetrics fm, String text, int x, int y, int maxW) {
        StringBuilder line = new StringBuilder();
        int yy = y;
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(test) > maxW && line.length() > 0) {
                g2.drawString(line.toString(), x, yy);
                yy += fm.getHeight();
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            g2.drawString(line.toString(), x, yy);
        }
    }
}
