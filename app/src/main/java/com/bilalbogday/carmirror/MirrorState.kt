package com.bilalbogday.carmirror

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArraySet

enum class ScaleMode(
    val labelTr: String,
    val shortCarLabel: String,
    val descTr: String
) {
    FILL(
        labelTr = "Tam Ekran Doldur",
        shortCarLabel = "Tam Ekran",
        descTr = "Seçili: Tam Ekran Doldur (Siyah boşluk bırakmadan tüm araç ekranını kaplar)"
    ),
    FIT(
        labelTr = "Orantılı Sığdır",
        shortCarLabel = "Orantılı",
        descTr = "Seçili: Orantılı Sığdır (Görüntü oranını bozmadan araç ekranına sığdırır)"
    ),
    CROP(
        labelTr = "Yakınlaştır (Kırp)",
        shortCarLabel = "Yakınlaştır",
        descTr = "Seçili: Yakınlaştırarak Doldur (Oranı bozmadan ekranı tamamen doldurur)"
    ),
    ROTATE_90(
        labelTr = "90° Yatay Çevir",
        shortCarLabel = "90° Yatay",
        descTr = "Seçili: 90° Yatay Çevir (Dikey kilitli uygulamaları araç ekranında yatay gösterir)"
    );

    fun next(): ScaleMode {
        val vals = entries
        return vals[(ordinal + 1) % vals.size]
    }
}

data class MirrorUiState(
    val isMirroring: Boolean = false,
    val isCarSurfaceConnected: Boolean = false,
    val carWidth: Int = 0,
    val carHeight: Int = 0,
    val scaleMode: ScaleMode = ScaleMode.FILL,
    val autoLandscape: Boolean = true,
    val dimPhoneScreen: Boolean = false,
    val localWebUrl: String? = null
)

object MirrorState {
    private val _state = MutableStateFlow(MirrorUiState())
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    private val listeners = CopyOnWriteArraySet<(MirrorUiState) -> Unit>()

    fun addListener(listener: (MirrorUiState) -> Unit) {
        listeners.add(listener)
        listener(_state.value)
    }

    fun removeListener(listener: (MirrorUiState) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(newState: MirrorUiState) {
        for (listener in listeners) {
            try {
                listener(newState)
            } catch (_: Throwable) {
            }
        }
    }

    fun updateMirroring(active: Boolean, localWebUrl: String? = _state.value.localWebUrl) {
        _state.update { it.copy(isMirroring = active, localWebUrl = if (active) localWebUrl else null) }
        notifyListeners(_state.value)
    }

    fun updateCarSurface(connected: Boolean, width: Int = 0, height: Int = 0) {
        _state.update {
            it.copy(
                isCarSurfaceConnected = connected,
                carWidth = if (connected) width else 0,
                carHeight = if (connected) height else 0
            )
        }
        notifyListeners(_state.value)
    }

    fun setScaleMode(mode: ScaleMode) {
        _state.update { it.copy(scaleMode = mode) }
        notifyListeners(_state.value)
    }

    fun cycleScaleMode(): ScaleMode {
        val nextMode = _state.value.scaleMode.next()
        setScaleMode(nextMode)
        return nextMode
    }

    fun setAutoLandscape(enabled: Boolean) {
        _state.update { it.copy(autoLandscape = enabled) }
        notifyListeners(_state.value)
    }

    fun setDimPhoneScreen(enabled: Boolean) {
        _state.update { it.copy(dimPhoneScreen = enabled) }
        notifyListeners(_state.value)
    }

    fun toggleDimPhoneScreen(): Boolean {
        val next = !_state.value.dimPhoneScreen
        setDimPhoneScreen(next)
        return next
    }
}
