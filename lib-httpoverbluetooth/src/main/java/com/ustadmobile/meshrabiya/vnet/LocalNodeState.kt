package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState

data class LocalNodeState(
    val address: Int = 0,
    val wifiState: MeshrabiyaWifiState = MeshrabiyaWifiState(),
    val connectUri: String? = null,
) {
}
