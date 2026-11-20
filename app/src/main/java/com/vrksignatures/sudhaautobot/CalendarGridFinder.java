package com.vrksignatures.sudhaautobot;

import android.graphics.Rect;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the Copy Order calendar from the screen's pixels: where each day is drawn, and
 * which day carries the filled highlight circle.
 *
 * <p>This is the second opinion behind {@link CalendarDateSelector}. The selector works on
 * the accessibility tree, which can only be reasoned about; the pixels are what the person
 * holding the phone sees. Two things are done with them:</p>
 * <ul>
 *   <li><b>Checking</b> - before OK is pressed, the highlight circle must sit on the
 *       requested day. If it is still on the present day, nothing is confirmed.</li>
 *   <li><b>Tapping</b> - when the tree offers no usable day cell, or pressing it changed
 *       nothing, the cell's position is taken from the drawn grid and pressed with a real
 *       touch.</li>
 * </ul>
 *
 * <p>Nothing here is a hard-coded coordinate. The grid is measured on every call:</p>
 * <ol>
 *   <li>the dialog is the white card on the dimmed screen;</li>
 *   <li>inside the card, rows of ink from the top are: month title, weekday letters, then
 *       the weeks, then the buttons;</li>
 *   <li>the seven weekday letters give the seven column centres;</li>
 *   <li>the highlight is the one strongly coloured, solid, round blob among the weeks.</li>
 * </ol>
 *
 * <p>Then the calendar itself is the cross-check. For the month in question the first day
 * must start in the column its weekday dictates, the last day must end where it should,
 * and the number of week rows must match. Two neighbouring months never share all three,
 * so a reading that passes is a reading of that month. If the picture disagrees with the
 * calendar, the reading is rejected and nothing is tapped.</p>
 *
 * <p>Pure integer/float maths on an {@code int[]} of ARGB pixels; only {@link Rect} comes
 * from Android. That is what lets the self-test run this class against real frames of the
 * device's own screen recording.</p>
 */
public final class CalendarGridFinder {

    /** What the pixels say about the calendar. */
    public static final class Reading {
        /** Null when the grid was read; otherwise why it could not be. */
        public final String problem;
        /** The dialog's white card. */
        public final Rect card;
        /** The filled highlight circle, or null when this month shows none. */
        public final Rect disc;
        /** Day of month the highlight sits on; 0 when this month shows no highlight. */
        public final int selectedDay;
        /** The "previous month" arrow beside the month title, or null if not drawn. */
        public final Rect previousMonthArrow;
        public final int rows;
        public final float colPitch;
        public final float rowPitch;
        private final float col0X;
        private final float row0Y;
        private final int offset;
        private final int daysInMonth;

        private Reading(String problem) {
            this(problem, null, null, -1, null, 0, 0f, 0f, 0f, 0f, 0, 0);
        }

        private Reading(String problem, Rect card, Rect disc, int selectedDay,
                        Rect previousMonthArrow, int rows,
                        float colPitch, float rowPitch, float col0X, float row0Y,
                        int offset, int daysInMonth) {
            this.problem = problem;
            this.card = card;
            this.disc = disc;
            this.selectedDay = selectedDay;
            this.previousMonthArrow = previousMonthArrow;
            this.rows = rows;
            this.colPitch = colPitch;
            this.rowPitch = rowPitch;
            this.col0X = col0X;
            this.row0Y = row0Y;
            this.offset = offset;
            this.daysInMonth = daysInMonth;
        }

        public boolean ok() {
            return problem == null;
        }

        /** Where to press for {@code day}: the middle part of its cell. Null if out of range. */
        public Rect cellOf(int day) {
            if (!ok() || day < 1 || day > daysInMonth) return null;
            int index = offset + day - 1;
            float cx = col0X + (index % 7) * colPitch;
            float cy = row0Y + (index / 7) * rowPitch;
            int halfW = Math.max(6, Math.round(colPitch * 0.36f));
            int halfH = Math.max(6, Math.round(rowPitch * 0.36f));
            return new Rect(Math.round(cx) - halfW, Math.round(cy) - halfH,
                    Math.round(cx) + halfW, Math.round(cy) + halfH);
        }

