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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.widget.doAfterTextChanged
import com.example.mycar.auto.CarConnectionWatcher
import com.example.mycar.audio.AnnouncementPauser
import com.example.mycar.capture.ScreenMirrorService
import com.example.mycar.car.CarCategory
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
    private lateinit var categoryGroup: RadioGroup
    private lateinit var themeGroup: RadioGroup
    private lateinit var autoStartView: CheckBox
    private lateinit var startAppButton: Button
    private lateinit var closeAppView: CheckBox
    private lateinit var closeAppButton: Button
    private lateinit var closeAppHardView: CheckBox
    private lateinit var pauseAnnouncementsView: CheckBox
    private lateinit var notificationAccessButton: Button
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
        manageLauncherAppsButton.setOnClickListener { showLauncherEditor() }
        updateLauncherAppsUi()

        // Which Android Auto category to register under. Backed by two service components; only
        // the chosen one is enabled, and Android Auto picks it up when it next reconnects.
        categoryGroup = findViewById(R.id.category_group)
        categoryGroup.check(
            when (Settings.carCategory(this)) {
                CarCategory.NAVIGATION -> R.id.category_navigation
                CarCategory.WEATHER -> R.id.category_weather
            }
        )
        categoryGroup.setOnCheckedChangeListener { _, checkedId ->
            val category = if (checkedId == R.id.category_weather) {
                CarCategory.WEATHER
            } else {
                CarCategory.NAVIGATION
            }
            Settings.setCarCategory(this, category)
            CarCategory.register(this, category)
        }

        // Appearance: light, dark, or follow the system. Changing it recreates this activity.
        themeGroup = findViewById(R.id.theme_group)
        themeGroup.check(
            when (Settings.themeMode(this)) {
                ThemeMode.SYSTEM -> R.id.theme_system
                ThemeMode.LIGHT -> R.id.theme_light
                ThemeMode.DARK -> R.id.theme_dark
            }
        )
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_light -> ThemeMode.LIGHT
                R.id.theme_dark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            Settings.setThemeMode(this, mode)
            AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
        }

        autoStartView = findViewById(R.id.auto_start)
        // Set the stored value before attaching the listener so restoring it does not fire it.
        autoStartView.isChecked = Settings.autoStart(this)
        autoStartView.setOnCheckedChangeListener { _, checked ->
            Settings.setAutoStart(this, checked)
            overlayPrompted = false
            syncWatcher()
        }

        startAppButton = findViewById(R.id.start_app)
        startAppButton.setOnClickListener { showStartAppsEditor() }
        updateStartAppUi()

        // Close-on-disconnect. The app is closed by CarConnectionWatcher when Android Auto drops;
        // leaving a foreground app or force stopping it needs the accessibility service.
        closeAppView = findViewById(R.id.close_app)
        closeAppView.isChecked = Settings.closeAppOnDisconnect(this)
        closeAppView.setOnCheckedChangeListener { _, checked ->
            Settings.setCloseAppOnDisconnect(this, checked)
            if (!checked) Settings.setCloseAppHard(this, false)
            updateCloseAppUi()
            syncWatcher()
        }

        closeAppButton = findViewById(R.id.close_app_picker)
        closeAppButton.setOnClickListener { showCloseAppPicker() }

        closeAppHardView = findViewById(R.id.close_app_hard)
        closeAppHardView.isChecked = Settings.closeAppHard(this)
        closeAppHardView.setOnCheckedChangeListener { _, checked ->
            Settings.setCloseAppHard(this, checked)
            // Force stop is driven by the accessibility service; open its settings when it is off.
            if (checked && !TouchInjectorService.isConnected) openAccessibilitySettings()
        }
        updateCloseAppUi()

        // Pause media while a navigation/alert app speaks. Controlling another app's media needs
        // Notification access, so ask for it when the feature is switched on.
        pauseAnnouncementsView = findViewById(R.id.pause_announcements)
        pauseAnnouncementsView.isChecked = Settings.pauseForAnnouncements(this)
        pauseAnnouncementsView.setOnCheckedChangeListener { _, checked ->
            Settings.setPauseForAnnouncements(this, checked)
            if (checked) {
                AnnouncementPauser.start(this)
                if (!notificationAccessGranted()) openNotificationAccessSettings()
            } else {
                AnnouncementPauser.stop()
            }
            updateNotificationAccessUi()
        }

        notificationAccessButton = findViewById(R.id.notification_access)
        notificationAccessButton.setOnClickListener { openNotificationAccessSettings() }

        updateNotificationAccessUi()

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
        updateNotificationAccessUi()
        if (pauseAnnouncementsView.isChecked) AnnouncementPauser.start(this)
        syncWatcher()
        maybeAutoStart()
        // The quick launch panel needs the same background-launch permission to open apps, so ask
        // for it even when auto-start is off (once per activity, like syncWatcher does).
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
        // Keep the manifest's enabled category in sync with the stored choice (an app update
        // resets the components to the manifest defaults, i.e. weather).
        CarCategory.register(this, Settings.carCategory(this))
    }

    /**
     * Runs the watcher while either hands-free feature wants it: auto-start mirroring or closing
     * an app on disconnect. Auto-start alone needs "Display over other apps" for its background
     * activity start, so without it the watcher is only kept for close-on-disconnect.
     */
    private fun syncWatcher() {
        val autoStart = autoStartView.isChecked
        val close = closeAppView.isChecked
        if (autoStart && !android.provider.Settings.canDrawOverlays(this) && !overlayPrompted) {
            overlayPrompted = true
            requestOverlayPermissionIfNeeded()
        }
        if (autoStart && android.provider.Settings.canDrawOverlays(this) || close) {
            CarConnectionWatcher.start(this)
        } else {
            CarConnectionWatcher.stop(this)
        }
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

    /** Whether MyCar is currently an enabled notification listener. */
    private fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotificationAccessSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Offers the "Grant notification access" button until the access is actually granted. */
    private fun updateNotificationAccessUi() {
        notificationAccessButton.visibility =
            if (notificationAccessGranted()) View.GONE else View.VISIBLE
    }

    /** Shows how many apps will open once mirroring starts, or the prompt when none is set. */
    private fun updateStartAppUi() {
        val count = Settings.startAppPackages(this).size
        startAppButton.text = if (count == 0) {
            getString(R.string.start_app)
        } else {
            getString(R.string.start_apps_count, count)
        }
    }

    /**
     * Shows the close-on-disconnect controls: the app picker and the force-stop option only make
     * sense while the feature is on, and force stop only once an app is chosen.
     */
    private fun updateCloseAppUi() {
        val enabled = closeAppView.isChecked
        closeAppButton.visibility = if (enabled) View.VISIBLE else View.GONE
        closeAppHardView.visibility =
            if (enabled && Settings.closeAppPackage(this).isNotBlank()) View.VISIBLE else View.GONE
        val label = Settings.closeAppLabel(this)
        if (label.isBlank()) {
            closeAppButton.setText(R.string.close_app_pick)
        } else {
            closeAppButton.text = getString(R.string.close_app_selected, label)
        }
    }

    private fun showCloseAppPicker() =
        showAppPicker(R.string.close_app_pick_title, R.string.close_app_none) { packageName, label ->
            Settings.setCloseApp(this, packageName, label)
            updateCloseAppUi()
        }

    /**
     * Single-select app picker: the first row clears the choice, the rest launch [onPicked] with
     * the chosen package and label.
     */
    private fun showAppPicker(titleRes: Int, noneRes: Int, onPicked: (String, String) -> Unit) {
        val apps = launchableApps()
        val noneLabel = getString(noneRes)
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
            .setTitle(titleRes)
            .setView(view)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                onPicked("", "")
            } else {
                apps.filter { matches(it.second) }.getOrNull(position - 1)
                    ?.let { onPicked(it.first, it.second) }
            }
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

    /** Editor for the car launcher's shortcuts. */
    private fun showLauncherEditor() {
        val entries = currentLauncherEntries().toMutableList()
        showAppListEditor(R.string.manage_launcher_apps, entries) { saveLauncherEntries(entries) }
    }

    /** Editor for the apps opened when mirroring starts; the last one ends up on screen. */
    private fun showStartAppsEditor() {
        val entries = startAppEntries().toMutableList()
        showAppListEditor(R.string.start_app, entries) { saveStartAppEntries(entries) }
    }

    /**
     * Editor shared by the launcher and the start-apps list: an ordered app list with up/down,
     * remove, and add. [onChanged] persists the list after every change.
     */
    private fun showAppListEditor(
        titleRes: Int,
        entries: MutableList<Pair<String, String>>,
        onChanged: () -> Unit,
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_launcher, null)
        val list = view.findViewById<ListView>(R.id.launcher_list)
        val empty = view.findViewById<TextView>(R.id.empty)

        // The adapter saves through onChanged after every change; it also drives the empty state.
        fun refreshEmpty() {
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
        val adapter = AppListEditorAdapter(entries) {
            onChanged()
            refreshEmpty()
        }
        list.adapter = adapter
        refreshEmpty()

        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        view.findViewById<Button>(R.id.add_app).setOnClickListener {
            showAddApp(entries.map { it.first }.toSet()) { packageName, label ->
                entries.add(packageName to label)
                adapter.notifyDataSetChanged()
                onChanged()
                refreshEmpty()
            }
        }

        dialog.show()
    }

    /** The launcher apps in order, resolved to (package, label); uninstalled ones are dropped. */
    private fun currentLauncherEntries(): List<Pair<String, String>> {
        val labels = launchableApps().toMap()
        return currentLauncherApps().mapNotNull { pkg -> labels[pkg]?.let { pkg to it } }
    }

    /** The start apps in order, resolved to (package, label); uninstalled ones are dropped. */
    private fun startAppEntries(): List<Pair<String, String>> {
        val labels = launchableApps().toMap()
        return Settings.startAppPackages(this).mapNotNull { pkg -> labels[pkg]?.let { pkg to it } }
    }

    private fun saveLauncherEntries(entries: List<Pair<String, String>>) {
        Settings.setLauncherApps(this, entries.map { it.first })
        updateLauncherAppsUi()
    }

    private fun saveStartAppEntries(entries: List<Pair<String, String>>) {
        Settings.setStartAppPackages(this, entries.map { it.first })
        updateStartAppUi()
    }

    /** Single-select picker for an app to append to a list, hiding ones already in [existing]. */
    private fun showAddApp(existing: Set<String>, onPicked: (String, String) -> Unit) {
        val apps = launchableApps().filter { it.first !in existing }
        val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val search = view.findViewById<EditText>(R.id.search)
        val list = view.findViewById<ListView>(R.id.list)

        val shown = mutableListOf<Pair<String, String>>()
        var filter = ""
        fun matches(label: String) = filter.isEmpty() || label.lowercase().contains(filter)

        fun refresh() {
            shown.clear()
            shown.addAll(apps.filter { matches(it.second) })
            list.adapter =
                ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.second })
        }
        refresh()

        search.doAfterTextChanged { editable ->
            filter = editable?.toString()?.trim()?.lowercase().orEmpty()
            refresh()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_launcher_app)
            .setView(view)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { onPicked(it.first, it.second) }
            dialog.dismiss()
        }
        dialog.show()
    }

    /** Binds the editor's entries to rows with up/down/remove, persisting after every change. */
    private inner class AppListEditorAdapter(
        private val entries: MutableList<Pair<String, String>>,
        private val onChanged: () -> Unit,
    ) : BaseAdapter() {

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: layoutInflater.inflate(R.layout.dialog_launcher_row, parent, false)
            val (packageName, label) = entries[position]

            row.findViewById<TextView>(R.id.label).text = label
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(
                runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull(),
            )

            val up = row.findViewById<ImageButton>(R.id.up)
            up.isEnabled = position > 0
            up.alpha = if (position > 0) 1f else 0.3f
            up.setOnClickListener { move(position, position - 1) }

            val down = row.findViewById<ImageButton>(R.id.down)
            down.isEnabled = position < entries.size - 1
            down.alpha = if (position < entries.size - 1) 1f else 0.3f
            down.setOnClickListener { move(position, position + 1) }

            row.findViewById<ImageButton>(R.id.remove).setOnClickListener {
                entries.removeAt(position)
                notifyDataSetChanged()
                onChanged()
            }
            return row
        }

        private fun move(from: Int, to: Int) {
            if (to < 0 || to >= entries.size) return
            entries.add(to, entries.removeAt(from))
            notifyDataSetChanged()
            onChanged()
        }
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
