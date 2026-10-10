package com.example.mycar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.mycar.auto.CarConnectionWatcher
import com.example.mycar.capture.ScreenMirrorService
import com.example.mycar.touch.TouchInjectorService
import com.example.mycar.update.ApkInstaller
import com.example.mycar.update.AvailableUpdate
import com.example.mycar.update.UpdateCheckOutcome
import com.example.mycar.update.UpdateManager

/**
 * Phone-side companion screen. This is where Android requires the user to approve screen
 * capture before anything can be mirrored into the car, and where updates are started.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var updateButton: Button
    private lateinit var updateStatusView: TextView
    private lateinit var keepScreenOnView: CheckBox
    private lateinit var lockLandscapeView: CheckBox
    private lateinit var touchControlView: CheckBox
    private lateinit var quickLaunchView: CheckBox
    private lateinit var manageLauncherAppsButton: Button
    private lateinit var autoStartView: CheckBox
    private lateinit var startAppButton: Button
    private lateinit var accessibilityButton: Button

    private lateinit var updates: UpdateManager

    /** Set when this activity was opened by [CarConnectionWatcher] to start mirroring by itself. */
    private var launchedForAutoStart = false

    /** Ensures the "Display over other apps" screen is only sent to once per activity. */
    private var overlayPrompted = false

    /** Ensures the "Modify system settings" screen (needed by the rotate button) is sent to once. */
    private var writeSettingsPrompted = false

    private val requestProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenMirrorService.start(this, result.resultCode, data)
                showRunning()
                // Launched from the watcher: get out of the way once capture is running.
                if (launchedForAutoStart) finish()
            } else {
                updateButtons()
                statusView.setText(R.string.status_declined)
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* cosmetic only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)
        updateButton = findViewById(R.id.check_updates)
        updateStatusView = findViewById(R.id.update_status)
        keepScreenOnView = findViewById(R.id.keep_screen_on)

        keepScreenOnView.setOnCheckedChangeListener { _, checked ->
            Settings.setKeepScreenOn(this, checked)
            applyKeepScreenOn()
        }
        keepScreenOnView.isChecked = Settings.keepScreenOn(this)
        applyKeepScreenOn()

        lockLandscapeView = findViewById(R.id.lock_landscape)
        lockLandscapeView.setOnCheckedChangeListener { _, checked ->
            Settings.setLockLandscape(this, checked)
            if (checked) {
                // Forcing the device rotation needs the "Modify system settings" special access.
                if (!RotationLock.isAllowed(this)) {
                    startActivity(RotationLock.permissionIntent(this))
                }
            } else {
                RotationLock.unlock(this)
            }
        }
        lockLandscapeView.isChecked = Settings.lockLandscape(this)

        // Scaling is read by the renderer every frame, so a change here applies immediately.
        val scaleGroup = findViewById<RadioGroup>(R.id.scale_group)
        scaleGroup.check(
            when (Settings.scaleMode(this)) {
                ScaleMode.FIT -> R.id.scale_fit
                ScaleMode.FILL_HEIGHT -> R.id.scale_fill_height
                ScaleMode.FILL_WIDTH -> R.id.scale_fill_width
                ScaleMode.FILL -> R.id.scale_fill
            }
        )
        scaleGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.scale_fit -> ScaleMode.FIT
                R.id.scale_fill_height -> ScaleMode.FILL_HEIGHT
                R.id.scale_fill_width -> ScaleMode.FILL_WIDTH
                else -> ScaleMode.FILL
            }
            Settings.setScaleMode(this, mode)
        }

        accessibilityButton = findViewById(R.id.accessibility_settings)
        accessibilityButton.setOnClickListener { openAccessibilitySettings() }

        touchControlView = findViewById(R.id.touch_control)
        touchControlView.setOnCheckedChangeListener { _, checked ->
            Settings.setTouchControl(this, checked)
            // Forwarding taps needs the accessibility service switched on, which only the
            // system Settings screen can do.
            if (checked && !TouchInjectorService.isConnected) openAccessibilitySettings()
        }
        touchControlView.isChecked = Settings.touchControl(this)

        // Car-screen quick launch panel. Always shown on the right of the mirror; tapping an app
        // opens it on the phone. That launch is a background activity start, so it needs
        // "Display over other apps" (or the MyCar accessibility service).
        quickLaunchView = findViewById(R.id.quick_launch)
        quickLaunchView.isChecked = Settings.quickLaunch(this)
        quickLaunchView.setOnCheckedChangeListener { _, checked ->
            Settings.setQuickLaunch(this, checked)
            if (!checked) return@setOnCheckedChangeListener
            // Opening apps needs "Display over other apps"; the rotate button needs "Modify system
            // settings". Ask for them one at a time, overlay first.
            if (!android.provider.Settings.canDrawOverlays(this)) {
                requestOverlayPermissionIfNeeded()
            } else if (!RotationLock.isAllowed(this)) {
                startActivity(RotationLock.permissionIntent(this))
            }
        }

        manageLauncherAppsButton = findViewById(R.id.manage_launcher_apps)
        manageLauncherAppsButton.setOnClickListener { showLauncherAppsPicker() }
        updateLauncherAppsUi()

        autoStartView = findViewById(R.id.auto_start)
        // Set the stored value before attaching the listener so restoring it does not fire it.
        autoStartView.isChecked = Settings.autoStart(this)
        autoStartView.setOnCheckedChangeListener { _, checked ->
            Settings.setAutoStart(this, checked)
            overlayPrompted = false
            syncAutoStart()
        }

        startAppButton = findViewById(R.id.start_app)
        startAppButton.setOnClickListener { showStartAppPicker() }
        updateStartAppUi()

        updates = UpdateManager(this)

        startButton.setOnClickListener { requestProjection() }
        stopButton.setOnClickListener {
            ScreenMirrorService.stop(this)
            showIdle()
        }
        updateButton.setOnClickListener { checkForUpdates() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // The service can be killed by the system or by the user revoking capture, so always
        // reflect its real state rather than assuming.
        if (ScreenMirrorService.isRunning) showRunning() else showIdle()
        applyKeepScreenOn()
        // The user may have just granted "Modify system settings" for the rotation lock.
        if (lockLandscapeView.isChecked && ScreenMirrorService.isRunning &&
            RotationLock.isAllowed(this)
        ) {
            RotationLock.lockLandscape(this)
        }
        updateTouchControlUi()
        syncAutoStart()
        maybeAutoStart()
        // The quick launch panel needs the same background-launch permission to open apps, so ask
        // for it even when auto-start is off (once per activity, like syncAutoStart does).
        if (quickLaunchView.isChecked && !android.provider.Settings.canDrawOverlays(this) &&
            !overlayPrompted
        ) {
            overlayPrompted = true
            requestOverlayPermissionIfNeeded()
        }
        // The launcher's rotate button needs "Modify system settings"; ask for it once the overlay
        // prompt is out of the way, so the two screens never overlap.
        if (quickLaunchView.isChecked && android.provider.Settings.canDrawOverlays(this) &&
            !RotationLock.isAllowed(this) && !writeSettingsPrompted
        ) {
            writeSettingsPrompted = true
            startActivity(RotationLock.permissionIntent(this))
        }
    }

    /**
     * Runs the watcher only while the setting is on and the background-launch permission is held.
     * That permission is what makes the whole feature work, so asking for it is the first step.
     */
    private fun syncAutoStart() {
        if (!autoStartView.isChecked) {
            CarConnectionWatcher.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            CarConnectionWatcher.stop(this)
            if (!overlayPrompted) {
                overlayPrompted = true
                requestOverlayPermissionIfNeeded()
            }
            return
        }
        CarConnectionWatcher.start(this)
    }

    /**
     * When Android Auto brought us up, ask for capture immediately. Done once per activity so a
     * declined prompt never loops.
     */
    private fun maybeAutoStart() {
        if (launchedForAutoStart) return
        if (!intent.getBooleanExtra(EXTRA_AUTO_START, false)) return
        launchedForAutoStart = true
        if (ScreenMirrorService.isRunning) {
            finish()
        } else {
            requestProjection()
        }
    }

    /**
     * Asks for capture. Entire-screen is preselected on Android 14+, so the only thing left is the
     * confirm tap (which the accessibility service can also make, for a fully hands-free start).
     */
    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        requestProjection.launch(captureIntent)
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Shows what app will be opened once mirroring starts, or the prompt when none is set. */
    private fun updateStartAppUi() {
        val label = Settings.startAppLabel(this)
        if (label.isBlank()) {
            startAppButton.setText(R.string.start_app)
        } else {
            startAppButton.text = getString(R.string.start_app_selected, label)
        }
    }

    private fun showStartAppPicker() {
        val apps = launchableApps()
        val noneLabel = getString(R.string.start_app_none)
        val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val search = view.findViewById<EditText>(R.id.search)
        val list = view.findViewById<ListView>(R.id.list)

        val shown = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)
        list.adapter = adapter

        var filter = ""
        fun matches(label: String) = filter.isEmpty() || label.lowercase().contains(filter)

        fun refresh() {
            shown.clear()
            shown.add(noneLabel)
            apps.filter { matches(it.second) }.forEach { shown.add(it.second) }
            adapter.notifyDataSetChanged()
        }
        refresh()

        search.doAfterTextChanged { editable ->
            filter = editable?.toString()?.trim()?.lowercase().orEmpty()
            refresh()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.start_app)
            .setView(view)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                Settings.setStartApp(this, "", "")
            } else {
                apps.filter { matches(it.second) }.getOrNull(position - 1)
                    ?.let { Settings.setStartApp(this, it.first, it.second) }
            }
            updateStartAppUi()
            dialog.dismiss()
        }

        dialog.show()
    }

    /** Shows how many launcher shortcuts are configured, or the prompt when none are chosen. */
    private fun updateLauncherAppsUi() {
        val count = currentLauncherApps().size
        manageLauncherAppsButton.text = if (count == 0) {
            getString(R.string.manage_launcher_apps)
        } else {
            getString(R.string.manage_launcher_apps_count, count)
        }
    }

    private fun currentLauncherApps(): List<String> =
        Settings.launcherApps(this) ?: Settings.DEFAULT_LAUNCHER_APPS

    /** Multi-select picker for the apps the car launcher shows; tapping a row toggles it. */
    private fun showLauncherAppsPicker() {
        val apps = launchableApps()
        val selected = LinkedHashSet(currentLauncherApps())

        val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val search = view.findViewById<EditText>(R.id.search)
        val list = view.findViewById<ListView>(R.id.list)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        val shown = mutableListOf<Pair<String, String>>()
        var filter = ""
        fun matches(label: String) = filter.isEmpty() || label.lowercase().contains(filter)

        fun refresh() {
            shown.clear()
            shown.addAll(apps.filter { matches(it.second) })
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_multiple_choice,
                shown.map { it.second },
            )
            shown.forEachIndexed { index, app ->
                list.setItemChecked(index, selected.contains(app.first))
            }
        }
        refresh()

        search.doAfterTextChanged { editable ->
            filter = editable?.toString()?.trim()?.lowercase().orEmpty()
            refresh()
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val pkg = shown.getOrNull(position)?.first ?: return@setOnItemClickListener
            // A checked row was already selected, so this tap removes it; otherwise it adds it.
            if (!selected.remove(pkg)) selected.add(pkg)
            list.setItemChecked(position, selected.contains(pkg))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.manage_launcher_apps)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.setLauncherApps(this, selected.toList())
                updateLauncherAppsUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Installed apps with a launcher entry, deduplicated by package and named as shown. */
    private fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved.asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .toList()
    }

    private fun updateTouchControlUi() {
        accessibilityButton.setText(
            if (TouchInjectorService.isConnected) {
                R.string.touch_control_enabled
            } else {
                R.string.open_accessibility_settings
            }
        )
    }

    /** Keeps the screen on while this UI is visible, when the user asked for it. */
    private fun applyKeepScreenOn() {
        if (keepScreenOnView.isChecked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun checkForUpdates() {
        updateButton.isEnabled = false
        updateStatusView.setText(R.string.update_checking)
        updates.check(force = true) { outcome, update ->
            when (outcome) {
                UpdateCheckOutcome.UPDATE_AVAILABLE -> {
                    if (update != null) download(update) else updateButton.isEnabled = true
                }
                UpdateCheckOutcome.UP_TO_DATE -> {
                    updateStatusView.text = getString(R.string.update_up_to_date, installedVersionName())
                    updateButton.isEnabled = true
                }
                UpdateCheckOutcome.FAILED -> {
                    updateStatusView.setText(R.string.update_unreachable)
                    updateButton.isEnabled = true
                }
            }
        }
    }

    private fun download(update: AvailableUpdate) {
        updateStatusView.text = getString(R.string.update_downloading, update.versionName, 0)
        updates.download(
            update = update,
            onProgress = { percent ->
                updateStatusView.text = getString(R.string.update_downloading, update.versionName, percent)
            },
            onResult = { result ->
                updateButton.isEnabled = true
                result.fold(
                    onSuccess = { file ->
                        updateStatusView.text = getString(R.string.update_ready, update.versionName)
                        // Stop offering this build once it has been handed to the installer.
                        updates.dismiss(update)
                        if (ApkInstaller.canInstall(this)) {
                            ApkInstaller.install(this, file)
                        } else {
                            ApkInstaller.requestInstallPermission(this)
                        }
                    },
                    onFailure = { error ->
                        updateStatusView.text =
                            getString(R.string.update_failed, error.message.orEmpty())
                    },
                )
            },
        )
    }

    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun showRunning() {
        statusView.setText(R.string.status_running)
        updateButtons()
    }

    private fun showIdle() {
        statusView.setText(R.string.status_idle)
        updateButtons()
    }

    private fun updateButtons() {
        val running = ScreenMirrorService.isRunning
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }

    companion object {
        /** Extra set by [CarConnectionWatcher] to request capture as soon as this screen opens. */
        const val EXTRA_AUTO_START = "autoStart"
    }
}
