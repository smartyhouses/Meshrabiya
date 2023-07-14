package com.ustadmobile.meshrabiya.vnet

import app.cash.turbine.test
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotRequest
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.test.EchoDatagramServer
import com.ustadmobile.meshrabiya.test.assertByteArrayEquals
import com.ustadmobile.meshrabiya.test.connectTo
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verifyBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VirtualNodeTest {

    class TestVirtualNode(
        uuidMask: UUID = UUID.randomUUID(),
        localNodeAddress: Int = randomApipaAddr(),
        port: Int = 0,
        logger: MNetLogger,
        override val hotspotManager: MeshrabiyaWifiManager = mock { },
        json: Json,
        config: NodeConfig = NodeConfig(maxHops = 5),
    ) : VirtualNode(
        uuidMask = uuidMask,
        port = port,
        logger = logger,
        json = json,
        config = config,
        localNodeAddress = localNodeAddress,
    )

    private val logger = MNetLogger { priority, message, exception ->
        println(buildString {
            append(message)
            if(exception != null) {
                append(" ")
                append(exception.stackTraceToString())
            }
        })
    }

    private val json = Json {
        encodeDefaults = true
    }

    @Test
    fun givenTwoVirtualNodesConnectedOverDatagramSocket_whenPingSent_thenReplyWillBeReceived() {
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )
        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        try {
            node1.connectTo(node2, timeout = 10000)
            println("Connected node1 -> node2")

            val latch = CountDownLatch(1)
            val pongMessage = AtomicReference<MmcpPong>()
            val node1ToNode2Ping = MmcpPing(Random.nextInt())

            node1.addPongListener(object: PongListener{
                override fun onPongReceived(fromNode: Int, pong: MmcpPong) {
                    if(pong.replyToMessageId == node1ToNode2Ping.messageId) {
                        pongMessage.set(pong)
                        latch.countDown()
                    }
                }
            })

            node1.route(
                node1ToNode2Ping.toVirtualPacket(
                    toAddr = node2.localNodeAddress,
                    fromAddr = node1.localNodeAddress
                )
            )

            latch.await(5000, TimeUnit.MILLISECONDS)
            Assert.assertEquals(node1ToNode2Ping.messageId, pongMessage.get().replyToMessageId)
        }finally {
            node1.close()
            node2.close()
        }
    }


    @Test
    fun givenMmcpHotspotRequestReceived_whenPacketRouted_thenWillRequestFromHotspotManagerAndReplyWithConfig() {
        val hotspotState = MutableStateFlow(
            MeshrabiyaWifiState(
                wifiDirectState = WifiDirectState(hotspotStatus = HotspotStatus.STOPPED)
            )
        )
        val mockHotspotManager = mock<MeshrabiyaWifiManager> {
            on { state }.thenReturn(hotspotState)
            onBlocking { requestHotspot(any(), any()) }.thenAnswer {
                val messageId = it.arguments.first() as Int
                LocalHotspotResponse(
                    responseToMessageId = messageId,
                    errorCode = 0,
                    config = WifiConnectConfig(
                        nodeVirtualAddr = randomApipaAddr(),
                        ssid = "networkname",
                        passphrase = "secret123",
                        port = 8042,
                        hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                        linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
                    ),
                    redirectAddr = 0,
                )
            }
        }

        val json = Json { encodeDefaults = true }

        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mockHotspotManager,
            json = json,
        )

        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        try {
            node1.connectTo(node2)

            //Wait for connection to be established
            runBlocking {
                node1.neighborNodesState.filter { it.isNotEmpty() }.test {
                    awaitItem()
                }
            }

            val requestId = Random.nextInt()

            node1.route(
                packet = MmcpHotspotRequest(requestId, LocalHotspotRequest(is5GhzSupported = true))
                    .toVirtualPacket(
                        toAddr = node1.localNodeAddress,
                        fromAddr = node2.localNodeAddress
                    )
            )

            verifyBlocking(mockHotspotManager, timeout(5000)) {
                requestHotspot(eq(requestId), any())
            }

            runBlocking {
                node2.incomingMmcpMessages.filter {
                    it.message.what == MmcpMessage.WHAT_HOTSPOT_RESPONSE
                }.test(timeout = 5.seconds) {
                    val message = awaitItem().message as MmcpHotspotResponse
                    Assert.assertEquals(requestId, message.result.responseToMessageId)
                    Assert.assertEquals("networkname", message.result.config?.ssid)
                    Assert.assertEquals("secret123", message.result.config?.passphrase)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }finally {
            node1.close()
            node2.close()
        }
    }

    @Test(timeout = 10000)
    fun givenConnectedNodes_whenBroadcastIsSent_thenAllWillReceive() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val nodesToClose = mutableListOf<VirtualNode>()

        try {
            val middleNode = TestVirtualNode(
                logger = logger,
                json = json,
            )
            nodesToClose += middleNode

            val connectedNodes = (0 until 3).map {
                TestVirtualNode(
                    logger = logger,
                    json = json
                ).also {
                    nodesToClose += it
                    it.connectTo(middleNode)
                }
            }

            val pingMessageId = 1000042
            val broadcastPing = MmcpPing(pingMessageId).toVirtualPacket(
                toAddr = ADDR_BROADCAST,
                fromAddr = connectedNodes.first().localNodeAddress
            )

            val otherJobs = (1 until 3).map {nodeIndex ->
                scope.async {
                    connectedNodes[nodeIndex].incomingMmcpMessages.filter {
                        it.message.messageId == pingMessageId
                    }.first()
                }
            }

            val firstNode = connectedNodes.first()

            firstNode.route(broadcastPing)

            runBlocking { awaitAll(*otherJobs.toTypedArray()) }
        }finally {
            scope.cancel()
            nodesToClose.forEach { it.close() }
        }
    }

    /**
     * Integration test to ensure that when there are two connected virtual node neighbors they will
     * ping each other and update their state.
     */
    @Test
    fun givenTwoNodes_whenConnected_thenPingTimesWillBeDetermined() {
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 1).ip4AddressToInt()
        )
        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 2).ip4AddressToInt()
        )
        println("Test node1=${node1.localNodeAddress.addressToDotNotation()} node2=${node2.localNodeAddress.addressToDotNotation()}")

        fun VirtualNode.assertPingTimeDetermined(
            otherNode: VirtualNode,
            name: String,
        ) {
            runBlocking {
                neighborNodesState.filter { neighbors ->
                    (neighbors.firstOrNull { it.remoteAddress == otherNode.localNodeAddress }?.pingTime ?: 0) > 0
                }.test(timeout = 10000.milliseconds, name = name) {
                    val pingTime = awaitItem().first { it.remoteAddress == otherNode.localNodeAddress }.pingTime
                    Assert.assertTrue(
                        "${localNodeAddress.addressToDotNotation()} -> " +
                            "${otherNode.localNodeAddress.addressToDotNotation()} ping time > 0",
                        pingTime > 0
                    )
                    println("Determined ping time from ${localNodeAddress.addressToDotNotation()} " +
                            "-> ${otherNode.localNodeAddress.addressToDotNotation()} = ${pingTime}ms")
                }
            }
        }

        try {
            node1.connectTo(node2)
            node1.assertPingTimeDetermined(node2, name = "Found ping time from node1 to node2")
            node2.assertPingTimeDetermined(node1, name = "Found ping time from node2 to node1")
        }finally {
            node1.close()
            node2.close()
        }
    }

    @Test
    fun givenThreeNodes_whenConnected_thenShouldReceiveOriginatingMessagesFromOthers() {
        val nodes = (0 until 3).map {
            TestVirtualNode(
                uuidMask = UUID.randomUUID(),
                logger = logger,
                hotspotManager = mock { },
                json = json,
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (it +1).toByte()).ip4AddressToInt()
            )
        }

        try {
            nodes[0].connectTo(nodes[1])
            nodes[1].connectTo(nodes[2])

            nodes.forEach { node ->
                runBlocking {
                    val otherNodeAddresses = nodes
                        .filter { it.localNodeAddress != node.localNodeAddress }
                        .map { it.localNodeAddress }

                    node.state.filter { nodeState ->
                        otherNodeAddresses.all { otherNodeAddr ->
                            nodeState.originatorMessages.containsKey(otherNodeAddr)
                        }
                    }.test(timeout = 5.seconds) {
                        val item = awaitItem()
                        Assert.assertNotNull(item)
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }finally {
            nodes.forEach { it.close() }
        }
    }

    @Test(timeout = 5000)
    fun givenThreeNodes_whenConnected_thenCanPingFromOneToOtherViaHop() {
        val  scope = CoroutineScope(Dispatchers.Default + Job())
        val nodes = (0 until 3).map {
            TestVirtualNode(
                uuidMask = UUID.randomUUID(),
                logger = logger,
                hotspotManager = mock { },
                json = json,
                localNodeAddress = byteArrayOf(
                    169.toByte(),
                    254.toByte(),
                    1,
                    (it + 1).toByte()
                ).ip4AddressToInt()
            )
        }

        try {
            nodes[0].connectTo(nodes[1])
            nodes[1].connectTo(nodes[2])

            runBlocking {
                //Wait for node 0 to discover node 2
                println("test: wait for discovery")
                nodes.first().state
                    .filter {
                        it.originatorMessages.containsKey(nodes.last().localNodeAddress)
                    }
                    .first()
                println("test: node 1 knows about node 3")
                nodes.last().state
                    .filter { it.originatorMessages.containsKey(nodes.first().localNodeAddress) }
                    .first()
                println("test: node 3 knows about node 1 : discovery done")

                val pingId = 1000042//Random.nextInt(0, Int.MAX_VALUE)
                val pongReply = scope.async {
                    nodes[0].incomingMmcpMessages.filter {
                        (it.message as? MmcpPong)?.replyToMessageId == pingId
                    }.first()
                }

                nodes.first().route(MmcpPing(pingId).toVirtualPacket(
                    toAddr = nodes.last().localNodeAddress,
                    fromAddr = nodes.first().localNodeAddress
                ))

                val pong = pongReply.await()
                Assert.assertEquals(pingId, (pong.message as? MmcpPong)?.replyToMessageId)
            }
        }finally {
            nodes.forEach { it.close() }
        }
    }

    @Test(timeout = 5000)
    fun givenTwoNodesConnected_whenPacketSentUsingVirtualSocket_thenShouldBeReceived() {
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (1).toByte()).ip4AddressToInt()
        )

        val node2  = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (2).toByte()).ip4AddressToInt()
        )
        try {
            node1.connectTo(node2)
            val node1socket  = node1.openSocket(81)
            val node2socket = node2.openSocket(82)

            val packetData = "Hello World".encodeToByteArray()
            val txPacket = DatagramPacket(packetData, 0, packetData.size)
            txPacket.address = InetAddress.getByAddress(node2.localNodeAddress.addressToByteArray())
            txPacket.port = 82
            node1socket.send(txPacket)

            val rxBuffer = ByteArray(1500)
            val rxPacket = DatagramPacket(rxBuffer, 0, rxBuffer.size)
            node2socket.receive(rxPacket)
            assertByteArrayEquals(
                packetData, 0, rxBuffer, rxPacket.offset, packetData.size
            )
            Assert.assertEquals(txPacket.length, rxPacket.length)
        }finally {
            node1.close()
            node2.close()
        }

    }


    /**
     * Test forwarding between a real socket on node 1 and a real socket on node 2 over the virtual
     * network.
     */
    @Test(timeout = 5000)
    fun givenTwoNodes_whenForwardingSetup_thenEchoWillBeReceived(){
        val executorService = Executors.newCachedThreadPool()
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (1).toByte()).ip4AddressToInt()
        )

        val node2  = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, (2).toByte()).ip4AddressToInt()
        )

        val node1InetAddr = InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 1, (1).toByte()))
        val node2InetAddr = InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 1, (2).toByte()))
        val node2EchoServer = EchoDatagramServer(0, executorService)

        try {
            node1.connectTo(node2)

            val node1ForwardingListenPort = node1.forward(
                InetAddress.getLoopbackAddress(), 0, node2InetAddr, 8000
            )

            node2.forward(
                node1InetAddr, 8000, InetAddress.getLoopbackAddress(), node2EchoServer.listeningPort
            )

            val client = DatagramSocket()
            val helloBytes = "Hello".toByteArray()
            val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
                InetAddress.getLoopbackAddress(), node1ForwardingListenPort)
            println("Send packet to ${InetAddress.getLoopbackAddress()}:$node1ForwardingListenPort")
            client.send(helloPacket)

            val receiveBuffer = ByteArray(100)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            client.receive(receivePacket)

            val decoded = String(receivePacket.data, receivePacket.offset, receivePacket.length)
            Assert.assertEquals("Hello", decoded)
        }finally {
            node1.close()
            node2.close()
        }
    }


}