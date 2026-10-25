package com.bilalbogday.carmirror

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bilalbogday.carmirror.databinding.ActivityMainBinding
import com.bilalbogday.carmirror.service.MirrorForegroundService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, MirrorForegroundService::class.java).apply {
                action = MirrorForegroundService.ACTION_START
                putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorForegroundService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Toast.makeText(
                this,
                "Ekran yansıtma izni verilmedi.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestMediaProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        setupListeners()
        observeMirrorState()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermissionUi()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("carmirror_prefs", Context.MODE_PRIVATE)
        val savedScale = prefs.getString("scale_mode", ScaleMode.FILL.name) ?: ScaleMode.FILL.name
        val mode = try {
            ScaleMode.valueOf(savedScale)
        } catch (_: Throwable) {
            ScaleMode.FILL
        }
        val autoLandscape = prefs.getBoolean("auto_landscape", true)
        MirrorState.setScaleMode(mode)
        MirrorState.setAutoLandscape(autoLandscape)
    }

    private fun savePreferences(mode: ScaleMode, autoLandscape: Boolean) {
        getSharedPreferences("carmirror_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("scale_mode", mode.name)
            .putBoolean("auto_landscape", autoLandscape)
            .apply()
    }

    private fun setupListeners() {
        binding.btnToggleMirror.setOnClickListener {
            if (MirrorState.state.value.isMirroring) {
                val stopIntent = Intent(this, MirrorForegroundService::class.java).apply {
                    action = MirrorForegroundService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                startMirrorFlow()
            }
        }

        binding.btnScaleFill.setOnClickListener { selectScaleMode(ScaleMode.FILL) }
        binding.btnScaleFit.setOnClickListener { selectScaleMode(ScaleMode.FIT) }
        binding.btnScaleCrop.setOnClickListener { selectScaleMode(ScaleMode.CROP) }
        binding.btnScaleRotate.setOnClickListener { selectScaleMode(ScaleMode.ROTATE_90) }

        binding.switchAutoLandscape.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Otomatik yatay mod için 'Üstte Gösterme' iznini etkinleştirin.",
                    Toast.LENGTH_LONG
                ).show()
                openOverlayPermissionSettings()
            }
            MirrorState.setAutoLandscape(isChecked)
            savePreferences(MirrorState.state.value.scaleMode, isChecked)
        }

        binding.switchDimScreen.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                binding.switchDimScreen.isChecked = false
                Toast.makeText(
                    this,
                    "Ekran karartma modu için önce 'Üstte Gösterme' izni verin.",
                    Toast.LENGTH_LONG
                ).show()
                openOverlayPermissionSettings()
                return@setOnCheckedChangeListener
            }
            MirrorState.setDimPhoneScreen(isChecked)
        }

        binding.btnOverlayPermission.setOnClickListener {
            openOverlayPermissionSettings()
        }

        binding.btnOpenAaSettings.setOnClickListener {
            openAndroidAutoSettings()
        }
    }

    private fun selectScaleMode(mode: ScaleMode) {
        MirrorState.setScaleMode(mode)
        savePreferences(mode, MirrorState.state.value.autoLandscape)
    }

    private fun startMirrorFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun observeMirrorState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorState.state.collect { state ->
                    renderUi(state)
                }
            }
        }
    }

    private fun renderUi(state: MirrorUiState) {
        val colorGreen = ContextCompat.getColor(this, R.color.accent_green)
        val colorAmber = ContextCompat.getColor(this, R.color.accent_amber)
        val colorRed = ContextCompat.getColor(this, R.color.accent_red)
        val colorBlue = ContextCompat.getColor(this, R.color.accent_blue)
        val colorChip = ContextCompat.getColor(this, R.color.chip_bg)

        if (state.isMirroring) {
            binding.tvMirrorStatus.text = "● Yansıtma Durumu: AKTİF (Sıfır Gecikmeli Akış)"
            binding.tvMirrorStatus.setTextColor(colorGreen)
            binding.btnToggleMirror.text = "YANSITMAYI DURDUR"
            binding.btnToggleMirror.backgroundTintList = ColorStateList.valueOf(colorRed)
        } else {
            binding.tvMirrorStatus.text = "● Yansıtma Durumu: Hazır (Beklemede)"
            binding.tvMirrorStatus.setTextColor(colorAmber)
            binding.btnToggleMirror.text = "EKRAN YANSITMAYI BAŞLAT"
            binding.btnToggleMirror.backgroundTintList = ColorStateList.valueOf(colorBlue)
        }

        if (state.isCarSurfaceConnected) {
            binding.tvCarSurfaceStatus.text =
                "🚗 Araç Ekranı (Android Auto): BAĞLI (${state.carWidth}x${state.carHeight})"
            binding.tvCarSurfaceStatus.setTextColor(colorGreen)
        } else {
            binding.tvCarSurfaceStatus.text =
                "🚗 Araç Ekranı (Android Auto): Araç ekranından CarMirror Auto'yu açın"
            binding.tvCarSurfaceStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        binding.tvCurrentScaleDesc.text = state.scaleMode.descTr

        binding.btnScaleFill.backgroundTintList = ColorStateList.valueOf(
            if (state.scaleMode == ScaleMode.FILL) colorBlue else colorChip
        )
        binding.btnScaleFit.backgroundTintList = ColorStateList.valueOf(
            if (state.scaleMode == ScaleMode.FIT) colorBlue else colorChip
        )
        binding.btnScaleCrop.backgroundTintList = ColorStateList.valueOf(
            if (state.scaleMode == ScaleMode.CROP) colorBlue else colorChip
        )
        binding.btnScaleRotate.backgroundTintList = ColorStateList.valueOf(
            if (state.scaleMode == ScaleMode.ROTATE_90) colorBlue else colorChip
        )

        if (binding.switchAutoLandscape.isChecked != state.autoLandscape) {
            binding.switchAutoLandscape.isChecked = state.autoLandscape
        }
        if (binding.switchDimScreen.isChecked != state.dimPhoneScreen) {
            binding.switchDimScreen.isChecked = state.dimPhoneScreen
        }

        if (state.localWebUrl != null) {
            binding.tvLocalWebFallback.text =
                "🌐 Yerel Wi-Fi Tarayıcı Adresi (İnternetsiz): ${state.localWebUrl}"
        } else {
            binding.tvLocalWebFallback.text =
                "🌐 Ek Wi-Fi Tarayıcı Yedeği: Yansıtma başladığında yerel adres burada görünür"
        }
    }

    private fun updateOverlayPermissionUi() {
        val granted = Settings.canDrawOverlays(this)
        binding.btnOverlayPermission.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(
                this,
                "Ayarlar -> Uygulamalar -> CarMirror Auto -> Üstte göster iznini açın",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openAndroidAutoSettings() {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
                )
            },
            Intent("com.google.android.projection.gearhead.SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:com.google.android.projection.gearhead")
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        for (candidate in intents) {
            try {
                startActivity(candidate)
                return
            } catch (_: Throwable) {
            }
        }
    }
}
