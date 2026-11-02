package cc.jaxy.bob.feature.chat

/** Keeps the process network-capable while an answer is generated. */
interface ChatGenerationKeepAlive {
    fun start()
    fun stop()
}