        @Override
        public String toString() {
            if (!ok()) return "unreadable: " + problem;
            return String.format(Locale.US, "grid %dx7 pitch %.1fx%.1f, %s",
                    rows, colPitch, rowPitch,
                    selectedDay > 0 ? "highlight on day " + selectedDay : "no highlight");
        }
    }

    private CalendarGridFinder() {
    }

    /**
     * Reads the calendar for {@code month}.
     *
     * @param weekStart the weekday in the first column - the device's own setting. It is
     *                  NOT guessed: any 31-day month fits the grid of any other 31-day
     *                  month if the first weekday may be chosen freely, and the whole point
     *                  of this check is to tell one month from the next.
     */
    public static Reading read(int[] argb, int width, int height, YearMonth month,
                               DayOfWeek weekStart) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) {
            return new Reading("no screenshot");
        }
        if (month == null || weekStart == null) return new Reading("no month given");
        Layout layout = new Layout();
        String problem = measure(argb, width, height, layout);
        if (problem != null) return new Reading(problem);
        return fit(layout, month, weekStart);
    }

    /** Everything measured from the picture, before any month is assumed. */
    private static final class Layout {
        Rect card;
        Rect disc;
        Rect arrow;
        int weekRows;
        float colPitch;
        float rowPitch;
        float col0X;
        float row0Y;
        /** Column of the first inked cell of the first week, and of the last of the last. */
        int firstCol;
        int lastCol;
        int discRow = -1;
        int discCol = -1;
    }

    private static String measure(int[] argb, int width, int height, Layout out) {
        // ---- 1. The white card. Between two weeks there is only paper from edge to edge,
        //         so those rows give the card's sides; rows with text only give fragments.
        int minRun = Math.round(width * 0.35f);
        int[] lefts = new int[height];
        int[] rights = new int[height];
        int samples = 0;
        for (int y = 0; y < height; y += 2) {
            int row = y * width;
            int bestL = -1, bestR = -1, start = -1;
            for (int x = 0; x <= width; x++) {
                boolean paper = x < width && isPaper(argb[row + x]);
                if (paper && start < 0) {
                    start = x;
                } else if (!paper && start >= 0) {
                    if (x - start > bestR - bestL + 1 || bestL < 0) {
                        bestL = start;
                        bestR = x - 1;
                    }
                    start = -1;
                }
            }
            if (bestL >= 0 && bestR - bestL + 1 >= minRun) {
                lefts[samples] = bestL;
                rights[samples] = bestR;
                samples++;
            }
        }
        if (samples < 8) return "no white dialog card is visible";
        int left = mostCommon(lefts, samples);
        int right = mostCommon(rights, samples);
        if (right - left + 1 < minRun) return "no white dialog card is visible";

        // The card's side margin holds no ink, so it runs clean from the coloured title
        // bar above to the bottom edge of the dialog. Take the longest such run.
        int marginX = left + Math.max(2, (right - left) / 150);
        int top = -1, bottom = -1, runStart = -1;
        for (int y = 0; y <= height; y++) {
            boolean paper = y < height && isPaper(argb[y * width + marginX]);
            if (paper && runStart < 0) {
                runStart = y;
            } else if (!paper && runStart >= 0) {
                if (top < 0 || y - runStart > bottom - top + 1) {
                    top = runStart;
                    bottom = y - 1;
                }
                runStart = -1;
            }
        }
        if (top < 0 || bottom - top < (right - left) / 2) {
            return "the white card is too short to be the calendar";
        }
        out.card = new Rect(left, top, right + 1, bottom + 1);

        // ---- 2. Rows of ink, top to bottom: title, weekday letters, weeks, buttons.
        int x0 = left + 2;
        int x1 = right - 2;
        int unit = (right - left + 1) / 7;        // roughly one column
        List<int[]> bands = inkBands(argb, width, x0, x1, top, bottom, Math.max(3, unit / 9));
        int weekRows = bands.size() - 3;          // minus title, weekdays, buttons
        if (weekRows < 4 || weekRows > 6) {
            return "expected 4-6 week rows between the weekday letters and the buttons, saw "
                    + weekRows;
        }
        out.weekRows = weekRows;

        // Week rows must be evenly spaced; that is what makes them weeks.
        float[] rowCentres = new float[weekRows];
        for (int r = 0; r < weekRows; r++) {
            int[] band = bands.get(r + 2);
            rowCentres[r] = (band[0] + band[1]) / 2f;
        }
        float rowPitch = (rowCentres[weekRows - 1] - rowCentres[0]) / (weekRows - 1);
        if (rowPitch < unit * 0.5f || rowPitch > unit * 2.5f) {
            return "week rows are not spaced like a calendar";
        }
        for (int r = 0; r < weekRows; r++) {
            if (Math.abs(rowCentres[r] - (rowCentres[0] + r * rowPitch)) > rowPitch * 0.15f) {
                return "week rows are unevenly spaced";
            }
        }
        out.rowPitch = rowPitch;
        out.row0Y = rowCentres[0];

        // ---- 3. Seven weekday letters give the seven columns.
        int[] weekdays = bands.get(1);
        int joinGap = Math.max(4, unit / 6);
        List<float[]> letters = inkClusters(argb, width, x0, x1, weekdays[0], weekdays[1], joinGap);
        if (letters.size() != 7) return "expected 7 weekday letters, saw " + letters.size();
        float first = letters.get(0)[2];
        float colPitch = (letters.get(6)[2] - first) / 6f;
        if (colPitch < unit * 0.5f || colPitch > unit * 1.5f) {
            return "weekday letters are not spaced like a calendar";
        }
        float drift = 0f;
        for (int c = 0; c < 7; c++) {
            float off = letters.get(c)[2] - (first + c * colPitch);
            if (Math.abs(off) > colPitch * 0.2f) return "weekday letters are unevenly spaced";
            drift += off;
        }
        out.colPitch = colPitch;
        out.col0X = first + drift / 7f;

        // ---- 4. Where the month starts and ends on the grid.
        int[] firstWeek = bands.get(2);
        List<float[]> firstCells = inkClusters(argb, width, x0, x1, firstWeek[0], firstWeek[1], joinGap);
        int[] lastWeek = bands.get(2 + weekRows - 1);
        List<float[]> lastCells = inkClusters(argb, width, x0, x1, lastWeek[0], lastWeek[1], joinGap);
        if (firstCells.isEmpty() || lastCells.isEmpty()) return "first or last week is empty";
        out.firstCol = Math.round((firstCells.get(0)[2] - out.col0X) / colPitch);
        out.lastCol = Math.round((lastCells.get(lastCells.size() - 1)[2] - out.col0X) / colPitch);

        // ---- 5. The "previous month" arrow: the first mark on the title row, well to the
        //         left of the month's name.
        int[] title = bands.get(0);
        List<float[]> marks = inkClusters(argb, width, x0, x1, title[0], title[1], joinGap);
        if (marks.size() >= 2 && marks.get(0)[1] < left + (right - left) * 0.30f) {
            int cx = Math.round(marks.get(0)[2]);
            int cy = (title[0] + title[1]) / 2;
            int half = Math.max(8, Math.round(colPitch * 0.3f));
            out.arrow = new Rect(cx - half, cy - half, cx + half, cy + half);
        }

        // ---- 6. The highlight circle, if this month shows one.
        int gridTop = Math.max(top, Math.round(out.row0Y - rowPitch * 0.75f));
        int gridBottom = Math.min(bottom, Math.round(out.row0Y + (weekRows - 1) * rowPitch + rowPitch * 0.75f));
        int[] count = new int[1];
        Rect disc = findHighlight(argb, width, height, new Rect(left, gridTop, right + 1, gridBottom + 1),
                Math.round(colPitch * 0.6f), Math.round(Math.max(colPitch, rowPitch) * 1.35f), count);
        if (count[0] > 1) return "more than one day looks highlighted";
        if (disc != null) {
            int col = Math.round((disc.exactCenterX() - out.col0X) / colPitch);
            int row = Math.round((disc.exactCenterY() - out.row0Y) / rowPitch);
            if (col < 0 || col > 6 || row < 0 || row >= weekRows
                    || Math.abs(disc.exactCenterX() - (out.col0X + col * colPitch)) > colPitch * 0.25f
                    || Math.abs(disc.exactCenterY() - (out.row0Y + row * rowPitch)) > rowPitch * 0.25f) {
                return "the highlight is not centred on a day";
            }
            out.disc = disc;
            out.discCol = col;
            out.discRow = row;
        }
        return null;
    }

    /** Checks the measured grid against the calendar for {@code month}. */
    private static Reading fit(Layout layout, YearMonth month, DayOfWeek weekStart) {
        int daysInMonth = month.lengthOfMonth();
        int offset = (month.atDay(1).getDayOfWeek().getValue() - weekStart.getValue() + 7) % 7;
        int expectedRows = (offset + daysInMonth + 6) / 7;
        int expectedLastCol = (offset + daysInMonth - 1) % 7;
        if (expectedRows != layout.weekRows || layout.firstCol != offset
                || layout.lastCol != expectedLastCol) {
            return new Reading("the month on screen is not " + month + " (" + layout.weekRows
                    + " week rows, first day in column " + layout.firstCol + ", last in column "
                    + layout.lastCol + ", week starting " + weekStart + ")");
        }
        int selectedDay = 0;
        if (layout.disc != null) {
            selectedDay = layout.discRow * 7 + layout.discCol - offset + 1;
            if (selectedDay < 1 || selectedDay > daysInMonth) {
                return new Reading("the highlight is not on a day of " + month);
            }
        }
        return new Reading(null, layout.card, layout.disc, selectedDay, layout.arrow,
                layout.weekRows, layout.colPitch, layout.rowPitch, layout.col0X, layout.row0Y,
                offset, daysInMonth);
    }

    /** The value that occurs most often among the first {@code n} entries (within +-1). */
    private static int mostCommon(int[] values, int n) {
        int best = values[0];
        int bestVotes = 0;
        for (int i = 0; i < n; i++) {
            int votes = 0;
            for (int j = 0; j < n; j++) {
                if (Math.abs(values[j] - values[i]) <= 1) votes++;
            }
            if (votes > bestVotes) {
                bestVotes = votes;
                best = values[i];
            }
        }
        return best;
    }

    /**
     * True when the cell holds a day drawn in dark ink — i.e. a day that can be chosen.
     * Days the dialog does not allow are drawn pale grey.
     */
    public static boolean looksSelectable(int[] argb, int width, int height, Rect cell) {
        if (cell == null) return false;
        int darkest = 255;
        for (int y = Math.max(0, cell.top); y < Math.min(height, cell.bottom); y++) {
            int row = y * width;
            for (int x = Math.max(0, cell.left); x < Math.min(width, cell.right); x++) {
                int c = argb[row + x];
                int max = Math.max((c >> 16) & 0xFF, Math.max((c >> 8) & 0xFF, c & 0xFF));
                if (max < darkest) darkest = max;
            }
        }
        return darkest < 130;
    }

    // ---------------------------------------------------------------- pixels

    /**
     * The dialog's background. The threshold sits between white and the palest ink the
     * dialog uses (the grey of days that cannot be chosen, about 193), with room on both
     * sides so neither display dithering nor a slightly off-white theme tips it over.
     */
    private static boolean isPaper(int c) {
        return ((c >> 16) & 0xFF) >= 228 && ((c >> 8) & 0xFF) >= 228 && (c & 0xFF) >= 228;
    }

    /** A strongly coloured pixel: the accent colour, whatever hue the theme uses. */
    private static boolean isVivid(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max >= 150 && (max - min) * 100 >= max * 45;
    }

    /**
     * Runs of rows that contain any ink between x0 and x1; each entry is {top, bottom}.
     * Runs thinner than {@code minHeight} are hairlines (a divider, the card's edge), not
     * text, and are dropped.
     */
    private static List<int[]> inkBands(int[] argb, int width, int x0, int x1, int top, int bottom,
                                        int minHeight) {
        List<int[]> bands = new ArrayList<>(10);
        int start = -1;
        for (int y = top; y <= bottom + 1; y++) {
            boolean ink = false;
            if (y <= bottom) {
                int row = y * width;
                for (int x = x0; x <= x1; x++) {
                    if (!isPaper(argb[row + x])) {
                        ink = true;
                        break;
                    }
                }
            }
            if (ink && start < 0) {
                start = y;
            } else if (!ink && start >= 0) {
                if (y - start >= minHeight) bands.add(new int[]{start, y - 1});
                start = -1;
            }
        }
        return bands;
    }

    /**
     * Runs of columns that contain ink within rows y0..y1; gaps narrower than
     * {@code joinGap} are bridged so the two digits of "17" count as one cell. Each entry
     * is {left, right, centre}.
     */
    private static List<float[]> inkClusters(int[] argb, int width, int x0, int x1,
                                             int y0, int y1, int joinGap) {
        List<float[]> clusters = new ArrayList<>(8);
        int start = -1;
        int last = -1;
        for (int x = x0; x <= x1; x++) {
            boolean ink = false;
            for (int y = y0; y <= y1; y++) {
                if (!isPaper(argb[y * width + x])) {
                    ink = true;
                    break;
                }
            }
            if (!ink) continue;
            if (start >= 0 && x - last > joinGap) {
                clusters.add(new float[]{start, last, (start + last) / 2f});
                start = -1;
            }
            if (start < 0) start = x;
            last = x;
        }
        if (start >= 0) clusters.add(new float[]{start, last, (start + last) / 2f});
        return clusters;
    }

    /**
     * The filled highlight circle: a vivid, solid, round blob of day-cell size that is
     * surrounded by paper. Only {@code area} is searched. {@code count[0]} receives how many
     * such blobs were found; the result is null unless there is exactly one.
     */
    private static Rect findHighlight(int[] argb, int width, int height, Rect area,
                                      int minSize, int maxSize, int[] count) {
        int ax0 = Math.max(0, area.left), ay0 = Math.max(0, area.top);
        int ax1 = Math.min(width, area.right), ay1 = Math.min(height, area.bottom);
        int aw = ax1 - ax0, ah = ay1 - ay0;
        count[0] = 0;
        if (aw <= 0 || ah <= 0) return null;
        boolean[] seen = new boolean[aw * ah];
        int[] stack = new int[aw * ah];

        Rect best = null;
        for (int sy = 0; sy < ah; sy++) {
            for (int sx = 0; sx < aw; sx++) {
                int startIdx = sy * aw + sx;
                if (seen[startIdx] || !isVivid(argb[(ay0 + sy) * width + ax0 + sx])) continue;

                int topOfStack = 0;
                stack[topOfStack++] = startIdx;
                seen[startIdx] = true;
                int pixels = 0;
                int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
                while (topOfStack > 0) {
                    int idx = stack[--topOfStack];
                    int x = idx % aw;
                    int y = idx / aw;
                    pixels++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    for (int d = 0; d < 4; d++) {
                        int nx = x + (d == 0 ? -1 : d == 1 ? 1 : 0);
                        int ny = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
                        if (nx < 0 || ny < 0 || nx >= aw || ny >= ah) continue;
                        int n = ny * aw + nx;
                        if (seen[n] || !isVivid(argb[(ay0 + ny) * width + ax0 + nx])) continue;
                        seen[n] = true;
                        stack[topOfStack++] = n;
                    }
                }

                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;
                if (bw < minSize || bh < minSize || bw > maxSize || bh > maxSize) continue;
                float ratio = bw / (float) bh;
                if (ratio < 0.85f || ratio > 1.18f) continue;
                // A disc fills ~0.79 of its box; the white day number inside removes a little.
                float fill = pixels / (float) (bw * bh);
                if (fill < 0.55f) continue;
                if (!ringIsPaper(argb, width, height, ax0 + (minX + maxX) / 2f,
                        ay0 + (minY + maxY) / 2f, Math.max(bw, bh) / 2f)) {
                    continue;
                }
                count[0]++;
                best = new Rect(ax0 + minX, ay0 + minY, ax0 + maxX + 1, ay0 + maxY + 1);
            }
        }
        return count[0] == 1 ? best : null;
    }

    /** True when the ring just outside the blob is white: it lies on the dialog's card. */
    private static boolean ringIsPaper(int[] argb, int width, int height,
                                       float cx, float cy, float radius) {
        float ring = radius + Math.max(3f, radius * 0.16f);
        int samples = 48;
        int paper = 0;
        for (int i = 0; i < samples; i++) {
            double angle = (Math.PI * 2 * i) / samples;
            int x = Math.round(cx + (float) Math.cos(angle) * ring);
            int y = Math.round(cy + (float) Math.sin(angle) * ring);
            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            if (isPaper(argb[y * width + x])) paper++;
        }
        return paper >= samples * 0.85f;
    }
}
