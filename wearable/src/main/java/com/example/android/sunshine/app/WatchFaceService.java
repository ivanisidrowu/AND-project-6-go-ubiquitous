/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchFaceService extends CanvasWatchFaceService {

    private static String TAG = WatchFaceService.class.getSimpleName();

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);


    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {

        private final WeakReference<WatchFaceService.Engine> mWeakReference;

        public EngineHandler(WatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String DATA_PATH = "/weather/weather-info";
        private static final String KEY_WEATHER_ID = "weather-id";
        private static final String KEY_MAX_TEMPERATURE = "high";
        private static final String KEY_MIN_TEMPERATURE = "low";
        private static final String KEY_UUID = "uuid";

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;

        private Paint backgroundPaint;
        private Paint textPaint;
        private Paint timePaint;
        private Paint maxTempPaint;
        private Paint minTempPaint;
        private Paint minTempAmbientPaint;
        private Paint dateAmbientPaint;
        private Paint datePaint;

        private Bitmap mWeatherIcon;
        private String mWeatherHigh;
        private String mWeatherLow;
        private boolean mAmbient;
        private Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            }
        };

        private int mTapCount;

        float mTimeYOffset;

        float mDateYOffset;

        float mDividerYOffset;

        float mWeatherYOffset;

        private Date mDate;

        private SimpleDateFormat mDayOfWeekFormat;

        private SimpleDateFormat mDateFormat;

        private GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WatchFaceService.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y);

            initPaints();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initDateFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(WatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        private void initDateFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("E", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = new SimpleDateFormat("MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        private void initPaints(){
            Resources resources = WatchFaceService.this.getResources();

            backgroundPaint = new Paint();
            backgroundPaint.setColor(resources.getColor(R.color.primary));

            textPaint = new Paint();
            textPaint = createText(NORMAL_TYPEFACE, resources.getColor(R.color.text_color_white));
            timePaint = createText(NORMAL_TYPEFACE,
                    resources.getColor(R.color.text_color_white));
            datePaint = createText(NORMAL_TYPEFACE,
                    resources.getColor(R.color.text_color_grey));
            dateAmbientPaint = createText(NORMAL_TYPEFACE,
                    resources.getColor(R.color.text_color_white));
            maxTempPaint = createText(BOLD_TYPEFACE,
                    resources.getColor(R.color.text_color_white));
            minTempPaint = createText(NORMAL_TYPEFACE,
                    resources.getColor(R.color.text_color_grey));
            minTempAmbientPaint = createText(NORMAL_TYPEFACE,
                    resources.getColor(R.color.text_color_white));
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createText(Typeface typeface, int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
                initDateFormats();
            } else {
                // Disconnect from Google API
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mDateYOffset = resources.getDimension(isRound
                    ? R.dimen.date_y_round : R.dimen.date_y);
            mDividerYOffset = resources.getDimension(isRound
                    ? R.dimen.divider_y_round : R.dimen.divider_y);
            mWeatherYOffset = resources.getDimension(isRound
                    ? R.dimen.weather_y_round : R.dimen.weather_y);

            setTextSizes(isRound);
        }

        private void setTextSizes(boolean isRound){
            Resources resources = WatchFaceService.this.getResources();
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.text_size_round : R.dimen.text_size);

            textPaint.setTextSize(timeTextSize);
            timePaint.setTextSize((float) (tempTextSize * 0.80));
            datePaint.setTextSize(dateTextSize);
            dateAmbientPaint.setTextSize(dateTextSize);
            maxTempPaint.setTextSize(tempTextSize);
            minTempAmbientPaint.setTextSize(tempTextSize);
            minTempPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                setTextAntiAlias(inAmbientMode);
                invalidate();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void setTextAntiAlias(boolean inAmbientMode){
            if (mLowBitAmbient) {
                textPaint.setAntiAlias(!inAmbientMode);
                datePaint.setAntiAlias(!inAmbientMode);
                dateAmbientPaint.setAntiAlias(!inAmbientMode);
                maxTempPaint.setAntiAlias(!inAmbientMode);
                minTempAmbientPaint.setAntiAlias(!inAmbientMode);
                minTempPaint.setAntiAlias(!inAmbientMode);
            }
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    backgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.primary : R.color.primary_dark));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            boolean is24Hour = DateFormat.is24HourFormat(WatchFaceService.this);
            int minute = mCalendar.get(Calendar.MINUTE);
            int amPm = mCalendar.get(Calendar.AM_PM);
            String text;
            if (is24Hour) {
                int hour = mCalendar.get(Calendar.HOUR_OF_DAY);
                text = String.format("%02d:%02d", hour, minute);
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                text = String.format("%d:%02d", hour, minute);
            }

            String amPmText = amPm == Calendar.AM ? "am" : "pm";
            float timeTextLen = textPaint.measureText(text);
            float xOffsetTime = timeTextLen / 2;
            if (mAmbient) {
                if (!is24Hour) {
                    xOffsetTime = xOffsetTime + (timePaint.measureText(amPmText) / 2);
                }
            }
            float xOffsetTimeFromCenter = bounds.centerX() - xOffsetTime;
            canvas.drawText(text, xOffsetTimeFromCenter, mTimeYOffset, textPaint);
            if (mAmbient) {
                if (!is24Hour) {
                    canvas.drawText(amPmText, xOffsetTimeFromCenter + timeTextLen + 5, mTimeYOffset,
                            timePaint);
                }
            }

            // Decide which paint to use for the next bits dependent on ambient mode.
            Paint datePaint = mAmbient ? dateAmbientPaint : this.datePaint;

            // Draw the date
            String dayOfWeekString = mDayOfWeekFormat.format(mDate);
            String monthOfYearString = mDateFormat.format(mCalendar.getTime());

            String dateText = String
                    .format("%s, %s", dayOfWeekString, monthOfYearString);
            float xOffsetDate = datePaint.measureText(dateText) / 2;
            canvas.drawText(dateText, bounds.centerX() - xOffsetDate, mDateYOffset, datePaint);

            // Draw high and low temp if we have it
            if (mWeatherHigh != null && mWeatherLow != null && mWeatherIcon != null) {
                // Draw a line to separate date and time from weather elements
                canvas.drawLine(bounds.centerX() - 15, mDividerYOffset, bounds.centerX() + 15,
                        mDividerYOffset, datePaint);
                float highTextLen = maxTempPaint.measureText(mWeatherHigh);

                if (mAmbient) {
                    float lowTextLen = minTempAmbientPaint.measureText(mWeatherLow);
                    float xOffset = bounds.centerX() - ((highTextLen + lowTextLen + 20) / 2);
                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, maxTempPaint);
                    canvas.drawText(mWeatherLow, xOffset + highTextLen + 20, mWeatherYOffset,
                            minTempAmbientPaint);
                } else {
                    float xOffset = bounds.centerX() - (highTextLen / 2);
                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, maxTempPaint);
                    canvas.drawText(mWeatherLow, bounds.centerX() + (highTextLen / 2) + 20,
                            mWeatherYOffset, minTempPaint);
                    float iconXOffset = bounds.centerX() - ((highTextLen / 2) + mWeatherIcon
                            .getWidth() + 30);
                    canvas.drawBitmap(mWeatherIcon, iconXOffset,
                            mWeatherYOffset - mWeatherIcon.getHeight(), null);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            requestWeatherInfo();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {

                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(DATA_PATH)) {
                    continue;
                }

                DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem())
                        .getDataMap();
                mWeatherHigh = dataMap.containsKey(KEY_MAX_TEMPERATURE) ? dataMap
                        .getString(KEY_MAX_TEMPERATURE) : mWeatherHigh;
                mWeatherLow = dataMap.containsKey(KEY_MIN_TEMPERATURE) ? dataMap
                        .getString(KEY_MIN_TEMPERATURE) : mWeatherLow;
                String path = dataEvent.getDataItem().getUri().getPath();

                Log.d(TAG, "onDataChanged: " + path);
                if (dataMap.containsKey(KEY_WEATHER_ID)) {
                    int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                    Drawable b = getResources().getDrawable(DrawableUtil.getWeatherIcon(weatherId));
                    Bitmap icon = ((BitmapDrawable) b).getBitmap();
                    float scaledWidth =
                            (maxTempPaint.getTextSize() / icon.getHeight()) * icon
                                    .getWidth();
                    mWeatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth,
                            (int) maxTempPaint.getTextSize(), true);

                }

                invalidate();
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }

        private void requestWeatherInfo() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(DATA_PATH);
            putDataMapRequest.getDataMap().putString(KEY_UUID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Failed fetching data");
                                return;
                            }
                        }
                    });
        }
    }

}
