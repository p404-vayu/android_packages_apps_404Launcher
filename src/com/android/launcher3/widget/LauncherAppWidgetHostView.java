/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.launcher3.widget;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AdapterView;
import android.widget.Advanceable;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.CheckLongPressHelper;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.BaseDragLayer.TouchCompleteListener;
import com.android.launcher3.widget.dragndrop.AppWidgetHostViewDragListener;

import java.util.List;

/**
 * {@inheritDoc}
 */
public class LauncherAppWidgetHostView extends NavigableAppWidgetHostView
        implements TouchCompleteListener, View.OnLongClickListener,
        LocalColorExtractor.Listener {

    private static final String LOG_TAG = "LauncherAppWidgetHostView";

    // Related to the auto-advancing of widgets
    private static final long ADVANCE_INTERVAL = 20000;
    private static final long ADVANCE_STAGGER = 250;

    // Maintains a list of widget ids which are supposed to be auto advanced.
    private static final SparseBooleanArray sAutoAdvanceWidgetIds = new SparseBooleanArray();

    protected final LayoutInflater mInflater;

    private final CheckLongPressHelper mLongPressHelper;
    protected final Launcher mLauncher;
    private final Workspace mWorkspace;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mReinflateOnConfigChange;

    // Maintain the color manager.
    private final LocalColorExtractor mColorExtractor;

    private boolean mIsScrollable;
    private boolean mIsAttachedToWindow;
    private boolean mIsAutoAdvanceRegistered;
    private boolean mIsInDragMode = false;
    private Runnable mAutoAdvanceRunnable;
    private RectF mLastLocationRegistered = null;
    @Nullable private AppWidgetHostViewDragListener mDragListener;

    // Used to store the widget size during onLayout.
    private final Rect mCurrentWidgetSize = new Rect();
    private final Rect mWidgetSizeAtDrag = new Rect();
    private final RectF mTempRectF = new RectF();
    private final boolean mIsRtl;
    private final Rect mEnforcedRectangle = new Rect();
    private final float mEnforcedCornerRadius;
    private final ViewOutlineProvider mCornerRadiusEnforcementOutline = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (mEnforcedRectangle.isEmpty() || mEnforcedCornerRadius <= 0) {
                outline.setEmpty();
            } else {
                outline.setRoundRect(mEnforcedRectangle, mEnforcedCornerRadius);
            }
        }
    };

    public LauncherAppWidgetHostView(Context context) {
        super(context);
        mLauncher = Launcher.getLauncher(context);
        mWorkspace = mLauncher.getWorkspace();
        mLongPressHelper = new CheckLongPressHelper(this, this);
        mInflater = LayoutInflater.from(context);
        setAccessibilityDelegate(mLauncher.getAccessibilityDelegate());
        setBackgroundResource(R.drawable.widget_internal_focus_bg);

        setExecutor(Executors.THREAD_POOL_EXECUTOR);
        if (Utilities.ATLEAST_Q && Themes.getAttrBoolean(mLauncher, R.attr.isWorkspaceDarkText)) {
            setOnLightBackground(true);
        }
        mIsRtl = Utilities.isRtl(context.getResources());
        mColorExtractor = LocalColorExtractor.newInstance(getContext());
        mColorExtractor.setListener(this);

        mEnforcedCornerRadius = RoundedCornerEnforcement.computeEnforcedRadius(getContext());
    }

    @Override
    public void setColorResources(@Nullable SparseIntArray colors) {
        if (colors == null) {
            resetColorResources();
        } else {
            super.setColorResources(colors);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mIsInDragMode && mDragListener != null) {
            mDragListener.onDragContentChanged();
        }
    }

    @Override
    public boolean onLongClick(View view) {
        if (mIsScrollable) {
            DragLayer dragLayer = mLauncher.getDragLayer();
            dragLayer.requestDisallowInterceptTouchEvent(false);
        }
        view.performLongClick();
        return true;
    }

    @Override
    protected View getErrorView() {
        return mInflater.inflate(R.layout.appwidget_error, this, false);
    }

    @Override
    public void updateAppWidget(RemoteViews remoteViews) {
        super.updateAppWidget(remoteViews);

        // The provider info or the views might have changed.
        checkIfAutoAdvance();

        // It is possible that widgets can receive updates while launcher is not in the foreground.
        // Consequently, the widgets will be inflated for the orientation of the foreground activity
        // (framework issue). On resuming, we ensure that any widgets are inflated for the current
        // orientation.
        mReinflateOnConfigChange = !isSameOrientation();
    }

    private boolean isSameOrientation() {
        return mLauncher.getResources().getConfiguration().orientation ==
                mLauncher.getOrientation();
    }

    private boolean checkScrollableRecursively(ViewGroup viewGroup) {
        if (viewGroup instanceof AdapterView) {
            return true;
        } else {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child instanceof ViewGroup) {
                    if (checkScrollableRecursively((ViewGroup) child)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            DragLayer dragLayer = mLauncher.getDragLayer();
            if (mIsScrollable) {
                dragLayer.requestDisallowInterceptTouchEvent(true);
            }
            dragLayer.setTouchCompleteListener(this);
        }
        mLongPressHelper.onTouchEvent(ev);
        return mLongPressHelper.hasPerformedLongPress();
    }

    public boolean onTouchEvent(MotionEvent ev) {
        mLongPressHelper.onTouchEvent(ev);
        // We want to keep receiving though events to be able to cancel long press on ACTION_UP
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mIsAttachedToWindow = true;
        checkIfAutoAdvance();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // We can't directly use isAttachedToWindow() here, as this is called before the internal
        // state is updated. So isAttachedToWindow() will return true until next frame.
        mIsAttachedToWindow = false;
        checkIfAutoAdvance();
        mColorExtractor.removeLocations();
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }

    @Override
    public AppWidgetProviderInfo getAppWidgetInfo() {
        AppWidgetProviderInfo info = super.getAppWidgetInfo();
        if (info != null && !(info instanceof LauncherAppWidgetProviderInfo)) {
            throw new IllegalStateException("Launcher widget must have"
                    + " LauncherAppWidgetProviderInfo");
        }
        return info;
    }

    @Override
    public void onTouchComplete() {
        if (!mLongPressHelper.hasPerformedLongPress()) {
            // If a long press has been performed, we don't want to clear the record of that since
            // we still may be receiving a touch up which we want to intercept
            mLongPressHelper.cancelLongPress();
        }
    }

    public void switchToErrorView() {
        // Update the widget with 0 Layout id, to reset the view to error view.
        updateAppWidget(new RemoteViews(getAppWidgetInfo().provider.getPackageName(), 0));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        try {
            super.onLayout(changed, left, top, right, bottom);
        } catch (final RuntimeException e) {
            post(new Runnable() {
                @Override
                public void run() {
                    switchToErrorView();
                }
            });
        }

        mIsScrollable = checkScrollableRecursively(this);

        if (!mIsInDragMode && getTag() instanceof LauncherAppWidgetInfo) {
            mCurrentWidgetSize.left = left;
            mCurrentWidgetSize.top = top;
            mCurrentWidgetSize.right = right;
            mCurrentWidgetSize.bottom = bottom;
            LauncherAppWidgetInfo info = (LauncherAppWidgetInfo) getTag();
            int pageId = mWorkspace.getPageIndexForScreenId(info.screenId);
            updateColorExtraction(mCurrentWidgetSize, pageId);
        }

        enforceRoundedCorners();
    }

    /** Starts the drag mode. */
    public void startDrag(AppWidgetHostViewDragListener dragListener) {
        mIsInDragMode = true;
        mDragListener = dragListener;
    }

    /** Handles a drag event occurred on a workspace page, {@code pageId}. */
    public void handleDrag(Rect rect, int pageId) {
        mWidgetSizeAtDrag.set(rect);
        updateColorExtraction(mWidgetSizeAtDrag, pageId);
    }

    /** Ends the drag mode. */
    public void endDrag() {
        mIsInDragMode = false;
        mDragListener = null;
        mWidgetSizeAtDrag.setEmpty();
        requestLayout();
    }

    private void updateColorExtraction(Rect widgetLocation, int pageId) {
        // If the widget hasn't been measured and laid out, we cannot do this.
        if (widgetLocation.isEmpty()) {
            return;
        }
        int screenWidth = mLauncher.getDeviceProfile().widthPx;
        int screenHeight = mLauncher.getDeviceProfile().heightPx;
        int numScreens = mWorkspace.getNumPagesForWallpaperParallax();
        pageId = mIsRtl ? numScreens - pageId - 1 : pageId;
        float relativeScreenWidth = 1f / numScreens;
        float absoluteTop = widgetLocation.top;
        float absoluteBottom = widgetLocation.bottom;
        View v = this;
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v.getId() != R.id.launcher) {
                break;
            }
            absoluteBottom += v.getTop();
            absoluteTop += v.getTop();
        }
        float xOffset = 0;
        View parentView = (View) getParent();
        // The layout depends on the orientation.
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            int parentViewWidth = parentView == null ? 0 : parentView.getWidth();
            xOffset = screenWidth - mWorkspace.getPaddingRight() - parentViewWidth;
        } else {
            int parentViewPaddingLeft = parentView == null ? 0 : parentView.getPaddingLeft();
            xOffset = mWorkspace.getPaddingLeft() + parentViewPaddingLeft;
        }
        // This is the position of the widget relative to the wallpaper, as expected by the
        // local color extraction of the WallpaperManager.
        // The coordinate system is such that, on the horizontal axis, each screen has a
        // distinct range on the [0,1] segment. So if there are 3 screens, they will have the
        // ranges [0, 1/3], [1/3, 2/3] and [2/3, 1]. The position on the subrange should be
        // the position of the widget relative to the screen. For the vertical axis, this is
        // simply the location of the widget relative to the screen.
        mTempRectF.left = ((widgetLocation.left + xOffset) / screenWidth + pageId)
                * relativeScreenWidth;
        mTempRectF.right = ((widgetLocation.right + xOffset) / screenWidth + pageId)
                * relativeScreenWidth;
        mTempRectF.top = absoluteTop / screenHeight;
        mTempRectF.bottom = absoluteBottom / screenHeight;
        if (mTempRectF.left < 0 || mTempRectF.right > 1 || mTempRectF.top < 0
                || mTempRectF.bottom > 1) {
            Log.e(LOG_TAG, "   Error, invalid relative position");
            return;
        }
        if (!mTempRectF.equals(mLastLocationRegistered)) {
            if (mLastLocationRegistered != null) {
                mColorExtractor.removeLocations();
            }
            mLastLocationRegistered = new RectF(mTempRectF);
            mColorExtractor.addLocation(List.of(mLastLocationRegistered));
        }
    }

    @Override
    public void onColorsChanged(RectF rectF, SparseIntArray colors) {
        // setColorResources will reapply the view, which must happen in the UI thread.
        post(() -> setColorResources(colors));
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(getClass().getName());
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        maybeRegisterAutoAdvance();

        if (visibility == View.VISIBLE) {
            if (mLastLocationRegistered != null) {
                mColorExtractor.addLocation(List.of(mLastLocationRegistered));
            }
        } else {
            mColorExtractor.removeLocations();
        }
    }

    private void checkIfAutoAdvance() {
        boolean isAutoAdvance = false;
        Advanceable target = getAdvanceable();
        if (target != null) {
            isAutoAdvance = true;
            target.fyiWillBeAdvancedByHostKThx();
        }

        boolean wasAutoAdvance = sAutoAdvanceWidgetIds.indexOfKey(getAppWidgetId()) >= 0;
        if (isAutoAdvance != wasAutoAdvance) {
            if (isAutoAdvance) {
                sAutoAdvanceWidgetIds.put(getAppWidgetId(), true);
            } else {
                sAutoAdvanceWidgetIds.delete(getAppWidgetId());
            }
            maybeRegisterAutoAdvance();
        }
    }

    private Advanceable getAdvanceable() {
        AppWidgetProviderInfo info = getAppWidgetInfo();
        if (info == null || info.autoAdvanceViewId == NO_ID || !mIsAttachedToWindow) {
            return null;
        }
        View v = findViewById(info.autoAdvanceViewId);
        return (v instanceof Advanceable) ? (Advanceable) v : null;
    }

    private void maybeRegisterAutoAdvance() {
        Handler handler = getHandler();
        boolean shouldRegisterAutoAdvance = getWindowVisibility() == VISIBLE && handler != null
                && (sAutoAdvanceWidgetIds.indexOfKey(getAppWidgetId()) >= 0);
        if (shouldRegisterAutoAdvance != mIsAutoAdvanceRegistered) {
            mIsAutoAdvanceRegistered = shouldRegisterAutoAdvance;
            if (mAutoAdvanceRunnable == null) {
                mAutoAdvanceRunnable = this::runAutoAdvance;
            }

            handler.removeCallbacks(mAutoAdvanceRunnable);
            scheduleNextAdvance();
        }
    }

    private void scheduleNextAdvance() {
        if (!mIsAutoAdvanceRegistered) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long advanceTime = now + (ADVANCE_INTERVAL - (now % ADVANCE_INTERVAL)) +
                ADVANCE_STAGGER * sAutoAdvanceWidgetIds.indexOfKey(getAppWidgetId());
        Handler handler = getHandler();
        if (handler != null) {
            handler.postAtTime(mAutoAdvanceRunnable, advanceTime);
        }
    }

    private void runAutoAdvance() {
        Advanceable target = getAdvanceable();
        if (target != null) {
            target.advance();
        }
        scheduleNextAdvance();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinflate when the final configuration is same as the required configuration
        if (mReinflateOnConfigChange && isSameOrientation()) {
            mReinflateOnConfigChange = false;
            reInflate();
        }
    }

    public void reInflate() {
        if (!isAttachedToWindow()) {
            return;
        }
        LauncherAppWidgetInfo info = (LauncherAppWidgetInfo) getTag();
        // Remove and rebind the current widget (which was inflated in the wrong
        // orientation), but don't delete it from the database
        mLauncher.removeItem(this, info, false  /* deleteFromDb */);
        mLauncher.bindAppWidget(info);
    }

    @Override
    protected boolean shouldAllowDirectClick() {
        if (getTag() instanceof ItemInfo) {
            ItemInfo item = (ItemInfo) getTag();
            return item.spanX == 1 && item.spanY == 1;
        }
        return false;
    }

    @UiThread
    private void resetRoundedCorners() {
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setClipToOutline(false);
    }

    @UiThread
    private void enforceRoundedCorners() {
        if (mEnforcedCornerRadius <= 0 || !RoundedCornerEnforcement.isRoundedCornerEnabled()) {
            resetRoundedCorners();
            return;
        }
        View background = RoundedCornerEnforcement.findBackground(this);
        if (background == null
                || RoundedCornerEnforcement.hasAppWidgetOptedOut(this, background)) {
            resetRoundedCorners();
            return;
        }
        RoundedCornerEnforcement.computeRoundedRectangle(this,
                background,
                mEnforcedRectangle);
        setOutlineProvider(mCornerRadiusEnforcementOutline);
        setClipToOutline(true);
    }

    /** Returns the corner radius currently enforced, in pixels. */
    public float getEnforcedCornerRadius() {
        return mEnforcedCornerRadius;
    }

    /** Returns true if the corner radius are enforced for this App Widget. */
    public boolean hasEnforcedCornerRadius() {
        return getClipToOutline();
    }

}
