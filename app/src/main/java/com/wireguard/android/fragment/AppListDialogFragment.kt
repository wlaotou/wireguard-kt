/*
 * Copyright © 2018 Eric Kuck <eric@bluelinelabs.com>.
 * Copyright © 2018 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.fragment

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.AppListDialogFragmentBinding
import com.wireguard.android.model.ApplicationData
import com.wireguard.android.util.ExceptionLoggers
import com.wireguard.android.util.ObservableKeyedArrayList
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator

class AppListDialogFragment : DialogFragment() {

    private var currentlyExcludedApps: List<String>? = null
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentlyExcludedApps = Arrays.asList(*arguments!!.getStringArray(KEY_EXCLUDED_APPS)!!)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val alertDialogBuilder = AlertDialog.Builder(activity!!)
        alertDialogBuilder.setTitle(R.string.excluded_applications)

        val binding = AppListDialogFragmentBinding.inflate(activity!!.layoutInflater, null, false)
        binding.executePendingBindings()
        alertDialogBuilder.setView(binding.root)

        alertDialogBuilder.setPositiveButton(R.string.set_exclusions) { dialog, which -> setExclusionsAndDismiss() }
        alertDialogBuilder.setNegativeButton(R.string.cancel) { dialog, which -> dialog.dismiss() }
        alertDialogBuilder.setNeutralButton(R.string.deselect_all) { dialog, which -> }

        binding.fragment = this
        binding.appData = appData

        loadData()

        val dialog = alertDialogBuilder.create()
        dialog.setOnShowListener { _ ->
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                for (app in appData)
                    app.isExcludedFromTunnel = false
            }
        }
        return dialog
    }

    private fun loadData() {
        val activity = activity ?: return

        val pm = activity.packageManager
        Application.getAsyncWorker().supplyAsync<List<ApplicationData>> {
            val launcherIntent = Intent(Intent.ACTION_MAIN, null)
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

            val appData = ArrayList<ApplicationData>()
            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo.packageName
                appData.add(
                    ApplicationData(
                        resolveInfo.loadIcon(pm),
                        resolveInfo.loadLabel(pm).toString(),
                        packageName,
                        currentlyExcludedApps!!.contains(packageName)
                    )
                )
            }

            appData.sortWith(Comparator { lhs, rhs -> lhs.name.toLowerCase().compareTo(rhs.name.toLowerCase()) })
            appData
        }.whenComplete { data, throwable ->
            if (data != null) {
                appData.clear()
                appData.addAll(data)
            } else {
                val error = if (throwable != null) ExceptionLoggers.unwrapMessage(throwable) else "Unknown"
                val message = activity.getString(R.string.error_fetching_apps, error)
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                dismissAllowingStateLoss()
            }
        }
    }

    private fun setExclusionsAndDismiss() {
        val excludedApps = ArrayList<String>()
        for (data in appData) {
            if (data.isExcludedFromTunnel) {
                excludedApps.add(data.packageName)
            }
        }

        (targetFragment as AppExclusionListener).onExcludedAppsSelected(excludedApps)
        dismiss()
    }

    interface AppExclusionListener {
        fun onExcludedAppsSelected(excludedApps: List<String>)
    }

    companion object {

        private const val KEY_EXCLUDED_APPS = "excludedApps"

        fun <T> newInstance(
            excludedApps: Array<String>,
            target: T
        ): AppListDialogFragment where T : Fragment, T : AppListDialogFragment.AppExclusionListener {
            val extras = Bundle()
            extras.putStringArray(KEY_EXCLUDED_APPS, excludedApps)
            val fragment = AppListDialogFragment()
            fragment.setTargetFragment(target, 0)
            fragment.arguments = extras
            return fragment
        }
    }
}
