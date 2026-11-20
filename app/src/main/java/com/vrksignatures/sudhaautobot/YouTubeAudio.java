package com.vrksignatures.sudhaautobot;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * YouTube, for the ears only: searches it for songs and finds the address of the
 * <b>sound-only</b> stream behind a video. No picture is ever asked for or downloaded.
 *
 * <p>ExoPlayer cannot play a YouTube page by itself: a page address is not a media file.
 * The lookup is done by NewPipe Extractor, the library of the NewPipe app, which asks
 * YouTube the same questions YouTube's own players ask. It needs no Google account and no
 * key. It is not an official YouTube interface: when YouTube changes its answers the
 * lookup stops working until the library is updated (its version is in
 * {@code app/build.gradle.kts}).</p>
 *
 * <p>Every call here goes to the network and may take seconds: never call from the main
 * thread. Nothing here is read by the bot.</p>
 */
final class YouTubeAudio {

    /** The address ExoPlayer is given, and what it has to say about itself to be served. */
    static final class Stream {
        final String url;
        final String userAgent;
        /** True for an HLS list (several qualities, picture too: the picture track is switched off). */
        final boolean hls;

        Stream(String url, String userAgent, boolean hls) {
            this.url = url;
            this.userAgent = userAgent;
            this.hls = hls;
        }
    }

    /** What a desktop browser says it is; used for everything but the stream itself. */
    static final String BROWSER =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static final Localization WHERE = new Localization("en", "IN");
    private static boolean ready;

    private YouTubeAudio() {
    }

    private static synchronized void init() {
        if (ready) return;
        NewPipe.init(new Net(), WHERE, new ContentCountry("IN"));
        ready = true;
    }

    /**
     * Songs YouTube answers for these search words, best first; only results that look
     * like one song ({@link MusicMood#fits}). YouTube Music's song list is asked first
     * (studio recordings, no talk before the song); the ordinary video search is the
     * fallback.
     */
    static List<SongBook.Song> search(String words) throws Exception {
        return search(words, false);
    }

    /**
     * As {@link #search(String)}; with {@code lines} true, short film dialogues are looked
     * for instead of songs ({@link MusicMood#fitsLine}), in the ordinary video search only.
     */
    static List<SongBook.Song> search(String words, boolean lines) throws Exception {
        init();
        List<SongBook.Song> songs = new ArrayList<>();
        Exception last = null;
        for (String kind : lines ? new String[]{"videos"} : new String[]{"music_songs", "videos"}) {
            try {
                SearchInfo answer = SearchInfo.getInfo(ServiceList.YouTube,
                        ServiceList.YouTube.getSearchQHFactory().fromQuery(
                                words, Collections.singletonList(kind), ""));
                List<InfoItem> items = answer.getRelatedItems();
                for (int i = 0; items != null && i < items.size(); i++) {
                    InfoItem item = items.get(i);
                    if (!(item instanceof StreamInfoItem)) continue;
                    StreamInfoItem one = (StreamInfoItem) item;
                    StreamType type = one.getStreamType();
                    if (type != StreamType.VIDEO_STREAM && type != StreamType.AUDIO_STREAM) continue;
                    boolean wanted = lines ? MusicMood.fitsLine(one.getName(), one.getDuration())
                            : MusicMood.fits(one.getName(), one.getDuration());
                    if (!wanted) continue;
                    String page = MusicMood.youTubePage(one.getUrl());
                    if (page == null || has(songs, page)) continue;
                    songs.add(new SongBook.Song(MusicMood.tidy(one.getName(), one.getUploaderName()), page));
                }
            } catch (Exception e) {
                last = e;
            }
            if (songs.size() >= 5) break;
        }
        if (songs.isEmpty() && last != null) throw last;
        return songs;
    }

    private static boolean has(List<SongBook.Song> songs, String address) {
        for (SongBook.Song song : songs) if (song.address.equals(address)) return true;
        return false;
    }

