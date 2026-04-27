package lava.auth.api

interface TokenProvider {
    suspend fun getToken(): String
    suspend fun refreshToken(): Boolean
}
