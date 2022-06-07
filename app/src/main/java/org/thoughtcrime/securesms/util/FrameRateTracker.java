package org.thoughtcrime.securesms.util;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Display;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the frame rate of the app and logs when things are bad.
 *
 * In general, whenever alterations are made here, the author should be very cautious to do as
 * little work as possible, because we don't want the tracker itself to impact the frame rate.
 */
public class FrameRateTracker {

  private static final String TAG = Log.tag(FrameRateTracker.class);

  private static final int MAX_CONSECUTIVE_FRAME_LOGS = 10;

  private final Application context;

  private double refreshRate;
  private long   idealTimePerFrameNanos;
  private long   badFrameThresholdNanos;

  private long lastFrameTimeNanos;

  private long consecutiveFrameWarnings;

  private TextView tv;

  public FrameRateTracker(@NonNull Application application) {
    this.context = application;

    updateRefreshRate();
  }

  public void start() {
    Log.d(TAG, String.format(Locale.ENGLISH, "Beginning frame rate tracking. Screen refresh rate: %.2f hz, or %.2f ms per frame.", refreshRate, idealTimePerFrameNanos / (float) 1_000_000));

    lastFrameTimeNanos  = System.nanoTime();

    Choreographer.getInstance().postFrameCallback(calculator);
  }

  public void stop() {
    Choreographer.getInstance().removeFrameCallback(calculator);
  }

  /**
   * The natural screen refresh rate, in hertz. May not always return the same value if a display
   * has a dynamic refresh rate.
   */
  public static float getDisplayRefreshRate(@NonNull Context context) {
    Display display = ServiceUtil.getWindowManager(context).getDefaultDisplay();
    return display.getRefreshRate();
  }

  /**
   * Displays with dynamic refresh rates may change their reported refresh rate over time.
   */
  private void updateRefreshRate() {
    double newRefreshRate = getDisplayRefreshRate(context);

    if (this.refreshRate != newRefreshRate) {
      if (this.refreshRate > 0) {
        Log.d(TAG, String.format(Locale.ENGLISH, "Refresh rate changed from %.2f hz to %.2f hz", refreshRate, newRefreshRate));
      }

      this.refreshRate             = getDisplayRefreshRate(context);
      this.idealTimePerFrameNanos  = (long) (TimeUnit.SECONDS.toNanos(1) / refreshRate);
      this.badFrameThresholdNanos  = idealTimePerFrameNanos * (int) (refreshRate / 4);
    }
  }

  public void setUpFPSCounter() {
    //Needed for permissions
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (!Settings.canDrawOverlays(context)) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
      }
    }

    //Creating the custom textview
    tv = new TextView(context);
    tv.setText("HELLO");

    //Calling the window manager to create the overlay
    WindowManager mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

    //Different OS versions have different overlay flags
    int LAYOUT_FLAG;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    } else {
      LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
    }

    //Make the overlay wrap_content, and make it not focusable and not touchable and make it translucent
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        LAYOUT_FLAG,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT);

    //Add the view to the window manager
    mWindowManager.addView(tv, params);
  }

  private final Choreographer.FrameCallback calculator = new Choreographer.FrameCallback() {
    @Override
    public void doFrame(long frameTimeNanos) {
      long   elapsedNanos = frameTimeNanos - lastFrameTimeNanos;
      double fps          = TimeUnit.SECONDS.toNanos(1) / (double) elapsedNanos;

      //Assign the value of fps to the textview
      tv.setText(String.valueOf((int) (fps)));

      if (elapsedNanos > badFrameThresholdNanos) {
        if (consecutiveFrameWarnings < MAX_CONSECUTIVE_FRAME_LOGS) {
          long droppedFrames = elapsedNanos / idealTimePerFrameNanos;
          Log.w(TAG, String.format(Locale.ENGLISH, "Bad frame! Took %d ms (%d dropped frames, or %.2f FPS)", TimeUnit.NANOSECONDS.toMillis(elapsedNanos), droppedFrames, fps));
          consecutiveFrameWarnings++;
        }
      } else {
        consecutiveFrameWarnings = 0;
      }

      lastFrameTimeNanos = frameTimeNanos;
      Choreographer.getInstance().postFrameCallback(this);
    }
  };
}
