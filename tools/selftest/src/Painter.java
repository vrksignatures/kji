import android.graphics.Rect;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Paints the date dialog as pixels, the way a screenshot would show it: dimmed screen,
 * white card under a coloured title bar, month title with its arrow, weekday letters, the
 * days, the highlight circle and the two buttons.
 *
 * <p>Proportions are the ones measured on the 720x1600 recording (see StockDatePicker).
 * Text is drawn as plain blocks of ink: the reader under test never reads characters, only
 * where ink is, so blocks exercise exactly the same code as glyphs. The real glyphs are
 * covered separately by the frames in fixtures/.</p>
 */
final class Painter {
    static final int SCRIM = 0xFF5F5F5F, PAPER = 0xFFFFFFFF, ACCENT = 0xFFFE7213;
    static final int INK = 0xFF1B1B1B, PALE = 0xFFC1C1C1, LETTER = 0xFF6E6E6E, PANEL = 0xFF263238;

    final int width, height;
    final int[] argb;
    private final double k;

    Painter(double scale) {
        k = scale;
        width = (int) Math.round(720 * scale);
        height = (int) Math.round(1600 * scale);
        argb = new int[width * height];
        java.util.Arrays.fill(argb, SCRIM);
    }

    /** Centre of a day cell, in the unscaled 720x1600 frame. */
    static double[] centreOf(YearMonth month, DayOfWeek weekStart, int day) {
        int offset = (month.atDay(1).getDayOfWeek().getValue() - weekStart.getValue() + 7) % 7;
        int index = offset + day - 1;
        return new double[]{161 + 66.0 * (index % 7), 718.5 + 75.0 * (index / 7)};
    }

    /**
     * @param selected the highlighted date (drawn only when it lies in {@code month})
     * @param today    drawn in the accent colour; later days are pale and cannot be chosen
     * @param minDate  days before it are pale too (null = none)
     * @param panel    the bot's floating panel in unscaled coordinates, or null
     */
    Painter dialog(YearMonth month, DayOfWeek weekStart, LocalDate selected, LocalDate today,
                   LocalDate minDate, Rect panel) {
        fill(90, 318, 630, 498, ACCENT);                 // title bar
        fill(90, 498, 630, 1249, PAPER);                 // card
        fill(155, 551, 167, 572, INK);                   // "<"
        fill(290, 550, 430, 572, INK);                   // "November 2026"
        if (YearMonth.from(today).isAfter(month)) fill(553, 551, 565, 572, INK);   // ">"
        for (int c = 0; c < 7; c++) {
            int cx = 161 + 66 * c;
            fill(cx - 6, 639, cx + 6, 656, LETTER);
        }
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            double[] at = centreOf(month, weekStart, day);
            boolean chosen = date.equals(selected);
            boolean allowed = !date.isAfter(today) && (minDate == null || !date.isBefore(minDate));
            int ink = chosen ? PAPER : date.equals(today) ? ACCENT : allowed ? INK : PALE;
            if (chosen) disc(at[0], at[1], 37);
            digits(at[0], at[1], day, ink);
        }
        fill(370, 1181, 460, 1201, ACCENT);              // CANCEL
        fill(530, 1181, 565, 1201, ACCENT);              // OK
        if (panel != null) {
            fill(panel.left, panel.top, panel.right, panel.bottom, PANEL);
            // A round accent-coloured button on the panel: must never be taken for a day.
            disc(panel.left + 60, panel.top + 60, 37);
        }
        return this;
    }

    private void digits(double cx, double cy, int day, int colour) {
        int top = (int) Math.round(cy - 8.5), bottom = top + 17;
        if (day < 10) {
            fill((int) Math.round(cx - 5), top, (int) Math.round(cx + 5), bottom, colour);
        } else {
            fill((int) Math.round(cx - 11), top, (int) Math.round(cx - 2), bottom, colour);
            fill((int) Math.round(cx + 2), top, (int) Math.round(cx + 11), bottom, colour);
        }
    }

    private void fill(double l, double t, double r, double b, int colour) {
        int x0 = (int) Math.round(l * k), x1 = (int) Math.round(r * k);
        int y0 = (int) Math.round(t * k), y1 = (int) Math.round(b * k);
        for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) argb[y * width + x] = colour;
        }
    }

    private void disc(double cx, double cy, double radius) {
        double r = radius * k, x0 = cx * k, y0 = cy * k;
        for (int y = (int) (y0 - r - 1); y <= y0 + r + 1; y++) {
            for (int x = (int) (x0 - r - 1); x <= x0 + r + 1; x++) {
                if (x < 0 || y < 0 || x >= width || y >= height) continue;
                double dx = x + 0.5 - x0, dy = y + 0.5 - y0;
                if (dx * dx + dy * dy <= r * r) argb[y * width + x] = ACCENT;
            }
        }
    }
}
