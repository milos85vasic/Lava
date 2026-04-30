package lava.network.model

sealed class LavaProxyError : Throwable()

data object BadRequest : LavaProxyError()
data object Forbidden : LavaProxyError()
data object NoData : LavaProxyError()
data object NoConnection : LavaProxyError()
data object NotFound : LavaProxyError()
data object Unauthorized : LavaProxyError()
data object Unknown : LavaProxyError()
