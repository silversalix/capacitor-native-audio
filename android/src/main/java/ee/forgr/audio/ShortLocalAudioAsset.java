package ee.forgr.audio;

import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import java.io.File;

/** A predecoded player for local clips too short to tolerate AAC decoder startup on tap. */
@UnstableApi
final class ShortLocalAudioAsset extends AudioAsset {

    private static final String TAG = "ShortLocalAudioAsset";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SoundPool soundPool;
    private final double duration;
    private final File file;
    private int sampleId;
    private int streamId;
    private float volume;
    private float rate = 1.0f;
    private boolean loaded;
    private boolean released;
    private boolean paused;
    private boolean looping;
    private boolean finished;
    private long startedAtMs;
    private long playedMs;
    private Runnable completion;
    private RemoteAudioAsset fallback;

    ShortLocalAudioAsset(NativeAudio owner, String assetId, File file, double duration, float volume) throws Exception {
        super(owner, assetId, null, 0, volume);
        this.file = file;
        this.duration = duration;
        this.volume = volume;
        soundPool = new SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .build();
        soundPool.setOnLoadCompleteListener((pool, id, status) -> handler.post(() -> {
            if (released || id != sampleId) return;
            if (status == 0) {
                loaded = true;
                Log.d(TAG, "Predecoded " + assetId + " (" + duration + " s)");
                owner.notifyDurationAvailable(assetId, duration);
            } else {
                Log.w(TAG, "SoundPool could not decode " + assetId + "; using Media3");
                try {
                    fallback = new RemoteAudioAsset(owner, assetId, android.net.Uri.fromFile(file), 1, volume, null, true);
                    fallback.setCompletionListener(completedId -> notifyCompletion());
                } catch (Exception e) {
                    Log.e(TAG, "Media3 fallback failed", e);
                }
            }
        }));
        sampleId = soundPool.load(file.getAbsolutePath(), 1);
        if (sampleId == 0) {
            soundPool.release();
            throw new Exception("SoundPool could not load " + file.getAbsolutePath());
        }
    }

    @Override
    public double getDuration() {
        return fallback != null ? fallback.getDuration() : loaded ? duration : 0;
    }

    @Override
    public void play(double time, float volume) throws Exception {
        if (fallback != null) {
            fallback.play(time, volume);
            return;
        }
        if (time != 0) throw new Exception("Seeking is unavailable for short predecoded audio");
        PlayerThread.run(() -> {
            if (!loaded || released) throw new Exception("Short audio is not predecoded: " + assetId);
            stopStream(false);
            this.volume = volume;
            playedMs = 0;
            looping = false;
            startStream(0);
        });
    }

    private void startStream(int loopCount) throws Exception {
        streamId = soundPool.play(sampleId, volume, volume, 1, loopCount, rate);
        if (streamId == 0) throw new Exception("SoundPool could not start " + assetId);
        startedAtMs = SystemClock.uptimeMillis();
        paused = false;
        finished = false;
        dispatchedCompleteMap.put(assetId, false);
        if (loopCount == 0) scheduleCompletion();
    }

    private void scheduleCompletion() {
        if (completion != null) handler.removeCallbacks(completion);
        completion = () -> {
            completion = null;
            finished = true;
            playedMs = Math.round(duration * 1000);
            dispatchedCompleteMap.put(assetId, true);
            notifyCompletion();
        };
        long remaining = Math.max(1, Math.round(duration * 1000 / rate) - playedMs);
        handler.postDelayed(completion, remaining);
    }

    private void stopStream(boolean notify) {
        boolean active = streamId != 0 && !finished;
        if (completion != null) handler.removeCallbacks(completion);
        completion = null;
        if (active) soundPool.stop(streamId);
        streamId = 0;
        paused = false;
        looping = false;
        finished = false;
        playedMs = 0;
        if (notify && active) dispatchComplete();
    }

    @Override
    public void stop() throws Exception {
        if (fallback != null) {
            fallback.stop();
            return;
        }
        PlayerThread.run(() -> stopStream(true));
    }

    @Override
    public boolean pause() throws Exception {
        if (fallback != null) return fallback.pause();
        return PlayerThread.call(() -> {
            if (streamId == 0 || paused || finished) return false;
            playedMs += SystemClock.uptimeMillis() - startedAtMs;
            soundPool.pause(streamId);
            if (completion != null) handler.removeCallbacks(completion);
            completion = null;
            paused = true;
            return true;
        });
    }

    @Override
    public void resume() throws Exception {
        if (fallback != null) {
            fallback.resume();
            return;
        }
        PlayerThread.run(() -> {
            if (!paused || streamId == 0) return;
            soundPool.resume(streamId);
            startedAtMs = SystemClock.uptimeMillis();
            paused = false;
            if (!looping) scheduleCompletion();
        });
    }

    @Override
    public void loop() throws Exception {
        if (fallback != null) {
            fallback.loop();
            return;
        }
        PlayerThread.run(() -> {
            if (!loaded || released) throw new Exception("Short audio is not predecoded: " + assetId);
            stopStream(false);
            looping = true;
            startStream(-1);
            looping = true;
        });
    }

    @Override
    public boolean isPlaying() throws Exception {
        return fallback != null ? fallback.isPlaying() : streamId != 0 && !paused && !finished;
    }

    @Override
    public double getCurrentPosition() {
        if (fallback != null) return fallback.getCurrentPosition();
        long elapsed = streamId != 0 && !paused ? SystemClock.uptimeMillis() - startedAtMs : 0;
        return Math.min(duration, (playedMs + elapsed) / 1000.0);
    }

    @Override
    public void setCurrentTime(double time) throws Exception {
        if (fallback != null) {
            fallback.setCurrentTime(time);
        } else if (time != 0) {
            throw new Exception("Seeking is unavailable for short predecoded audio");
        } else {
            stop();
        }
    }

    @Override
    public void setVolume(float volume, double fadeDurationMs) throws Exception {
        if (fallback != null) {
            fallback.setVolume(volume, fadeDurationMs);
            return;
        }
        PlayerThread.run(() -> {
            this.volume = volume;
            if (streamId != 0) soundPool.setVolume(streamId, volume, volume);
        });
    }

    @Override
    public float getVolume() throws Exception {
        return fallback != null ? fallback.getVolume() : volume;
    }

    @Override
    public void setRate(float rate) throws Exception {
        if (fallback != null) {
            fallback.setRate(rate);
            return;
        }
        PlayerThread.run(() -> {
            this.rate = rate;
            if (streamId != 0) soundPool.setRate(streamId, rate);
        });
    }

    @Override
    public void unload() throws Exception {
        if (fallback != null) fallback.unload();
        PlayerThread.run(() -> {
            stopStream(false);
            released = true;
            soundPool.release();
            close();
        });
    }

    @Override
    public void playWithFadeIn(double time, float volume, double fadeInDurationMs) throws Exception {
        play(time, volume);
    }

    @Override
    public void stopWithFade(double fadeOutDurationMs, boolean toPause) throws Exception {
        if (toPause) pause();
        else stop();
    }
}
