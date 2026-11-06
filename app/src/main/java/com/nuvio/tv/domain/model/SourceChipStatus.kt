package com.nuvio.tv.domain.model

enum class SourceChipStatus {
    LOADING,
    SUCCESS,
    ERROR
}

data class SourceChipItem(
    val name: String,
    val status: SourceChipStatus
)
