package com.vrksignatures.sudhaautobot;

import android.graphics.Rect;

/**
 * Locates the floating "+" button from raw screen pixels.
 *
 * <p>Why this exists: the button in the target app paints itself but exposes nothing
 * useful to the accessibility tree (no label, no view id, not even {@code isClickable}),
 * and the app is built in a way that its node bounds do not reliably describe where the
 * circle actually is drawn. Every coordinate-based attempt therefore depends on a
 * hand-measured guess that shifts with screen size, aspect ratio, insets, DPI and
 * navigation-bar mode. That guess has been wrong twice.</p>
 *
 * <p>A screenshot removes the guesswork entirely. The button is a saturated orange disc
 * with a white "+" inside it, sitting in the bottom-right corner of the content area.
 * This class finds that disc in the pixels and returns the box to tap.</p>
 *
 * <p>Two strategies are tried, in order:</p>
 * <ol>
 *   <li><b>Orange family</b> — the exact colour used by this app. Precise and cheap, and
 *       it cannot accidentally match a grey list row or a white card.</li>
 *   <li><b>Dominant accent colour</b> — the most common strongly-saturated hue on the
 *       screen, used when the theme colour changes in a future build. A wide orange
 *       button ("PLACE ORDER") is rejected by the squareness test, so it is not a risk.</li>
 * </ol>
 *
 * <p>Everything here is pure integer/float maths on an {@code int[]} of ARGB pixels, so it
 * is independent of Android graphics classes except {@link Rect} for the result.</p>
 */
final class FabFinder {

    /** A disc found in the pixels. */
    static final class Hit {
        /** Where to tap, in screenshot pixels. */
        final Rect bounds;
        /** Measured diameter in pixels. */
        final int diameter;
        /** Fraction of the bounding box actually covered by the coloured pixels. */
        final float fill;
        /** True when the match came from the app's orange, false for a generic accent. */
        final boolean orange;
        /** Human readable explanation, logged and shown in a capture. */
        final String reason;

        Hit(Rect bounds, int diameter, float fill, boolean orange, String reason) {
            this.bounds = bounds;
            this.diameter = diameter;
            this.fill = fill;
            this.orange = orange;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason + " d=" + diameter + "px fill=" + String.format(java.util.Locale.US, "%.2f", fill)
                    + " @" + bounds.exactCenterX() + "," + bounds.exactCenterY();
        }
    }

    /** Below this many sampled pixels a run is treated as noise rather than a button. */
    private static final int MIN_PIXELS = 120;

    private FabFinder() {
    }

    /** Returns the best button found, or null when the screen has nothing that qualifies. */
    static Hit find(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0) return null;
        if (argb.length < width * height) return null;

        // A 1080x2340 screen is 2.5M pixels. Sampling every other pixel keeps a ~90px disc
        // at ~45px across, which is still 1500+ samples: ample, and four times faster.
        int step = Math.min(width, height) > 900 ? 2 : 1;
        int sw = (width + step - 1) / step;
        int sh = (height + step - 1) / step;

        Hit orange = scan(argb, width, height, step, sw, sh, Family.ORANGE);
        if (orange != null) return orange;

