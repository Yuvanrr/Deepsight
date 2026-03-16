package com.example.deepsight

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("deepsight_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SELECTED_APPS = "selected_apps"
    }

    fun getSelectedApps(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
    }

    fun setSelectedApps(packageNames: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_APPS, packageNames).apply()
    }

    fun isAppSelected(packageName: String): Boolean {
        return getSelectedApps().contains(packageName)
    }
}
