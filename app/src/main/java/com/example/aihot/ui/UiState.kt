package com.example.aihot.ui

/** 通用 UI 状态密封接口,所有屏幕共用。 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
