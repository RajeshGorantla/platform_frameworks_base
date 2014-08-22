/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.View;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * The list of currently displaying notifications.
 */
public class NotificationData {

    private final Environment mEnvironment;

    public static final class Entry {
        public String key;
        public StatusBarNotification notification;
        public StatusBarIconView icon;
        public ExpandableNotificationRow row; // the outer expanded view
        public View expanded; // the inflated RemoteViews
        public View expandedPublic; // for insecure lockscreens
        public View expandedBig;
        private boolean interruption;
        public boolean autoRedacted; // whether the redacted notification was generated by us
        public boolean legacy; // whether the notification has a legacy, dark background

        public Entry(StatusBarNotification n, StatusBarIconView ic) {
            this.key = n.getKey();
            this.notification = n;
            this.icon = ic;
        }
        public void setBigContentView(View bigContentView) {
            this.expandedBig = bigContentView;
            row.setExpandable(bigContentView != null);
        }
        public View getBigContentView() {
            return expandedBig;
        }
        public View getPublicContentView() { return expandedPublic; }

        public void setInterruption() {
            interruption = true;
        }

        public boolean hasInterrupted() {
            return interruption;
        }

        /**
         * Resets the notification entry to be re-used.
         */
        public void reset() {
            // NOTE: Icon needs to be preserved for now.
            // We should fix this at some point.
            expanded = null;
            expandedPublic = null;
            expandedBig = null;
            autoRedacted = false;
            legacy = false;
            if (row != null) {
                row.reset();
            }
        }
    }

    private final ArrayMap<String, Entry> mEntries = new ArrayMap<>();
    private final ArrayList<Entry> mSortedAndFiltered = new ArrayList<>();

    private RankingMap mRankingMap;
    private final Ranking mTmpRanking = new Ranking();
    private final Comparator<Entry> mRankingComparator = new Comparator<Entry>() {
        private final Ranking mRankingA = new Ranking();
        private final Ranking mRankingB = new Ranking();

        @Override
        public int compare(Entry a, Entry b) {
            if (mRankingMap != null) {
                mRankingMap.getRanking(a.key, mRankingA);
                mRankingMap.getRanking(b.key, mRankingB);
                return mRankingA.getRank() - mRankingB.getRank();
            }

            final StatusBarNotification na = a.notification;
            final StatusBarNotification nb = b.notification;
            int d = nb.getScore() - na.getScore();
            if (a.interruption != b.interruption) {
                return a.interruption ? -1 : 1;
            } else if (d != 0) {
                return d;
            } else {
                return (int) (nb.getNotification().when - na.getNotification().when);
            }
        }
    };

    public NotificationData(Environment environment) {
        mEnvironment = environment;
    }

    /**
     * Returns the sorted list of active notifications (depending on {@link Environment}
     *
     * <p>
     * This call doesn't update the list of active notifications. Call {@link #filterAndSort()}
     * when the environment changes.
     * <p>
     * Don't hold on to or modify the returned list.
     */
    public ArrayList<Entry> getActiveNotifications() {
        return mSortedAndFiltered;
    }

    public Entry get(String key) {
        return mEntries.get(key);
    }

    public void add(Entry entry, RankingMap ranking) {
        mEntries.put(entry.notification.getKey(), entry);
        updateRankingAndSort(ranking);
    }

    public Entry remove(String key, RankingMap ranking) {
        Entry removed = mEntries.remove(key);
        if (removed == null) return null;
        updateRankingAndSort(ranking);
        return removed;
    }

    public void updateRanking(RankingMap ranking) {
        updateRankingAndSort(ranking);
    }

    public boolean isAmbient(String key) {
        if (mRankingMap != null) {
            mRankingMap.getRanking(key, mTmpRanking);
            return mTmpRanking.isAmbient();
        }
        return false;
    }

    private void updateRankingAndSort(RankingMap ranking) {
        if (ranking != null) {
            mRankingMap = ranking;
        }
        filterAndSort();
    }

