package com.mixpanel.android.viewcrawler;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.mixpanel.android.mpmetrics.MPConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@TargetApi(MPConfig.UI_FEATURES_MIN_API)
/* package */ abstract class ViewVisitor implements Pathfinder.Accumulator {

    /**
     * OnEvent will be fired when whatever the ViewVisitor installed fires
     * (For example, if the ViewVisitor installs watches for clicks, then OnEvent will be called
     * on click)
     */
    public interface OnEventListener {
        public void OnEvent(View host, String eventName, boolean debounce);
    }

    /**
     * Attempts to apply mutator to every matching view. Use this to update properties
     * in the view hierarchy. If accessor is non-null, it will be used to attempt to
     * prevent calls to the mutator if the property already has the intended value.
     */
    public static class PropertySetVisitor extends ViewVisitor {
        public PropertySetVisitor(List<Pathfinder.PathElement> path, PropertyCaller mutator, PropertyCaller accessor) {
            super(path);
            mMutator = mutator;
            mAccessor = accessor;
            mOriginalValueHolder = new Object[1];
            mOriginalValues = new WeakHashMap<View, Object>();
        }

        @Override
        public void cleanup() { // TODO this needs to be cleaned up not only on remove, but also on RESET (by id)
            for (Map.Entry<View, Object> original:mOriginalValues.entrySet()) {
                final View changedView = original.getKey();
                final Object originalValue = original.getValue();
                if (null != originalValue) {
                    mOriginalValueHolder[0] = originalValue;
                    mMutator.applyMethodWithArguments(changedView, mOriginalValueHolder);
                }
            }
        }

        @Override
        public void accumulate(View found) {
            if (null != mAccessor) {
                final Object[] setArgs = mMutator.getArgs();
                if (1 == setArgs.length) {
                    final Object desiredValue = setArgs[0];
                    final Object currentValue = mAccessor.applyMethod(found);

                    if (desiredValue == currentValue) {
                        return;
                    }

                    if (null != desiredValue) {
                        if (desiredValue instanceof Bitmap && currentValue instanceof Bitmap) {
                            final Bitmap desiredBitmap = (Bitmap) desiredValue;
                            final Bitmap currentBitmap = (Bitmap) currentValue;
                            if (desiredBitmap.sameAs(currentBitmap)) {
                                return;
                            }
                        } else if (desiredValue.equals(currentValue)) {
                            return;
                        }
                    }

                    if (desiredValue instanceof Bitmap || mOriginalValues.containsKey(found)) {
                        ; // Cache exactly one, non-bitmap original value
                    } else {
                        mOriginalValueHolder[0] = currentValue;
                        if (mMutator.argsAreApplicable(mOriginalValueHolder)) {
                            mOriginalValues.put(found, currentValue);
                        } else {
                            mOriginalValues.put(found, null);
                        }
                    }
                }
            }

            mMutator.applyMethod(found);
        }

        protected String name() {
            return "Property Mutator";
        }

        private final PropertyCaller mMutator;
        private final PropertyCaller mAccessor;
        private final WeakHashMap<View, Object> mOriginalValues;
        private final Object[] mOriginalValueHolder;
    }

    public static class LayoutSetVisitor extends ViewVisitor {
        public LayoutSetVisitor(List<Pathfinder.PathElement> path, LayoutCaller mutator) {
            super(path);
            mOriginalValues = new WeakHashMap<View, int[]>();
            mMutator = mutator;
        }

        @Override
        public void cleanup() {
            for (Map.Entry<View, int[]> original:mOriginalValues.entrySet()) {
                final View changedView = original.getKey();
                final int[] originalValue = original.getValue();
                mMutator.applyMethodWithArguments(changedView, originalValue);
            }
        }

        @Override
        public void accumulate(View found) {
            // currentRules is an array which has rule_index as the index and anchor_id as the value
            // newRule and currentRule are individual rules which look like {rules_index, anchor_id}
            final RelativeLayout.LayoutParams currentParams = (RelativeLayout.LayoutParams)found.getLayoutParams();
            final int[] currentRules = currentParams.getRules();
            final int[] newRule = mMutator.getArgs();
            final int rule_index = newRule[LayoutCaller.RULE_INDEX];
            final int[] currentRule = {rule_index, currentRules[rule_index]};

            if (currentRule[LayoutCaller.ANCHOR_ID] == newRule[LayoutCaller.ANCHOR_ID]) {
                return;
            }

            if (mOriginalValues.containsKey(found)) {
                ; // Cache exactly one
            } else {
                mOriginalValues.put(found, currentRule);
            }
            mMutator.applyMethod(found);
        }

        protected String name() {
            return "Layout Mutator";
        }

        private final WeakHashMap<View, int[]> mOriginalValues;
        private final LayoutCaller mMutator;
    }

    /**
     * Adds an accessibility event, which will fire OnEvent, to every matching view.
     */
    public static class AddAccessibilityEventVisitor extends EventTriggeringVisitor {
        public AddAccessibilityEventVisitor(List<Pathfinder.PathElement> path, int accessibilityEventType, String eventName, OnEventListener listener) {
            super(path, eventName, listener, false);
            mEventType = accessibilityEventType;
            mWatching = new WeakHashMap<View, TrackingAccessibilityDelegate>();
        }

        @Override
        public void cleanup() {
            for (final Map.Entry<View, TrackingAccessibilityDelegate> entry:mWatching.entrySet()) {
                final View v = entry.getKey();
                final TrackingAccessibilityDelegate toCleanup = entry.getValue();
                final View.AccessibilityDelegate currentViewDelegate = getOldDelegate(v);
                if (currentViewDelegate == toCleanup) {
                    v.setAccessibilityDelegate(toCleanup.getRealDelegate());
                } else if (currentViewDelegate instanceof TrackingAccessibilityDelegate) {
                    final TrackingAccessibilityDelegate newChain = (TrackingAccessibilityDelegate) currentViewDelegate;
                    newChain.removeFromDelegateChain(toCleanup);
                } else {
                    // Assume we've been replaced, zeroed out, or for some other reason we're already gone.
                    // (This isn't too weird, for example, it's expected when views get recycled)
                }
            }
            mWatching.clear();
        }

        @Override
        public void accumulate(View found) {
            final View.AccessibilityDelegate realDelegate = getOldDelegate(found);
            if (realDelegate instanceof TrackingAccessibilityDelegate) {
                final TrackingAccessibilityDelegate currentTracker = (TrackingAccessibilityDelegate) realDelegate;
                if (currentTracker.willFireEvent(getEventName())) {
                    return; // Don't double track
                }
            }

            // We aren't already in the tracking call chain of the view
            final TrackingAccessibilityDelegate newDelegate = new TrackingAccessibilityDelegate(realDelegate);
            found.setAccessibilityDelegate(newDelegate);
            mWatching.put(found, newDelegate);
        }

        @Override
        protected String name() {
            return getEventName() + " event when (" + mEventType + ")";
        }

        private View.AccessibilityDelegate getOldDelegate(View v) {
            View.AccessibilityDelegate ret = null;
            try {
                Class<?> klass = v.getClass();
                Method m = klass.getMethod("getAccessibilityDelegate");
                ret = (View.AccessibilityDelegate) m.invoke(v);
            } catch (NoSuchMethodException e) {
                // In this case, we just overwrite the original.
            } catch (IllegalAccessException e) {
                // In this case, we just overwrite the original.
            } catch (InvocationTargetException e) {
                Log.w(LOGTAG, "getAccessibilityDelegate threw an exception when called.", e);
            }

            return ret;
        }

        private class TrackingAccessibilityDelegate extends View.AccessibilityDelegate {
            public TrackingAccessibilityDelegate(View.AccessibilityDelegate realDelegate) {
                mRealDelegate = realDelegate;
            }

            public View.AccessibilityDelegate getRealDelegate() {
                return mRealDelegate;
            }

            public boolean willFireEvent(final String eventName) {
                if (getEventName() == eventName) {
                    return true;
                } else if (mRealDelegate instanceof TrackingAccessibilityDelegate) {
                    return ((TrackingAccessibilityDelegate) mRealDelegate).willFireEvent(eventName);
                } else {
                    return false;
                }
            }

            public void removeFromDelegateChain(final TrackingAccessibilityDelegate other) {
                if (mRealDelegate == other) {
                    mRealDelegate = other.getRealDelegate();
                } else if (mRealDelegate instanceof TrackingAccessibilityDelegate) {
                    final TrackingAccessibilityDelegate child = (TrackingAccessibilityDelegate) mRealDelegate;
                    child.removeFromDelegateChain(other);
                } else {
                    // We can't see any further down the chain, just return.
                }
            }

            @Override
            public void sendAccessibilityEvent(View host, int eventType) {
                if (eventType == mEventType) {
                    fireEvent(host);
                }

                if (null != mRealDelegate) {
                    mRealDelegate.sendAccessibilityEvent(host, eventType);
                }
            }

            private View.AccessibilityDelegate mRealDelegate;
        }

        private final int mEventType;
        private final WeakHashMap<View, TrackingAccessibilityDelegate> mWatching;
    }

    /**
     * Installs a TextWatcher in each matching view. Does nothing if matching views are not TextViews.
     */
    public static class AddTextChangeListener extends EventTriggeringVisitor {
        public AddTextChangeListener(List<Pathfinder.PathElement> path, String eventName, OnEventListener listener) {
            super(path, eventName, listener, true);
            mWatching = new HashMap<TextView, TextWatcher>();
        }

        @Override
        public void cleanup() {
            for (final Map.Entry<TextView, TextWatcher> entry:mWatching.entrySet()) {
                final TextView v = entry.getKey();
                final TextWatcher watcher = entry.getValue();
                v.removeTextChangedListener(watcher);
            }

            mWatching.clear();
        }

        @Override
        public void accumulate(View found) {
            if (found instanceof TextView) {
                final TextView foundTextView = (TextView) found;
                final TextWatcher watcher = new TrackingTextWatcher(foundTextView);
                final TextWatcher oldWatcher = mWatching.get(foundTextView);
                if (null != oldWatcher) {
                    foundTextView.removeTextChangedListener(oldWatcher);
                }
                foundTextView.addTextChangedListener(watcher);
                mWatching.put(foundTextView, watcher);
            }
        }

        @Override
        protected String name() {
            return getEventName() + " on Text Change";
        }

        private class TrackingTextWatcher implements TextWatcher {
            public TrackingTextWatcher(View boundTo) {
                mBoundTo = boundTo;
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                ; // Nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ; // Nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                fireEvent(mBoundTo);
            }

            private final View mBoundTo;
        }

        private final Map<TextView, TextWatcher> mWatching;
    }

    /**
     * Monitors the view tree for the appearance of matching views where there were not
     * matching views before. Fires only once per traversal.
     */
    public static class ViewDetectorVisitor extends EventTriggeringVisitor {
        public ViewDetectorVisitor(List<Pathfinder.PathElement> path, String eventName, OnEventListener listener) {
            super(path, eventName, listener, false);
            mSeen = false;
        }

        @Override
        public void cleanup() {
            ; // Do nothing, we don't have anything to leak :)
        }

        @Override
        public void accumulate(View found) {
            if (found != null && !mSeen) {
                fireEvent(found);
            }

            mSeen = (found != null);
        }

        @Override
        protected String name() {
            return getEventName() + " when Detected";
        }

        private boolean mSeen;
    }

    private static abstract class EventTriggeringVisitor extends ViewVisitor {
        public EventTriggeringVisitor(List<Pathfinder.PathElement> path, String eventName, OnEventListener listener, boolean debounce) {
            super(path);
            mListener = listener;
            mEventName = eventName;
            mDebounce = debounce;
        }

        protected void fireEvent(View found) {
            mListener.OnEvent(found, mEventName, mDebounce);
        }

        protected String getEventName() {
            return mEventName;
        }

        private final OnEventListener mListener;
        private final String mEventName;
        private final boolean mDebounce;
    }

    /**
     * Scans the View hierarchy below rootView, applying it's operation to each matching child view.
     */
    public void visit(View rootView) {
        mPathfinder.findTargetsInRoot(rootView, mPath, this);
    }

    /**
     * Removes listeners and frees resources associated with the visitor. Once cleanup is called,
     * the ViewVisitor should not be used again.
     */
    public abstract void cleanup();

    protected ViewVisitor(List<Pathfinder.PathElement> path) {
        mPath = path;
        mPathfinder = new Pathfinder();
    }

    protected abstract String name();

    private final List<Pathfinder.PathElement> mPath;
    private final Pathfinder mPathfinder;


    private static final String LOGTAG = "MixpanelAPI.ViewVisitor";
}
