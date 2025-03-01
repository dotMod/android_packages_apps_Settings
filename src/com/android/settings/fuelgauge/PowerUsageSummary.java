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

package com.android.settings.fuelgauge;

import static com.android.settings.fuelgauge.BatteryBroadcastReceiver.BatteryUpdateType;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.icu.text.NumberFormat;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.SearchIndexableResource;
import android.provider.Settings.Global;
import android.text.format.Formatter;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settings.core.SubSettingLauncher;
import com.android.settings.fuelgauge.batterytip.BatteryTipLoader;
import com.android.settings.fuelgauge.batterytip.BatteryTipPreferenceController;
import com.android.settings.fuelgauge.batterytip.tips.BatteryTip;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.utils.PowerUtil;
import com.android.settingslib.utils.StringUtil;
import com.android.settingslib.widget.LayoutPreference;

import java.util.List;

/**
 * Displays a list of apps and subsystems that consume power, ordered by how much power was consumed
 * since the last time it was unplugged.
 */
@SearchIndexable(forTarget = SearchIndexable.ALL & ~SearchIndexable.ARC)
public class PowerUsageSummary extends PowerUsageBase implements OnLongClickListener,
        BatteryTipPreferenceController.BatteryTipListener {

    static final String TAG = "PowerUsageSummary";

    private static final String KEY_BATTERY_HEADER = "battery_header";
    private static final String KEY_BATTERY_STATS_LAYOUT = "battery_stats_view";
    private static final int BATTERY_ANIMATION_DURATION_MS_PER_LEVEL = 30;

    @VisibleForTesting
    static final String ARG_BATTERY_LEVEL = "key_battery_level";

    private static final String KEY_SCREEN_USAGE = "screen_usage";
    private static final String KEY_TIME_SINCE_LAST_FULL_CHARGE = "last_full_charge";

    @VisibleForTesting
    static final int BATTERY_INFO_LOADER = 1;
    @VisibleForTesting
    static final int BATTERY_TIP_LOADER = 2;
    @VisibleForTesting
    static final int MENU_ADVANCED_BATTERY = Menu.FIRST + 1;
    static final int MENU_STATS_RESET = Menu.FIRST + 2;
    public static final int DEBUG_INFO_LOADER = 3;

    @VisibleForTesting
    int mBatteryLevel;
    @VisibleForTesting
    PowerUsageFeatureProvider mPowerFeatureProvider;
    @VisibleForTesting
    BatteryUtils mBatteryUtils;
    @VisibleForTesting
    LayoutPreference mBatteryLayoutPref;
    @VisibleForTesting
    LayoutPreference mBatteryStatsLayoutPref;
    @VisibleForTesting
    BatteryStatsView mBatteryStatsView;
    @VisibleForTesting
    BatteryInfo mBatteryInfo;

    @VisibleForTesting
    PowerManager mPowerManager;
    @VisibleForTesting
    BatteryHeaderPreferenceController mBatteryHeaderPreferenceController;
    @VisibleForTesting
    boolean mNeedUpdateBatteryTip;
    @VisibleForTesting
    BatteryTipPreferenceController mBatteryTipPreferenceController;
    @VisibleForTesting
    ValueAnimator animator;

    @VisibleForTesting
    final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            restartBatteryInfoLoader();
        }
    };

    @VisibleForTesting
    LoaderManager.LoaderCallbacks<BatteryInfo> mBatteryInfoLoaderCallbacks =
            new LoaderManager.LoaderCallbacks<BatteryInfo>() {

                @Override
                public Loader<BatteryInfo> onCreateLoader(int i, Bundle bundle) {
                    return new BatteryInfoLoader(getContext(), mStatsHelper);
                }

                @Override
                public void onLoadFinished(Loader<BatteryInfo> loader, BatteryInfo batteryInfo) {
                    updateHeaderPreference(batteryInfo);
                    mBatteryInfo = batteryInfo;
                    updateLastFullChargePreference();
                }

                @Override
                public void onLoaderReset(Loader<BatteryInfo> loader) {
                    // do nothing
                }
            };

    LoaderManager.LoaderCallbacks<List<BatteryInfo>> mBatteryInfoDebugLoaderCallbacks =
            new LoaderCallbacks<List<BatteryInfo>>() {
                @Override
                public Loader<List<BatteryInfo>> onCreateLoader(int i, Bundle bundle) {
                    return new DebugEstimatesLoader(getContext(), mStatsHelper);
                }

                @Override
                public void onLoadFinished(Loader<List<BatteryInfo>> loader,
                        List<BatteryInfo> batteryInfos) {
                    updateViews(batteryInfos);
                }

                @Override
                public void onLoaderReset(Loader<List<BatteryInfo>> loader) {
                }
            };

    protected void updateViews(List<BatteryInfo> batteryInfos) {
        final BatteryMeterView batteryView = mBatteryLayoutPref
                .findViewById(R.id.battery_header_icon);
        final TextView summary1 = mBatteryLayoutPref.findViewById(R.id.summary1);
        BatteryInfo oldInfo = batteryInfos.get(0);
        BatteryInfo newInfo = batteryInfos.get(1);
        batteryView.setText(Utils.formatPercentage(oldInfo.batteryLevel));

        // set the text to the old estimate (copied from battery info). Note that this
        // can sometimes say 0 time remaining because battery stats requires the phone
        // be unplugged for a period of time before being willing ot make an estimate.
        final String OldEstimateString = mPowerFeatureProvider.getOldEstimateDebugString(
                Formatter.formatShortElapsedTime(getContext(),
                        PowerUtil.convertUsToMs(oldInfo.remainingTimeUs)));
        final String NewEstimateString = mPowerFeatureProvider.getEnhancedEstimateDebugString(
                Formatter.formatShortElapsedTime(getContext(),
                        PowerUtil.convertUsToMs(newInfo.remainingTimeUs)));
        summary1.setText(OldEstimateString + "\n" + NewEstimateString);

        batteryView.setBatteryLevel(oldInfo.batteryLevel);
        batteryView.setCharging(!oldInfo.discharging);
    }

    private LoaderManager.LoaderCallbacks<List<BatteryTip>> mBatteryTipsCallbacks =
            new LoaderManager.LoaderCallbacks<List<BatteryTip>>() {

                @Override
                public Loader<List<BatteryTip>> onCreateLoader(int id, Bundle args) {
                    return new BatteryTipLoader(getContext(), mStatsHelper);
                }

                @Override
                public void onLoadFinished(Loader<List<BatteryTip>> loader,
                        List<BatteryTip> data) {
                    mBatteryTipPreferenceController.updateBatteryTips(data);
                }

                @Override
                public void onLoaderReset(Loader<List<BatteryTip>> loader) {

                }
            };

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        final SettingsActivity activity = (SettingsActivity) getActivity();

        mBatteryHeaderPreferenceController = use(BatteryHeaderPreferenceController.class);
        mBatteryHeaderPreferenceController.setActivity(activity);
        mBatteryHeaderPreferenceController.setFragment(this);
        mBatteryHeaderPreferenceController.setLifecycle(getSettingsLifecycle());

        mPowerManager = context.getSystemService(PowerManager.class);

        mBatteryTipPreferenceController = use(BatteryTipPreferenceController.class);
        mBatteryTipPreferenceController.setActivity(activity);
        mBatteryTipPreferenceController.setFragment(this);
        mBatteryTipPreferenceController.setBatteryTipListener(this::onBatteryTipHandled);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setAnimationAllowed(true);

        initFeatureProvider();
        mBatteryLayoutPref = (LayoutPreference) findPreference(KEY_BATTERY_HEADER);
        mBatteryStatsLayoutPref = (LayoutPreference) findPreference(KEY_BATTERY_STATS_LAYOUT);
        mBatteryStatsView = (BatteryStatsView) mBatteryStatsLayoutPref.findViewById(R.id.batteryStatsView);

        mBatteryLevel = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel) + 1;

        mBatteryUtils = BatteryUtils.getInstance(getContext());

        if (Utils.isBatteryPresent(getContext())) {
            restartBatteryInfoLoader();
        }
        mBatteryTipPreferenceController.restoreInstanceState(icicle);
        updateBatteryTipFlag(icicle);
    }
	
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState != null) {
            mBatteryLevel = savedInstanceState.getInt(ARG_BATTERY_LEVEL);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        initHeaderPreference();
        getContentResolver().registerContentObserver(
                Global.getUriFor(Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME),
                false,
                mSettingsObserver);
    }

    @Override
    public void onPause() {
        getContentResolver().unregisterContentObserver(mSettingsObserver);
        detachBatteryHeaderAnimationIfNecessary();
        super.onPause();
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (KEY_BATTERY_HEADER.equals(preference.getKey())) {
            new SubSettingLauncher(getContext())
                        .setDestination(PowerUsageAdvanced.class.getName())
                        .setSourceMetricsCategory(getMetricsCategory())
                        .setTitleRes(R.string.advanced_battery_title)
                        .launch();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.FUELGAUGE_POWER_USAGE_SUMMARY_V2;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.power_usage_summary;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(Menu.NONE, MENU_ADVANCED_BATTERY, Menu.NONE, R.string.advanced_battery_title);
        MenuItem reset = menu.add(0, MENU_STATS_RESET, 0, R.string.battery_stats_reset)
                 .setIcon(R.drawable.ic_delete)
                .setAlphabeticShortcut('d');
        reset.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        super.onCreateOptionsMenu(menu, inflater);
    }

    private void resetStats() {
        AlertDialog dialog = new AlertDialog.Builder(getActivity())
            .setTitle(R.string.battery_stats_reset)
            .setMessage(R.string.battery_stats_message)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mStatsHelper.resetStatistics();
                    refreshUi(BatteryUpdateType.MANUAL);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .create();
        dialog.show();
    }

    @Override
    public int getHelpResource() {
        return R.string.help_url_battery;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_STATS_RESET:
                resetStats();
                return true;
            case MENU_ADVANCED_BATTERY:
                new SubSettingLauncher(getContext())
                        .setDestination(PowerUsageAdvanced.class.getName())
                        .setSourceMetricsCategory(getMetricsCategory())
                        .setTitleRes(R.string.advanced_battery_title)
                        .launch();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    protected void refreshUi(@BatteryUpdateType int refreshType) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        // Skip refreshing UI if battery is not present.
        if (!mIsBatteryPresent) {
            return;
        }

        // Skip BatteryTipLoader if device is rotated or only battery level change
        if (mNeedUpdateBatteryTip
                && refreshType != BatteryUpdateType.BATTERY_LEVEL) {
            restartBatteryTipLoader();
        } else {
            mNeedUpdateBatteryTip = true;
        }
        // reload BatteryInfo and updateUI
        restartBatteryInfoLoader();
        updateLastFullChargePreference();
        mBatteryStatsView.updateSOT(StringUtil.formatElapsedTime(getContext(),
                mBatteryUtils.calculateScreenUsageTime(mStatsHelper), false));
        final long elapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000;
        Intent batteryBroadcast = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryInfo batteryInfo = BatteryInfo.getBatteryInfoOld(context, batteryBroadcast,
                mStatsHelper.getStats(), elapsedRealtimeUs, false);
        updateHeaderPreference(batteryInfo);
    }

    @VisibleForTesting
    void restartBatteryTipLoader() {
        getLoaderManager().restartLoader(BATTERY_TIP_LOADER, Bundle.EMPTY, mBatteryTipsCallbacks);
    }

    @VisibleForTesting
    void setBatteryLayoutPreference(LayoutPreference layoutPreference) {
        mBatteryLayoutPref = layoutPreference;
    }

    @VisibleForTesting
    void updateLastFullChargePreference() {
        if (mBatteryInfo != null && mBatteryInfo.averageTimeToDischarge
                != EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN) {
            mBatteryStatsView.updateLastFullChargeTitle(R.string.battery_full_charge_last);
            mBatteryStatsView.updateLastFullCharge(
                    StringUtil.formatElapsedTime(getContext(), mBatteryInfo.averageTimeToDischarge,
                            false /* withSeconds */));
        } else {
            final long lastFullChargeTime = mBatteryUtils.calculateLastFullChargeTime(mStatsHelper,
                    System.currentTimeMillis());
            mBatteryStatsView.updateLastFullChargeTitle(R.string.battery_last_full_charge);
            mBatteryStatsView.updateLastFullCharge(
                    StringUtil.formatRelativeTime(getContext(), lastFullChargeTime,
                            false /* withSeconds */));
        }
    }

    @VisibleForTesting
    void showBothEstimates() {
        final Context context = getContext();
        if (context == null
                || !mPowerFeatureProvider.isEnhancedBatteryPredictionEnabled(context)) {
            return;
        }
        getLoaderManager().restartLoader(DEBUG_INFO_LOADER, Bundle.EMPTY,
                mBatteryInfoDebugLoaderCallbacks);
    }

    @VisibleForTesting
    void updateHeaderPreference(BatteryInfo info) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final BatteryMeterView batteryView = (BatteryMeterView) mBatteryLayoutPref
                .findViewById(R.id.battery_header_icon);
        final TextView summary1 = (TextView) mBatteryLayoutPref.findViewById(R.id.summary1);
        if (info.remainingLabel == null ) {
            summary1.setText(info.statusLabel);
        } else {
            summary1.setText(info.remainingLabel);
        }
        batteryView.setCharging(!info.discharging);
        batteryView.setPowerSave(mPowerManager.isPowerSaveMode());
        startBatteryHeaderAnimationIfNecessary(batteryView, mBatteryLevel, info.batteryLevel);
    }

    @VisibleForTesting
    void initHeaderPreference() {
        final BatteryMeterView batteryView = (BatteryMeterView) mBatteryLayoutPref
                .findViewById(R.id.battery_header_icon);

        batteryView.setBatteryLevel(mBatteryLevel);
        batteryView.setPowerSave(mPowerManager.isPowerSaveMode());
        batteryView.setText(formatBatteryPercentageText(mBatteryLevel));
    }

    @VisibleForTesting
    void startBatteryHeaderAnimationIfNecessary(BatteryMeterView batteryView, int prevLevel, int currentLevel) {
        mBatteryLevel = currentLevel;
        final int diff = Math.abs(prevLevel - currentLevel);
        if (diff != 0) {
            animator = ValueAnimator.ofInt(prevLevel, currentLevel);
            animator.setDuration(BATTERY_ANIMATION_DURATION_MS_PER_LEVEL * diff);
            animator.setInterpolator(AnimationUtils.loadInterpolator(getContext(),
                    android.R.interpolator.fast_out_slow_in));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    final Integer level = (Integer) animation.getAnimatedValue();
                    batteryView.setBatteryLevel(level);
                    batteryView.setPowerSave(mPowerManager.isPowerSaveMode());
                    batteryView.setText(formatBatteryPercentageText(level));
                }
            });
            animator.start();
        }
    }

    @VisibleForTesting
    void detachBatteryHeaderAnimationIfNecessary() {
        if (animator != null && animator.isRunning()) {
            animator.pause();
        }
    }

    @VisibleForTesting
    void initFeatureProvider() {
        final Context context = getContext();
        mPowerFeatureProvider = FeatureFactory.getFactory(context)
                .getPowerUsageFeatureProvider(context);
    }

    @VisibleForTesting
    void restartBatteryInfoLoader() {
        if (getContext() == null) {
            return;
        }
        // Skip restartBatteryInfoLoader if battery is not present.
        if (!mIsBatteryPresent) {
            return;
        }
        getLoaderManager().restartLoader(BATTERY_INFO_LOADER, Bundle.EMPTY,
                mBatteryInfoLoaderCallbacks);
        if (mPowerFeatureProvider.isEstimateDebugEnabled()) {
            // Set long click action for summary to show debug info
            View header = mBatteryLayoutPref.findViewById(R.id.summary1);
            header.setOnLongClickListener(this);
        }
    }

    @VisibleForTesting
    void updateBatteryTipFlag(Bundle icicle) {
        mNeedUpdateBatteryTip = icicle == null || mBatteryTipPreferenceController.needUpdate();
    }

    @Override
    public boolean onLongClick(View view) {
        showBothEstimates();
        view.setOnLongClickListener(null);
        return true;
    }

    @Override
    protected void restartBatteryStatsLoader(@BatteryUpdateType int refreshType) {
        super.restartBatteryStatsLoader(refreshType);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mBatteryTipPreferenceController.saveInstanceState(outState);
    }

    @Override
    public void onBatteryTipHandled(BatteryTip batteryTip) {
        restartBatteryTipLoader();
    }

    private CharSequence formatBatteryPercentageText(int batteryLevel) {
        return TextUtils.expandTemplate(getContext().getText(R.string.battery_header_title_alternate),
                NumberFormat.getIntegerInstance().format(batteryLevel));
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.power_usage_summary);
}