    /** The title of the video behind a YouTube link. */
    static String title(String page) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, page);
        return MusicMood.tidy(info.getName(), "");
    }

    /**
     * The sound-only stream of a YouTube video. The address YouTube hands out is good for
     * a few hours only, so it is looked up again each time a song is started.
     */
    static Stream resolve(String page) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, page);
        StreamType type = info.getStreamType();
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) {
            throw new IOException("it is a live broadcast, not a song");
        }
        List<AudioStream> all = info.getAudioStreams();
        List<AudioStream> plain = new ArrayList<>();
        for (int i = 0; all != null && i < all.size(); i++) {
            AudioStream one = all.get(i);
            if (one == null || !one.isUrl() || one.getContent() == null || one.getContent().isEmpty()) continue;
            if (one.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            plain.add(one);
        }
        int[] kbps = new int[plain.size()];
        for (int i = 0; i < kbps.length; i++) kbps[i] = plain.get(i).getAverageBitrate();
        int best = MusicMood.bestStream(kbps);
        if (best >= 0) {
            String url = plain.get(best).getContent();
            return new Stream(url, agentFor(url), false);
        }
        // No separate sound stream was offered: the HLS list has one inside it.
        String list = info.getHlsUrl();
        if (list != null && !list.isEmpty()) {
            return new Stream(list, YoutubeParsingHelper.getVisionOsUserAgent((Localization) null), true);
        }
        throw new IOException("YouTube offered no sound stream for it");
    }

    /** YouTube serves a stream only to the kind of player it was handed out to. */
    private static String agentFor(String url) {
        try {
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                // null: the library's own default wording, as the NewPipe app sends it.
                return YoutubeParsingHelper.getVisionOsUserAgent((Localization) null);
            }
        } catch (Throwable ignored) {
        }
        return BROWSER;
    }

    /** A short reason, for the person, out of whatever went wrong. */
    static String reason(Throwable problem) {
        if (problem == null) return "unknown problem";
        if (problem instanceof java.net.UnknownHostException
                || problem instanceof java.net.ConnectException
                || problem instanceof java.net.SocketTimeoutException
                || problem instanceof javax.net.ssl.SSLException) {
            return "no internet, or YouTube could not be reached";
        }
        if (problem instanceof ReCaptchaException) {
            return "YouTube asked for a \"not a robot\" check; try again later";
        }
        String said = problem.getMessage();
        String name = problem.getClass().getSimpleName();
        if (said == null || said.trim().isEmpty()) return name;
        said = said.replaceAll("\\s+", " ").trim();
        return said.length() > 90 ? said.substring(0, 90) + "…" : said;
    }

    // ------------------------------------------------------------- the network

    /**
     * How the library reaches the internet: the phone's own {@link HttpURLConnection}.
     * Text answers only (pages and JSON); the sound itself is fetched by ExoPlayer.
     */
    private static final class Net extends Downloader {
        private static final int MOST_BYTES = 12 * 1024 * 1024;

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            String address = request.url();
            String method = request.httpMethod();
            byte[] body = request.dataToSend();
            HttpURLConnection link = (HttpURLConnection) new URL(address).openConnection();
            try {
                link.setConnectTimeout(15_000);
                link.setReadTimeout(30_000);
                link.setInstanceFollowRedirects(true);
                link.setRequestMethod(method == null ? "GET" : method);
                link.setRequestProperty("User-Agent", BROWSER);
                Map<String, List<String>> headers = request.headers();
                if (headers != null) {
                    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                        String name = header.getKey();
                        List<String> values = header.getValue();
                        if (name == null || values == null) continue;
                        boolean first = true;
                        for (String value : values) {
                            if (value == null) continue;
                            if (first) link.setRequestProperty(name, value);
                            else link.addRequestProperty(name, value);
                            first = false;
                        }
                    }
                }
                if (body != null) {
                    link.setDoOutput(true);
                    link.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = link.getOutputStream()) {
                        out.write(body);
                    }
                }
                int code = link.getResponseCode();
                if (code == 429) {
                    throw new ReCaptchaException("reCaptcha Challenge requested", address);
                }
                String text = "";
                if (!"HEAD".equalsIgnoreCase(method)) {
                    InputStream in = code >= 400 ? link.getErrorStream() : link.getInputStream();
                    if (in != null) {
                        if ("gzip".equalsIgnoreCase(link.getContentEncoding())) in = new GZIPInputStream(in);
                        text = read(in);
                    }
                }
                Map<String, List<String>> answerHeaders = new HashMap<>();
                Map<String, List<String>> given = link.getHeaderFields();
                if (given != null) {
                    for (Map.Entry<String, List<String>> header : given.entrySet()) {
                        // The status line comes under a null name.
                        if (header.getKey() != null && header.getValue() != null) {
                            answerHeaders.put(header.getKey(), header.getValue());
                        }
                    }
                }
                String message = link.getResponseMessage();
                return new Response(code, message == null ? "" : message, answerHeaders, text,
                        link.getURL().toString());
            } finally {
                link.disconnect();
            }
        }

        private static String read(InputStream in) throws IOException {
            try (InputStream from = in) {
                ByteArrayOutputStream all = new ByteArrayOutputStream(64 * 1024);
                byte[] piece = new byte[16 * 1024];
                int got;
                while ((got = from.read(piece)) > 0) {
                    all.write(piece, 0, got);
                    if (all.size() > MOST_BYTES) throw new IOException("answer too large");
                }
                return all.toString("UTF-8");
            }
        }
    }
}
