package com.miku.mcp.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus
    data class Running(val port: Int) : ServerStatus
    data class Error(val message: String) : ServerStatus
}

// Tunnel state is separate: a tunnel failure must not take the local server down
sealed interface TunnelStatus {
    data object Off : TunnelStatus
    data object Connecting : TunnelStatus
    data class Connected(val url: String) : TunnelStatus
    data class Error(val message: String) : TunnelStatus
}

object ServerState {
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _tunnel = MutableStateFlow<TunnelStatus>(TunnelStatus.Off)
    val tunnel: StateFlow<TunnelStatus> = _tunnel.asStateFlow()

    fun set(newStatus: ServerStatus) { _status.value = newStatus }
    fun setTunnel(newStatus: TunnelStatus) { _tunnel.value = newStatus }
}