    // TODO: This should not be public. Instead the Environment should notify this class when
    // anything changed, and this class should call back the UI so it updates itself.
    public void filterAndSort() {
        mSortedAndFiltered.clear();

        ArraySet<String> groupsWithSummaries = null;
        final int N = mEntries.size();
        for (int i = 0; i < N; i++) {
            Entry entry = mEntries.valueAt(i);
            StatusBarNotification sbn = entry.notification;

            if (shouldFilterOut(sbn)) {
                continue;
            }

            if (sbn.getNotification().isGroupSummary()) {
                if (groupsWithSummaries == null) {
                    groupsWithSummaries = new ArraySet<>();
                }
                groupsWithSummaries.add(sbn.getGroupKey());
            }
            mSortedAndFiltered.add(entry);
        }

        // Second pass: Filter out group children with summary.
        if (groupsWithSummaries != null) {
            final int M = mSortedAndFiltered.size();
            for (int i = M - 1; i >= 0; i--) {
                Entry ent = mSortedAndFiltered.get(i);
                StatusBarNotification sbn = ent.notification;
                if (sbn.getNotification().isGroupChild() &&
                        groupsWithSummaries.contains(sbn.getGroupKey())) {
                    mSortedAndFiltered.remove(i);
                }
            }
        }

        Collections.sort(mSortedAndFiltered, mRankingComparator);
    }

    private boolean shouldFilterOut(StatusBarNotification sbn) {
        if (!(mEnvironment.isDeviceProvisioned() ||
                showNotificationEvenIfUnprovisioned(sbn))) {
            return true;
        }

        if (!mEnvironment.isNotificationForCurrentProfiles(sbn)) {
            return true;
        }

        if (sbn.getNotification().visibility == Notification.VISIBILITY_SECRET &&
                mEnvironment.shouldHideSensitiveContents(sbn.getUserId())) {
            return true;
        }
        return false;
    }

    /**
     * Return whether there are any clearable notifications (that aren't errors).
     */
    public boolean hasActiveClearableNotifications() {
        for (Entry e : mSortedAndFiltered) {
            if (e.expanded != null) { // the view successfully inflated
                if (e.notification.isClearable()) {
                    return true;
                }
            }
        }
        return false;
    }

    // Q: What kinds of notifications should show during setup?
    // A: Almost none! Only things coming from the system (package is "android") that also
    // have special "kind" tags marking them as relevant for setup (see below).
    public static boolean showNotificationEvenIfUnprovisioned(StatusBarNotification sbn) {
        return "android".equals(sbn.getPackageName())
                && sbn.getNotification().extras.getBoolean(Notification.EXTRA_ALLOW_DURING_SETUP);
    }

    public void dump(PrintWriter pw, String indent) {
        int N = mSortedAndFiltered.size();
        pw.print(indent);
        pw.println("active notifications: " + N);
        int active;
        for (active = 0; active < N; active++) {
            NotificationData.Entry e = mSortedAndFiltered.get(active);
            dumpEntry(pw, indent, active, e);
        }

        int M = mEntries.size();
        pw.print(indent);
        pw.println("inactive notifications: " + (M - active));
        int inactiveCount = 0;
        for (int i = 0; i < M; i++) {
            Entry entry = mEntries.valueAt(i);
            if (!mSortedAndFiltered.contains(entry)) {
                dumpEntry(pw, indent, inactiveCount, entry);
                inactiveCount++;
            }
        }
    }

    private void dumpEntry(PrintWriter pw, String indent, int i, Entry e) {
        pw.print(indent);
        pw.println("  [" + i + "] key=" + e.key + " icon=" + e.icon);
        StatusBarNotification n = e.notification;
        pw.print(indent);
        pw.println("      pkg=" + n.getPackageName() + " id=" + n.getId() + " score=" +
                n.getScore());
        pw.print(indent);
        pw.println("      notification=" + n.getNotification());
        pw.print(indent);
        pw.println("      tickerText=\"" + n.getNotification().tickerText + "\"");
    }

    /**
     * Provides access to keyguard state and user settings dependent data.
     */
    public interface Environment {
        public boolean shouldHideSensitiveContents(int userId);
        public boolean isDeviceProvisioned();
        public boolean isNotificationForCurrentProfiles(StatusBarNotification sbn);
    }
}
