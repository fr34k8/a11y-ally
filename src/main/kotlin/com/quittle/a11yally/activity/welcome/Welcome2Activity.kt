package com.quittle.a11yally.activity.welcome

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.transition.Scene
import android.transition.TransitionInflater
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.quittle.a11yally.R
import com.quittle.a11yally.activity.FixedContentActivity
import com.quittle.a11yally.activity.LearnMoreActivity
import com.quittle.a11yally.base.RefreshableWeakReference
import com.quittle.a11yally.base.flaggedHasCode
import com.quittle.a11yally.base.orElse
import com.quittle.a11yally.base.time
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class Welcome2Activity : FixedContentActivity() {
    private companion object {
        val TAG = Welcome2Activity::class.simpleName
    }

    override val layoutId = R.layout.welcome2_activity

    private var mIsInListView = false
    private var mApps: List<CheckableAppInfo>? = null

    private val mFallbackAppIconDrawable = RefreshableWeakReference {
        ContextCompat.getDrawable(applicationContext, android.R.drawable.sym_def_app_icon)!!
    }

    private suspend fun getApps(): List<CheckableAppInfo> {
        return withContext(Dispatchers.Default) {
            time(TAG, "Time to initialize app list: %dms") {
                // Only show apps with code
                // Also sort
                // 1. "debuggable" apps first (e.g. debug builds installed by developers)
                // 2. System apps last (most won't be testing a non-debuggable system app)
                // 3. Then sort by friendly app name
                // 4. Finally fallback to sort by package name.
                packageManager.getInstalledApplications(0).filter { app ->
                    app.flaggedHasCode()
                }.map {
                    AppInfo(
                        packageManager.getApplicationLabel(it).toString(),
                        it.packageName,
                        it.flags,
                        RefreshableWeakReference {
                            packageManager.getApplicationIcon(it.packageName)
                        }
                    )
                }.sortedWith(
                    compareBy(
                        { it.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 },
                        { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 },
                        { it.label },
                        { it.packageName }
                    )
                ).map {
                    CheckableAppInfo(it, false)
                }.toList()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("mIsInListView", mIsInListView)
        val t = measureTimeMillis {
            outState.putParcelableArray(
                "mApps",
                (
                    mApps?.map {
                        ParcelableCheckableAppInfo(
                            it.appInfo.let {
                                ParcelableAppInfo(it.label, it.packageName, it.flags)
                            },
                            it.isChecked
                        )
                    }
                    )?.toTypedArray()
            )
        }
        Log.i(TAG, "Serializing in ${t}ms")
    }

    private fun onCreateInitialState(savedInstanceState: Bundle?) {
        val appsGenerator = getAppListAsync(savedInstanceState)

        findViewById<View>(R.id.get_started).setOnClickListener {
            mIsInListView = true
            val sceneList = Scene.getSceneForLayout(
                findViewById(R.id.wrapper), R.layout.welcome2_activity_list_scene, this
            )
            val transition = TransitionInflater.from(this)
                .inflateTransition(R.transition.welcome_transition)
            TransitionManager.go(sceneList, transition)

            onDisplayAppList(appsGenerator, sceneList)
        }

        findViewById<View>(R.id.learn_more)?.setOnClickListener {
            startActivity(Intent(this, LearnMoreActivity::class.java))
        }
    }

    private fun onCreateListState(savedInstanceState: Bundle?) {
        val appsGenerator = getAppListAsync(savedInstanceState)

        mIsInListView = true
        val sceneList = Scene.getSceneForLayout(
            findViewById(R.id.wrapper), R.layout.welcome2_activity_list_scene, this
        )
        val transition = TransitionInflater.from(this)
            .inflateTransition(R.transition.welcome_transition)
            .setDuration(0)
        TransitionManager.go(sceneList, transition)

        onDisplayAppList(appsGenerator, sceneList)
    }

    private fun onDisplayAppList(
        appsGenerator: Deferred<List<CheckableAppInfo>>,
        sceneList: Scene
    ) {
        onBackPressedDispatcher.addCallback(this@Welcome2Activity) {
            mIsInListView = false
            this@Welcome2Activity.recreate()
        }

        sceneList.sceneRoot.findViewById<RecyclerView>(R.id.app_list).apply {
            layoutManager = LinearLayoutManager(this@Welcome2Activity)

            if (!appsGenerator.isCompleted) {
                val loadingString = getString(R.string.welcome2_activity_loading)
                val apps = listOf(
                    CheckableAppInfo(
                        AppInfo(
                            loadingString,
                            loadingString,
                            0,
                            mFallbackAppIconDrawable
                        ),
                        false
                    )
                )

                adapter = AppInfoRecyclerViewAdapter(this@Welcome2Activity, apps)
            }

            lifecycleScope.launch {
                val generatedApps = appsGenerator.await()
                adapter = AppInfoRecyclerViewAdapter(this@Welcome2Activity, generatedApps)
            }
        }
    }

    private fun getAppListAsync(savedInstanceState: Bundle?): Deferred<List<CheckableAppInfo>> {
        val cachedAppList = getCachedAppListFromBundle(savedInstanceState)
        return lifecycleScope.async {
            val ret = cachedAppList.orElse(suspend { getApps() })
            mApps = ret
            ret
        }
    }

    private fun getCachedAppListFromBundle(savedInstanceState: Bundle?): List<CheckableAppInfo>? {
        var ret: List<CheckableAppInfo>? = null
        val t = measureTimeMillis {
            ret = savedInstanceState?.getParcelableArray("mApps")?.map {
                (it as ParcelableCheckableAppInfo).let {
                    CheckableAppInfo(
                        it.appInfo.let {
                            AppInfo(
                                it.label, it.packageName, it.flags,
                                RefreshableWeakReference {
                                    packageManager.getApplicationIcon(it.packageName)
                                }
                            )
                        },
                        it.isChecked
                    )
                }
            }
        }
        Log.i(TAG, "Deserializing in ${t}ms")
        return ret
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mIsInListView = savedInstanceState?.getBoolean("mIsInListView", mIsInListView) == true

        if (!mIsInListView) {
            onCreateInitialState(savedInstanceState)
        } else {
            onCreateListState(savedInstanceState)
        }
    }
}
