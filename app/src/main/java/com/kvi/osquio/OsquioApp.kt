package com.kvi.osquio

import android.app.Application
import android.content.Context
import com.kvi.osquio.data.supabase
import com.kvi.osquio.ui.theme.AppTheme
import com.kvi.osquio.ui.theme.ThemeManager

class OsquioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        supabase
        val saved = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString("theme", AppTheme.MIDNIGHT.name)
        ThemeManager.current = runCatching { AppTheme.valueOf(saved!!) }.getOrDefault(AppTheme.MIDNIGHT)
    }
}
