package com.vrksignatures.sudhaautobot;

/**
 * How see-through the floating panel is. Plain Java, no Android.
 *
 * <p>Two different things are drawn:</p>
 * <ul>
 *   <li><b>Folded</b>, the panel is a ring and nothing else: no fill at all, so the
 *       wallpaper or the app behind shows through it untouched.</li>
 *   <li><b>Open</b>, the dashboard is a sheet of white glass. How white is the setting
 *       here, in five steps. The words on it are never thinned: they stay at full strength
 *       and get a pale edge, so that they can be read over whatever is behind.</li>
 * </ul>
 */
public final class Glass {

    /** Steps 0 (nearly solid) to 4 (most see-through). */
    public static final int LEVELS = 5;
    /** The step used until the person chooses another. */
    public static final int DEFAULT = 2;
    /** How much of the sheet is white at each step, in percent; the rest is what is behind. */
    private static final int[] WHITE_PERCENT = {92, 76, 60, 46, 34};

    private Glass() {
    }

    public static int clamp(int level) {
        return Math.max(0, Math.min(LEVELS - 1, level));
    }

    /** How much of the sheet is its own white, 34 to 92 percent. */
    public static int percent(int level) {
        return WHITE_PERCENT[clamp(level)];
    }

    /** The sheet's colour as ARGB: white, as see-through as the step says. */
    public static int sheet(int level) {
        return (Math.round(percent(level) * 255 / 100f) << 24) | 0x00FFFFFF;
    }

    /**
     * The pale edge drawn round every word, as ARGB; stronger the more see-through the
     * sheet is. This is what keeps the words readable over a dark or busy screen.
     */
    public static int halo(int level) {
        int[] alpha = {0x00, 0xB0, 0xD8, 0xF0, 0xFF};
        return (alpha[clamp(level)] << 24) | 0x00FFFFFF;
    }

    /** What a point of the sheet looks like over this backdrop (0xRRGGBB), as 0xRRGGBB. */
    public static int over(int level, int backdropRgb) {
        int white = percent(level);
        int r = (255 * white + ((backdropRgb >> 16) & 0xFF) * (100 - white)) / 100;
        int g = (255 * white + ((backdropRgb >> 8) & 0xFF) * (100 - white)) / 100;
        int b = (255 * white + (backdropRgb & 0xFF) * (100 - white)) / 100;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Where a window of this size goes so that all of it is on the screen: moved left and
     * up just far enough, never off the top or the left. Returns {x, y}.
     */
    public static int[] fit(int x, int y, int width, int height, int screenWidth, int screenHeight) {
        int fitX = x;
        int fitY = y;
        if (width > 0 && screenWidth > 0 && fitX + width > screenWidth) fitX = screenWidth - width;
        if (height > 0 && screenHeight > 0 && fitY + height > screenHeight) fitY = screenHeight - height;
        return new int[]{Math.max(0, fitX), Math.max(0, fitY)};
    }

    /** How much of what is behind still comes through, in percent. */
    public static int showsThrough(int level) {
        return 100 - percent(level);
    }

    /** The contrast between two colours as WCAG counts it: 1 (none) to 21 (black on white). */
    public static double contrast(int rgbA, int rgbB) {
        double a = luminance(rgbA);
        double b = luminance(rgbB);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF)
                + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
