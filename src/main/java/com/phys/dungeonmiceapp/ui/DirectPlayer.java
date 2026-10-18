package com.phys.dungeonmiceapp.ui;

import java.io.File;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.beans.property.*;
import javafx.scene.image.ImageView;
import javafx.scene.media.MediaPlayer.Status;
import javafx.util.Duration;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.media.callback.seekable.RandomAccessFileMedia;

/** Direct file playback. Native operations never run on the JavaFX event thread. */
public final class DirectPlayer {
    static { System.setProperty("jna.encoding", "UTF-8"); }
    private static final ExecutorService commands = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dungeonmice-vlc"); thread.setDaemon(true); return thread;
    });
    private static MediaPlayerFactory factory;
    private final File source;
    private final ImageView target;
    private final ImageView surfaceView = new ImageView();
    private final ReadOnlyObjectWrapper<Duration> time = new ReadOnlyObjectWrapper<>(Duration.ZERO);
    private final Timeline ticker;
    private volatile EmbeddedMediaPlayer engine;
    private RandomAccessFileMedia fileMedia;
    private ImageViewVideoSurface videoSurface;
    private final long openingStarted = System.nanoTime();
    private volatile boolean disposed;
    private volatile Status state = Status.UNKNOWN;
    private volatile long length, position;
    private volatile int frameWidth, frameHeight;
    private volatile double volume = .5;
    private volatile boolean muted;
    private RuntimeException error;
    private Runnable onReady, onError, onEnd;
    private boolean ready;
    private volatile boolean pollPending;

    public DirectPlayer(File source, ImageView target) {
        this.source = source; this.target = target;
        surfaceView.imageProperty().addListener((obs, old, image) -> fx(() -> {
            if (!disposed) { target.setImage(image); cropFrame(); checkReady(); }
        }));
        ticker = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            if (!disposed) {
                // Paused seeks do not reliably emit VLC timeChanged callbacks.
                if (state == Status.PAUSED && !pollPending) {
                    pollPending = true;
                    commands.execute(() -> {
                        try { if (!disposed && engine != null) position = Math.max(0, engine.status().time()); }
                        finally { pollPending = false; }
                    });
                }
                time.set(Duration.millis(position)); checkReady();
                if (!ready && error == null && System.nanoTime() - openingStarted > TimeUnit.SECONDS.toNanos(30))
                    fail(new IllegalStateException("El video no respondio al abrirse: " + source));
            }
        })); ticker.setCycleCount(Timeline.INDEFINITE); ticker.play();
        commands.execute(() -> {
            if (disposed) return;
            try {
                if (factory == null) {
                    if (!new NativeDiscovery().discover()) throw new IllegalStateException("Instala VLC de 64 bits para reproducir videos directamente.");
                    factory = new MediaPlayerFactory("--quiet", "--no-video-title-show", "--no-osd", "--avcodec-hw=none");
                }
                engine = factory.mediaPlayers().newEmbeddedMediaPlayer();
                videoSurface = new ImageViewVideoSurface(surfaceView);
                engine.videoSurface().set(videoSurface);
                engine.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                    @Override public void playing(uk.co.caprica.vlcj.player.base.MediaPlayer player) {
                        state = Status.PLAYING;
                        java.util.List<uk.co.caprica.vlcj.media.VideoTrackInfo> tracks = player.media().info().videoTracks();
                        if (!tracks.isEmpty()) { frameWidth = tracks.get(0).width(); frameHeight = tracks.get(0).height(); }
                        fx(() -> { if (!disposed) { cropFrame(); checkReady(); } });
                    }
                    @Override public void paused(uk.co.caprica.vlcj.player.base.MediaPlayer player) { state = Status.PAUSED; }
                    @Override public void stopped(uk.co.caprica.vlcj.player.base.MediaPlayer player) { if (!disposed) state = Status.STOPPED; }
                    @Override public void timeChanged(uk.co.caprica.vlcj.player.base.MediaPlayer player, long value) { position = value; }
                    @Override public void lengthChanged(uk.co.caprica.vlcj.player.base.MediaPlayer player, long value) { length = value; }
                    @Override public void finished(uk.co.caprica.vlcj.player.base.MediaPlayer player) { fx(() -> { if (!disposed && onEnd != null) onEnd.run(); }); }
                    @Override public void error(uk.co.caprica.vlcj.player.base.MediaPlayer player) { fail(new IllegalStateException("VLC no pudo abrir el archivo: " + source)); }
                });
                engine.audio().setVolume((int) (volume * 100)); engine.audio().setMute(muted);
                // Java opens the exact filesystem path; VLC reads/seeks it via callbacks.
                fileMedia = new RandomAccessFileMedia(source);
                if (!engine.media().play(fileMedia, ":file-caching=100", ":no-sub-autodetect-file")) fail(new IllegalStateException("No se pudo abrir: " + source));
            } catch (RuntimeException ex) { fail(ex); }
            catch (LinkageError ex) { fail(new IllegalStateException("No se pudo cargar VLC de 64 bits.", ex)); }
        });
    }

    private static void fx(Runnable action) { Platform.runLater(action); }
    private void cropFrame() {
        if (frameWidth > 0 && frameHeight > 0 && target.getImage() != null)
            target.setViewport(new javafx.geometry.Rectangle2D(0, 0, frameWidth, frameHeight));
    }
    private void checkReady() {
        if (!ready && !disposed && error == null && target.getImage() != null && length > 0 && frameWidth > 0 && frameHeight > 0) {
            ready = true; if (onReady != null) onReady.run();
        }
    }
    private void fail(RuntimeException ex) { fx(() -> { if (!disposed) { error = ex; state = Status.HALTED; if (onError != null) onError.run(); } }); }
    private void command(java.util.function.Consumer<EmbeddedMediaPlayer> action) {
        commands.execute(() -> { if (!disposed && engine != null) action.accept(engine); });
    }
    public void play() { command(player -> player.controls().play()); }
    public void pause() { command(player -> player.controls().setPause(true)); }
    public void stop() { pause(); seek(Duration.ZERO); }
    public void seek(Duration duration) { command(player -> player.controls().setTime((long) duration.toMillis())); }
    public void setVolume(double value) { volume = value; command(player -> player.audio().setVolume((int) (value * 100))); }
    public double getVolume() { return volume; }
    public void setMute(boolean value) { muted = value; command(player -> player.audio().setMute(value)); }
    public boolean isMute() { return muted; }
    public Status getStatus() { return disposed ? Status.DISPOSED : state; }
    public Duration getCurrentTime() { return time.get(); }
    public ReadOnlyObjectProperty<Duration> currentTimeProperty() { return time.getReadOnlyProperty(); }
    public Duration getTotalDuration() { return Duration.millis(length); }
    public RuntimeException getError() { return error; }
    public void setOnReady(Runnable action) { onReady = action; checkReady(); }
    public void setOnError(Runnable action) { onError = action; if (error != null) action.run(); }
    public void setOnEndOfMedia(Runnable action) { onEnd = action; }
    public VideoInfo getMedia() { return new VideoInfo(); }
    public final class VideoInfo {
        public int getWidth() { return frameWidth; }
        public int getHeight() { return frameHeight; }
        public String getSource() { return source.toURI().toString(); }
    }
    public void dispose() {
        if (disposed) return;
        disposed = true; state = Status.DISPOSED; ticker.stop();
        // Keep JNA callbacks alive until the native player has finished releasing.
        commands.execute(() -> { if (engine != null) { engine.release(); engine = null; fileMedia = null; videoSurface = null; } });
    }
}