        return scan(argb, width, height, step, sw, sh, Family.DOMINANT_ACCENT);
    }

    private enum Family { ORANGE, DOMINANT_ACCENT }

    /**
     * Builds a mask for one colour family and returns the best button-shaped blob in it.
     */
    private static Hit scan(int[] argb, int width, int height, int step, int sw, int sh,
                            Family family) {
        byte[] mask = new byte[sw * sh];

        int[] hueHistogram = family == Family.DOMINANT_ACCENT ? new int[36] : null;

        for (int sy = 0; sy < sh; sy++) {
            int y = sy * step;
            if (y >= height) break;
            int row = y * width;
            for (int sx = 0; sx < sw; sx++) {
                int x = sx * step;
                if (x >= width) break;
                int c = argb[row + x];
                int a = (c >>> 24) & 0xFF;
                if (a < 200) continue;
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max <= 0) continue;
                float v = max / 255f;
                float s = (max - min) / (float) max;
                if (v < 0.45f || s < 0.40f) continue;

                float hue = hueDegrees(r, g, b, max, min);

                if (family == Family.ORANGE) {
                    // This app's orange sits around 20-25 degrees. The band is deliberately
                    // wide enough to survive a slight theme change, and thresholds are high
                    // enough that skin tones and beige cards do not qualify.
                    if (hue >= 11f && hue <= 42f && s >= 0.50f) {
                        mask[sy * sw + sx] = 1;
                    }
                } else if (s >= 0.55f && v >= 0.50f) {
                    int bin = (int) (hue / 10f);
                    if (bin < 0) bin = 0;
                    if (bin > 35) bin = 35;
                    hueHistogram[bin]++;
                    mask[sy * sw + sx] = 1;
                }
            }
        }

        if (family == Family.DOMINANT_ACCENT) {
            // Only keep the dominant hue band; otherwise every icon on the screen is
            // treated as the same blob and they get merged into one.
            int bestBin = -1;
            int bestCount = MIN_PIXELS;
            for (int i = 0; i < 36; i++) {
                if (hueHistogram[i] > bestCount) {
                    bestCount = hueHistogram[i];
                    bestBin = i;
                }
            }
            if (bestBin < 0) return null;
            pruneToHueBand(argb, mask, width, height, step, sw, sh, bestBin * 10f);
        }

        return bestBlob(mask, sw, sh, step, width, height,
                family == Family.ORANGE ? "orange disc" : "accent disc");
    }

    /** Keeps only pixels whose hue falls in the band around {@code centreHue}. */
    private static void pruneToHueBand(int[] argb, byte[] mask, int width, int height, int step,
                                       int sw, int sh, float centreHue) {
        for (int sy = 0; sy < sh; sy++) {
            int y = sy * step;
            if (y >= height) break;
            int row = y * width;
            for (int sx = 0; sx < sw; sx++) {
                int idx = sy * sw + sx;
                if (mask[idx] == 0) continue;
                int x = sx * step;
                if (x >= width) break;
                int c = argb[row + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                float hue = hueDegrees(r, g, b, max, min);
                float delta = Math.abs(hue - centreHue);
                if (delta > 180f) delta = 360f - delta;
                if (delta > 12f) mask[idx] = 0;
            }
        }
    }

    private static float hueDegrees(int r, int g, int b, int max, int min) {
        if (max == min) return 0f;
        float delta = max - min;
        float hue;
        if (max == r) {
            hue = ((g - b) / delta) % 6f;
        } else if (max == g) {
            hue = (b - r) / delta + 2f;
        } else {
            hue = (r - g) / delta + 4f;
        }
        hue *= 60f;
        if (hue < 0f) hue += 360f;
        return hue;
    }

    /**
     * Connected-component search over the mask, scoring each blob on the properties that
     * make something a floating action button: roughly square, disc-sized, and parked in
     * the bottom-right of the screen.
     */
    private static Hit bestBlob(byte[] mask, int sw, int sh, int step, int width, int height,
                                String label) {
        boolean[] seen = new boolean[sw * sh];
        int[] stack = new int[sw * sh];

        Hit best = null;
        int bestScore = 0;

        for (int start = 0; start < mask.length; start++) {
            if (mask[start] == 0 || seen[start]) continue;

            int top = 0;
            stack[top++] = start;
            seen[start] = true;

            int count = 0;
            int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;

            while (top > 0) {
                int idx = stack[--top];
                int x = idx % sw;
                int y = idx / sw;
                count++;

                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                // 4-connected flood fill. Each neighbour is marked as soon as it is
                // pushed, so it can never be queued twice and the stack cannot overflow.
                if (x > 0) {
                    int n = idx - 1;
                    if (mask[n] != 0 && !seen[n]) { seen[n] = true; stack[top++] = n; }
                }
                if (x + 1 < sw) {
                    int n = idx + 1;
                    if (mask[n] != 0 && !seen[n]) { seen[n] = true; stack[top++] = n; }
                }
                if (y > 0) {
                    int n = idx - sw;
                    if (mask[n] != 0 && !seen[n]) { seen[n] = true; stack[top++] = n; }
                }
                if (y + 1 < sh) {
                    int n = idx + sw;
                    if (mask[n] != 0 && !seen[n]) { seen[n] = true; stack[top++] = n; }
                }
            }

            if (count < MIN_PIXELS) continue;

            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;
            float ratio = bw / (float) Math.max(1, bh);
            float fill = count / (float) Math.max(1, bw * bh);

            // A disc covers ~0.79 of its box; the white "+" inside removes some of that.
            float widthFraction = bw / (float) sw;

            if (fill < 0.30f) continue;              // a ring, a line, or scattered noise
            if (ratio < 0.60f || ratio > 1.70f) continue;  // not square: e.g. a wide button
            if (widthFraction < 0.045f) continue;    // too small to be the button
            if (widthFraction > 0.30f) continue;     // too big: a banner or a card

            float centreX = (minX + maxX) / 2f / sw;
            float centreY = (minY + maxY) / 2f / sh;
            if (centreX < 0.50f) continue;           // a FAB is on the right
            if (centreY < 0.50f) continue;           // and low on the screen

            int score = 0;
            score += (int) (fill * 40);                       // solid disc beats a hollow shape
            score += (int) ((1f - Math.abs(ratio - 1f)) * 30); // squareness
            score += (int) (centreX * 40);                    // further right is more likely
            score += (int) (centreY * 30);                    // further down likewise
            if (count >= MIN_PIXELS * 4) score += 10;

            if (score > bestScore) {
                bestScore = score;
                int diameter = Math.max(bw, bh) * step;
                int cx = Math.round((minX + maxX) / 2f * step);
                int cy = Math.round((minY + maxY) / 2f * step);
                int radius = Math.max(20, diameter / 2);
                Rect bounds = new Rect(
                        Math.max(0, cx - radius),
                        Math.max(0, cy - radius),
                        Math.min(width, cx + radius),
                        Math.min(height, cy + radius));
                best = new Hit(bounds, diameter, fill, true, label);
            }
        }
        return best;
    }
}
