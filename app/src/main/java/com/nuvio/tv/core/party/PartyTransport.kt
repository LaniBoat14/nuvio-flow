package com.nuvio.tv.core.party

enum class PartyTransportProblem { NO_RELAY_REACHABLE, DEVICE_CLOCK_REJECTED }

/** Carries small text messages between the members of one room. Listener calls arrive on the session's thread. */
interface PartyTransport {
    val localId: String

    fun open(access: PartyRoomAccess, listener: Listener)

    fun send(payload: String)

    /** Sends to the code's room while this room runs on a key: answers to members knocking with the code. */
    fun sendLobby(payload: String)

    /** Moves to the room [access] opens, keeping this member's id. */
    fun moveTo(access: PartyRoomAccess)

    /** [text] sealed so only [otherId] can open it. */
    fun sealFor(otherId: String, text: String): String?

    fun openFrom(otherId: String, sealed: String): String?

    fun close()

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(senderId: String, payload: String)
        fun onLobbyMessage(senderId: String, payload: String) {}
        fun onProblem(problem: PartyTransportProblem)
    }
}

internal interface RelayConnector {
    fun connect(url: String, callbacks: RelayCallbacks): RelayConnection
}

internal interface RelayConnection {
    fun send(text: String): Boolean
    fun close()
}

internal interface RelayCallbacks {
    fun onOpen()
    fun onText(text: String)
    fun onClosed()
}

object PartyRelays {
    const val ACTIVE = 3

    val DEFAULT: List<String> = listOf(
        "wss://relay.snort.social",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://nostr.bitcoiner.social",
        "wss://nostr.oxtr.dev",
        "wss://nostr.mom",
        "wss://relay.damus.io",
    )

    /** Where the built-in relays are hosted. The active relays come from different providers while they can. */
    private val PROVIDERS: Map<String, String> = mapOf(
        "offchain.pub" to "incognet",
        "nostr.bitcoiner.social" to "incognet",
        "relay.snort.social" to "apex",
        "nos.lol" to "hetzner",
        "nostr.mom" to "hetzner",
        "nostr.oxtr.dev" to "hetzner",
        "relay.primal.net" to "cloudflare",
        "relay.damus.io" to "cloudflare",
    )

    fun provider(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return PROVIDERS[host] ?: host
    }

    fun parse(text: String): List<String> = text
        .split('\n', ',', ' ')
        .map { it.trim() }
        .filter { it.startsWith("wss://") || it.startsWith("ws://") }
        .distinct()
}
